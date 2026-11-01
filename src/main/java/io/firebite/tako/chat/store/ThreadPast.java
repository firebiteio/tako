package io.firebite.tako.chat.store;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.agent.AgentEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What a thread's earlier agents did, kept when it switches to another agent, so its timeline and the next agent's
 * handoff still have it after the IDE restarts. Plain prompts and answers only.
 *
 * @param files paths changed before the last switch, oldest first
 * @param todos the latest todo list before the last switch, one {@code - [status] item} per line
 * @param plan  the latest plan the agent proposed before the last switch
 */
public record ThreadPast(
  List<Segment> segments,
  List<String> files,
  @Nullable String todos,
  @Nullable String plan
) {
  public static final ThreadPast EMPTY = new ThreadPast(List.of(), List.of(), null, null);
  private static final Logger LOG = Logger.getInstance(ThreadPast.class);
  private static final Gson GSON = new Gson();

  public boolean isEmpty() {
    return this.segments.isEmpty();
  }

  /** Every turn of every segment, oldest first. */
  public List<Turn> turns() {
    List<Turn> turns = new ArrayList<>();
    for (Segment segment : this.segments) {
      turns.addAll(segment.turns());
    }
    return turns;
  }

  /** This past with only its first {@code count} segments, e.g. without one whose switch never got saved. */
  public ThreadPast firstSegments(int count) {
    return count >= this.segments.size()
      ? this
      : new ThreadPast(this.segments.subList(0, Math.max(0, count)), this.files, this.todos, this.plan);
  }

  /** The past as a replay: each turn's prompt and answer, and a switch after each segment. */
  public List<AgentEvent> events() {
    List<AgentEvent> events = new ArrayList<>();
    for (Segment segment : this.segments) {
      for (Turn turn : segment.turns()) {
        if (turn.prompt() != null) {
          events.add(new AgentEvent.Prompt(turn.prompt(), false, List.of()));
        }
        if (!turn.answer().isBlank()) {
          events.add(new AgentEvent.Text(null, turn.answer(), null));
        }
      }
      events.add(new AgentEvent.AgentSwitched(segment.agentName(), segment.nextAgentName()));
    }
    return events;
  }

  /** The past saved in {@code file}, empty when there is none or it cannot be read. */
  public static ThreadPast read(Path file) {
    if (!Files.isRegularFile(file)) {
      return EMPTY;
    }
    try {
      ThreadPast past = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ThreadPast.class);
      return past == null ? EMPTY : past.normalized();
    } catch (IOException | JsonParseException e) {
      LOG.warn("Could not read the earlier agents of a thread from " + file, e);
      return EMPTY;
    }
  }

  public void write(Path file) throws IOException {
    AtomicFiles.write(file, GSON.toJson(this));
  }

  /** Fills in what Gson leaves null when the file lacks it. */
  private ThreadPast normalized() {
    List<Segment> segments = new ArrayList<>();
    if (this.segments != null) {
      for (Segment segment : this.segments) {
        if (segment != null) {
          segments.add(segment.normalized());
        }
      }
    }
    List<String> files = new ArrayList<>();
    if (this.files != null) {
      for (String path : this.files) {
        if (path != null) {
          files.add(path);
        }
      }
    }
    return new ThreadPast(segments, files, this.todos, this.plan);
  }

  /** The turns one agent ran before the thread switched away from it. */
  public record Segment(
    String agentName,
    String nextAgentName,
    List<Turn> turns
  ) {
    private Segment normalized() {
      List<Turn> turns = new ArrayList<>();
      if (this.turns != null) {
        for (Turn turn : this.turns) {
          if (turn != null) {
            turns.add(new Turn(turn.prompt(), turn.answer() != null ? turn.answer() : ""));
          }
        }
      }
      return new Segment(this.agentName != null ? this.agentName : "",
        this.nextAgentName != null ? this.nextAgentName : "", turns);
    }
  }

  /** @param prompt null for output that came without a prompt of its own */
  public record Turn(
    @Nullable String prompt,
    String answer
  ) {
  }
}
