package io.firebite.tako.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What an agent did, in terms every agent shares. Each backend translates its own protocol into these, and the page
 * renders them. {@code webview/src/bridge/protocol.ts} mirrors them. The wire name is the record name in lower camel
 * case, sent as {@code type}, without null fields.
 */
public sealed interface AgentEvent {

  /**
   * A plan the agent keeps, shown as a finished todo-list tool call.
   *
   * @param todos each item's {@code content} and {@code status}, which is {@code pending}, {@code in_progress} or
   *              {@code completed}
   */
  static List<AgentEvent> todoList(String id, String name, JsonArray todos) {
    JsonObject input = new JsonObject();
    input.add("todos", todos);
    return List.of(new ToolStarted(id, name, ToolKind.TODO, input, null, null), new ToolFinished(id, "", false, null));
  }

  static JsonObject todo(String content, String status) {
    JsonObject todo = new JsonObject();
    todo.addProperty("content", content);
    todo.addProperty("status", status);
    return todo;
  }

  /**
   * The session is ready.
   *
   * @param sessionId      what resumes it later
   * @param scratchpadPath the folder the agent keeps its scratch files in, if it has one
   */
  record SessionStarted(
    @Nullable String sessionId,
    @Nullable String workingDirectory,
    @Nullable String model,
    @Nullable String mode,
    @Nullable String scratchpadPath
  ) implements AgentEvent {
  }

  record ModeChanged(
    String mode
  ) implements AgentEvent {
  }

  record CommandsChanged(
    List<AgentInfo.Command> commands
  ) implements AgentEvent {
  }

  /** The agent started working on a prompt. */
  record Busy() implements AgentEvent {
  }

  /** A new assistant message begins. Its text blocks stream under this id. */
  record MessageStarted(
    @Nullable String messageId
  ) implements AgentEvent {
  }

  record TextStarted(
    String blockId
  ) implements AgentEvent {
  }

  record TextDelta(
    String blockId,
    String text
  ) implements AgentEvent {
  }

  record ThinkingStarted(
    String blockId
  ) implements AgentEvent {
  }

  /** A streamed text or thinking block is complete. */
  record BlockEnded(
    String blockId
  ) implements AgentEvent {
  }

  /**
   * A complete text block. It finishes the message's streamed block or stands alone.
   *
   * @param parentId the task (subagent) that wrote it
   */
  record Text(
    @Nullable String messageId,
    String text,
    @Nullable String parentId
  ) implements AgentEvent {
  }

  /**
   * A tool call began.
   *
   * @param name  the agent's tool name, for display
   * @param input the call's arguments. The page reads {@code command}, {@code file_path}, {@code pattern},
   *              {@code path}, {@code query}, {@code url}, {@code todos}, {@code old_string}/{@code new_string},
   *              {@code edits} and {@code content} when present
   * @param title a ready-made label, when the agent provides one
   */
  record ToolStarted(
    String id,
    String name,
    ToolKind kind,
    JsonObject input,
    @Nullable String title,
    @Nullable String parentId
  ) implements AgentEvent {
  }

  record ToolFinished(
    String id,
    String output,
    boolean isError,
    @Nullable Change change
  ) implements AgentEvent {
  }

  /** What a tool call did to a file. */
  record Change(
    String path,
    int added,
    int removed,
    boolean isNew
  ) {
  }

  /** A prompt of a replayed session, which starts its turn. */
  record Prompt(
    String text,
    boolean hasIdeContext,
    List<ImageAttachment> images
  ) implements AgentEvent {
  }

  /** The person stopped the turn. */
  record Interrupted() implements AgentEvent {
  }

  /**
   * Tokens of one request, counting the context it filled and the prompt cache it read or wrote.
   *
   * @param at            when the request was made, in epoch millis, or null for now
   * @param contextWindow the model's context size, when the agent reports it
   */
  record Usage(
    long input,
    long output,
    long cacheRead,
    long cacheWrite,
    long cacheWrite1h,
    long cacheWrite5m,
    @Nullable Long at,
    @Nullable Long contextWindow
  ) implements AgentEvent {
  }

  /** @param cost the whole session's cost so far, when the agent reports one */
  record TurnEnded(
    @Nullable Long durationMs,
    @Nullable String error,
    @Nullable SessionCost cost
  ) implements AgentEvent {
  }

  record RateLimits(
    List<RateLimit> limits
  ) implements AgentEvent {
  }

  /** @param usedPercent 0 to 100 */
  record RateLimit(
    String label,
    double usedPercent
  ) {
  }

  record Retrying(
    @Nullable Integer attempt,
    @Nullable String message
  ) implements AgentEvent {
  }

  record Compacted() implements AgentEvent {
  }

  /** The agent withdrew a permission prompt. */
  record PermissionCancelled(
    String requestId
  ) implements AgentEvent {
  }

  /**
   * A subagent, workflow or background shell task started, progressed or ended. Fields left null did not change.
   *
   * @param taskType  {@code agent}, {@code shell} or {@code workflow}
   * @param endedAt   epoch millis
   * @param notified  the task reported its end
   */
  record TaskUpdated(
    String taskId,
    @Nullable String toolUseId,
    @Nullable String taskType,
    @Nullable String description,
    @Nullable String subagentType,
    @Nullable String prompt,
    @Nullable Boolean backgrounded,
    @Nullable String status,
    @Nullable String summary,
    @Nullable Integer toolUses,
    @Nullable Long totalTokens,
    @Nullable String lastTool,
    @Nullable Long endedAt,
    boolean notified,
    List<WorkflowAgent> agents,
    List<String> logs
  ) implements AgentEvent {
  }

  /** One agent of a workflow task. */
  record WorkflowAgent(
    int index,
    @Nullable String label,
    @Nullable String state,
    @Nullable String model,
    @Nullable Integer toolCalls,
    @Nullable Long tokens,
    @Nullable Integer phaseIndex,
    @Nullable String phaseTitle,
    @Nullable String error,
    @Nullable String resultPreview,
    @Nullable String promptPreview
  ) {
  }
}
