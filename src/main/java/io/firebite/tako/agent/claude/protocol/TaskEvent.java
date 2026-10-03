package io.firebite.tako.agent.claude.protocol;

import io.firebite.tako.json.Json;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code system/task_*} line, sent when a subagent, workflow or background shell starts, progresses or ends.
 *
 * @param status the reported status, null when this event carries none
 */
public record TaskEvent(
  String taskId,
  @Nullable String toolUseId,
  boolean isStart,
  boolean isLocalAgent,
  boolean isBackgrounded,
  @Nullable String status
) {
  private static final String STARTED = "task_started";
  private static final String UPDATED = "task_updated";
  private static final String LOCAL_AGENT = "local_agent";

  public static @Nullable TaskEvent from(CliMessage message) {
    String taskId = Json.string(message.json(), "task_id");
    if (!message.isTaskEvent() || taskId == null) {
      return null;
    }
    String subtype = message.subtype();
    String status = UPDATED.equals(subtype)
      ? Json.string(Json.object(message.json(), "patch"), "status")
      : Json.string(message.json(), "status");
    return new TaskEvent(taskId, Json.string(message.json(), "tool_use_id"), STARTED.equals(subtype),
      LOCAL_AGENT.equals(Json.string(message.json(), "task_type")), Json.bool(message.json(), "is_backgrounded"),
      status);
  }

  public boolean isFinished() {
    return this.status != null && !"running".equals(this.status) && !"pending".equals(this.status);
  }
}
