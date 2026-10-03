package io.firebite.tako.agent.claude;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.agent.claude.protocol.TaskEvent;
import io.firebite.tako.agent.claude.transcript.TranscriptTail;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Follows running subagents' transcripts and relays their steps as messages of the tool call that started them,
 * since the CLI's stream only reports a subagent's start and end.
 */
final class SubagentRelay {
  private static final Logger LOG = Logger.getInstance(SubagentRelay.class);
  private static final long POLL_MILLIS = 700;
  private static final String PARENT_TOOL_USE_ID = "parent_tool_use_id";

  private final Function<String, @Nullable Path> transcriptOf;
  private final Consumer<CliMessage> relay;
  private final Map<String, Tail> tails = new ConcurrentHashMap<>();
  private final Map<String, String> toolUseOfTask = new ConcurrentHashMap<>();
  private final Set<String> backgroundTasks = ConcurrentHashMap.newKeySet();

  /**
   * @param transcriptOf where a task's transcript is written, null when it cannot be known yet
   * @param relay        receives the subagents' messages, tagged with their parent tool call
   */
  SubagentRelay(Function<String, @Nullable Path> transcriptOf, Consumer<CliMessage> relay) {
    this.transcriptOf = transcriptOf;
    this.relay = relay;
  }

  void onTaskEvent(TaskEvent event) {
    if (event.isStart() && event.isLocalAgent() && event.toolUseId() != null) {
      this.toolUseOfTask.put(event.taskId(), event.toolUseId());
      if (event.isBackgrounded()) {
        this.backgroundTasks.add(event.taskId());
      }
      this.start(event.taskId(), event.toolUseId());
    } else if (event.isFinished()) {
      this.stop(event.taskId(), event.toolUseId() != null ? event.toolUseId() : this.toolUseOfTask.get(event.taskId()));
    }
  }

  /** A foreground agent is over once its tool result arrives, whether or not a final task event follows. */
  void onToolResults(List<CliMessage.ToolResult> results) {
    for (CliMessage.ToolResult result : results) {
      for (Map.Entry<String, String> task : this.toolUseOfTask.entrySet()) {
        if (result.toolUseId().equals(task.getValue()) && !this.backgroundTasks.contains(task.getKey())) {
          this.stop(task.getKey(), result.toolUseId());
        }
      }
    }
  }

  /** The process ended. Relays what the transcripts still hold. */
  void stopAll() {
    for (Map.Entry<String, String> task : this.toolUseOfTask.entrySet()) {
      this.stop(task.getKey(), task.getValue());
    }
  }

  void dispose() {
    for (Tail tail : this.tails.values()) {
      tail.polling().cancel(false);
    }
    this.tails.clear();
  }

  private void start(String taskId, String toolUseId) {
    Path file = this.tails.containsKey(taskId) ? null : this.transcriptOf.apply(taskId);
    if (file == null) {
      return;
    }
    TranscriptTail transcript = new TranscriptTail(file);
    ScheduledFuture<?> polling = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
      () -> this.poll(transcript, toolUseId), POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    this.tails.put(taskId, new Tail(transcript, polling));
  }

  /** Stops following. One last read after a pause catches lines written just before the end. */
  private void stop(String taskId, @Nullable String toolUseId) {
    Tail tail = this.tails.remove(taskId);
    if (tail == null) {
      return;
    }
    tail.polling().cancel(false);
    if (toolUseId != null) {
      AppExecutorUtil.getAppScheduledExecutorService()
        .schedule(() -> this.poll(tail.transcript(), toolUseId), POLL_MILLIS, TimeUnit.MILLISECONDS);
    }
  }

  private void poll(TranscriptTail transcript, String toolUseId) {
    List<JsonObject> lines;
    try {
      lines = transcript.poll();
    } catch (IOException e) {
      LOG.debug("Could not read a subagent transcript", e);
      return;
    }
    for (JsonObject line : lines) {
      CliMessage entry = new CliMessage(line);
      CliMessage.Type type = entry.type();
      if ((type != CliMessage.Type.ASSISTANT && type != CliMessage.Type.USER) || entry.body() == null) {
        continue;
      }
      CliMessage message = CliMessage.of(type, entry.body());
      message.json().addProperty(PARENT_TOOL_USE_ID, toolUseId);
      this.relay.accept(message);
    }
  }

  private record Tail(
    TranscriptTail transcript,
    ScheduledFuture<?> polling
  ) {
  }
}
