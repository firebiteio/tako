package io.firebite.tako.agent.claude;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.claude.protocol.ClaudeTool;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.agent.claude.protocol.FileChange;
import io.firebite.tako.agent.claude.transcript.SessionUsage;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.json.Json;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Turns the CLI's stream-json lines, live or from a transcript, into {@link AgentEvent}s. */
final class ClaudeTranslator {
  private static final String INTERRUPTED_PREFIX = "[Request interrupted";
  private static final Pattern COMMAND_NAME = Pattern.compile("<command-name>([^<]*)</command-name>");
  private static final String TEXT = "text";
  private static final String PARENT_TOOL_USE_ID = "parent_tool_use_id";

  private ClaudeTranslator() {
  }

  /**
   * @param change    what a Write or Edit in the line's tool result did
   * @param replaying the line is from a transcript, whose user prompts start turns
   */
  static List<AgentEvent> translate(CliMessage message, @Nullable FileChange change, boolean replaying) {
    List<AgentEvent> events = new ArrayList<>();
    JsonObject json = message.json();
    switch (message.type()) {
      case STREAM_EVENT -> {
        if (Json.string(json, PARENT_TOOL_USE_ID) == null) {
          stream(Json.object(json, "event"), events);
        }
      }
      case ASSISTANT -> assistant(json, events);
      case USER -> user(json, change, replaying, events);
      case SYSTEM -> system(message, events);
      case RESULT -> events.add(result(message));
      case OTHER -> other(json, events);
      default -> {
      }
    }
    return events;
  }

  /** The event a {@code result} line ends its turn with. */
  static AgentEvent.TurnEnded result(CliMessage message) {
    JsonObject json = message.json();
    String subtype = Json.string(json, "subtype");
    boolean failed = Json.bool(json, "is_error") || (subtype != null && !subtype.equals("success"));
    String result = Json.string(json, "result");
    String error = !failed
      ? null
      : result != null && !result.isEmpty()
        ? result
        : subtype != null && !subtype.isEmpty() ? subtype : TakoBundle.message("error.claude.failed");
    Double cost = message.totalCostUsd();
    return new AgentEvent.TurnEnded(
      Json.isNumber(json, "duration_ms") && Json.number(json, "duration_ms") > 0
        ? Json.number(json, "duration_ms")
        : null,
      error,
      cost == null ? null : SessionUsage.cost(SessionUsage.models(message.modelUsage()), cost));
  }

  private static void stream(@Nullable JsonObject event, List<AgentEvent> events) {
    String index = Json.isNumber(event, "index") ? String.valueOf(Json.number(event, "index")) : "";
    switch (String.valueOf(Json.string(event, "type"))) {
      case "message_start" -> {
        JsonObject body = Json.object(event, "message");
        events.add(new AgentEvent.MessageStarted(Json.string(body, "id")));
        AgentEvent.Usage usage = usage(Json.object(body, "usage"), null);
        if (usage != null) {
          events.add(usage);
        }
      }
      case "content_block_start" -> {
        String type = Json.string(Json.object(event, "content_block"), "type");
        if (TEXT.equals(type)) {
          events.add(new AgentEvent.TextStarted(index));
        } else if ("thinking".equals(type)) {
          events.add(new AgentEvent.ThinkingStarted(index));
        }
      }
      case "content_block_delta" -> {
        JsonObject delta = Json.object(event, "delta");
        if ("text_delta".equals(Json.string(delta, "type"))) {
          events.add(new AgentEvent.TextDelta(index, String.valueOf(Json.string(delta, TEXT))));
        }
      }
      case "content_block_stop" -> events.add(new AgentEvent.BlockEnded(index));
      default -> {
      }
    }
  }

  private static void assistant(JsonObject json, List<AgentEvent> events) {
    JsonObject body = Json.object(json, "message");
    String parentId = Json.string(json, PARENT_TOOL_USE_ID);
    if (parentId == null) {
      AgentEvent.Usage usage = usage(Json.object(body, "usage"), timestamp(Json.string(json, "timestamp")));
      if (usage != null) {
        events.add(usage);
      }
    }
    for (JsonObject block : Json.objects(body, "content")) {
      String type = Json.string(block, "type");
      if (TEXT.equals(type)) {
        events.add(new AgentEvent.Text(Json.string(body, "id"), String.valueOf(Json.string(block, TEXT)), parentId));
      } else if ("tool_use".equals(type)) {
        String name = String.valueOf(Json.string(block, "name"));
        JsonObject input = Json.object(block, "input");
        events.add(new AgentEvent.ToolStarted(String.valueOf(Json.string(block, "id")), name, ClaudeTool.kindOf(name),
          input != null ? input : new JsonObject(), null, parentId));
      }
    }
  }

  private static void user(JsonObject json, @Nullable FileChange change, boolean replaying, List<AgentEvent> events) {
    JsonObject body = Json.object(json, "message");
    boolean isPrompt = replaying && Json.string(json, PARENT_TOOL_USE_ID) == null;
    JsonElement content = body == null ? null : body.get("content");
    if (content != null && content.isJsonPrimitive()) {
      if (isPrompt) {
        prompt(content.getAsString(), List.of(), events);
      }
      return;
    }
    List<ImageAttachment> images = new ArrayList<>();
    String text = null;
    for (JsonObject block : Json.objects(body, "content")) {
      switch (String.valueOf(Json.string(block, "type"))) {
        case "tool_result" -> events.add(new AgentEvent.ToolFinished(String.valueOf(Json.string(block, "tool_use_id")),
          resultText(block.get("content")), Json.bool(block, "is_error"), change == null
            ? null
            : new AgentEvent.Change(change.filePath(), change.addedLines(), change.removedLines(), change.isNew())));
        case "image" -> {
          JsonObject source = Json.object(block, "source");
          if ("base64".equals(Json.string(source, "type"))) {
            images.add(new ImageAttachment(String.valueOf(Json.string(source, "media_type")),
              String.valueOf(Json.string(source, "data")), "image"));
          }
        }
        case TEXT -> {
          String blockText = String.valueOf(Json.string(block, TEXT));
          if (blockText.startsWith(INTERRUPTED_PREFIX)) {
            events.add(new AgentEvent.Interrupted());
          } else if (text == null) {
            text = blockText;
          }
        }
        default -> {
        }
      }
    }
    if (isPrompt && (text != null || !images.isEmpty())) {
      prompt(text != null ? text : "", images, events);
    }
  }

  /** A recorded prompt, with slash commands by name, IDE context as a flag and CLI-internal text skipped. */
  private static void prompt(String text, List<ImageAttachment> images, List<AgentEvent> events) {
    Matcher command = COMMAND_NAME.matcher(text);
    if (command.find()) {
      events.add(new AgentEvent.Prompt(command.group(1), false, List.of()));
      return;
    }
    if (text.startsWith("<") && images.isEmpty()) {
      return;
    }
    events.add(new AgentEvent.Prompt(PromptComposer.typedPart(text), PromptComposer.hasContext(text), images));
  }

  private static void system(CliMessage message, List<AgentEvent> events) {
    JsonObject json = message.json();
    String subtype = String.valueOf(message.subtype());
    switch (subtype) {
      case CliMessage.SUBTYPE_INIT -> events.add(new AgentEvent.SessionStarted(message.sessionId(),
        message.workingDirectory(), Json.string(json, "model"), Json.string(json, "permissionMode"),
        Json.string(json, "scratchpad_path")));
      case "status" -> {
        if ("requesting".equals(Json.string(json, "status"))) {
          events.add(new AgentEvent.Busy());
        }
      }
      case "api_retry" -> events.add(new AgentEvent.Retrying(
        optionalInt(json, "attempt"), null));
      case "compact_boundary" -> events.add(new AgentEvent.Compacted());
      case "task_started", "task_progress", "task_updated", "task_notification" -> {
        AgentEvent.TaskUpdated task = task(json, subtype);
        if (task != null) {
          events.add(task);
        }
      }
      default -> {
      }
    }
  }

  private static @Nullable AgentEvent.TaskUpdated task(JsonObject json, String subtype) {
    String taskId = Json.string(json, "task_id");
    if (taskId == null || taskId.isEmpty()) {
      return null;
    }
    JsonObject patch = "task_updated".equals(subtype) ? Json.object(json, "patch") : null;
    String status = Json.string(patch, "status");
    if (status == null || status.isEmpty()) {
      status = Json.string(json, "status");
    }
    JsonObject usage = Json.object(json, "usage");
    List<AgentEvent.WorkflowAgent> agents = new ArrayList<>();
    List<String> logs = new ArrayList<>();
    for (JsonObject item : Json.objects(json, "workflow_progress")) {
      String type = Json.string(item, "type");
      if ("workflow_agent".equals(type) && Json.isNumber(item, "index")) {
        agents.add(new AgentEvent.WorkflowAgent((int) Json.number(item, "index"), Json.string(item, "label"),
          Json.string(item, "state"), Json.string(item, "model"), optionalInt(item, "toolCalls"),
          Json.optionalNumber(item, "tokens"), optionalInt(item, "phaseIndex"),
          Json.string(item, "phaseTitle"), Json.string(item, "error"), Json.string(item, "resultPreview"),
          Json.string(item, "promptPreview")));
      } else if ("workflow_log".equals(type) && Json.string(item, "message") != null) {
        logs.add(Json.string(item, "message"));
      }
    }
    return new AgentEvent.TaskUpdated(taskId, Json.string(json, "tool_use_id"),
      taskType(Json.string(json, "task_type")),
      Json.string(json, "description"), Json.string(json, "subagent_type"), Json.string(json, "prompt"),
      json.has("is_backgrounded") ? Json.bool(json, "is_backgrounded") : null, status, Json.string(json, "summary"),
      optionalInt(usage, "tool_uses"), Json.optionalNumber(usage, "total_tokens"),
      Json.string(json, "last_tool_name"),
      Json.isNumber(patch, "end_time") && Json.number(patch, "end_time") > 0 ? Json.number(patch, "end_time") : null,
      "task_notification".equals(subtype), agents, logs);
  }

  private static @Nullable String taskType(@Nullable String type) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "local_agent" -> "agent";
      case "local_bash" -> "shell";
      case "local_workflow" -> "workflow";
      default -> type;
    };
  }

  private static void other(JsonObject json, List<AgentEvent> events) {
    String type = String.valueOf(Json.string(json, "type"));
    if ("rate_limit_event".equals(type)) {
      JsonObject windows = Json.object(Json.object(json, "rate_limit_info"), "unifiedWindows");
      if (windows == null) {
        return;
      }
      List<AgentEvent.RateLimit> limits = new ArrayList<>();
      addLimit(limits, "5h", Json.object(windows, "five_hour"));
      addLimit(limits, "7d", Json.object(windows, "seven_day"));
      events.add(new AgentEvent.RateLimits(limits));
    } else if ("control_cancel_request".equals(type)) {
      events.add(new AgentEvent.PermissionCancelled(String.valueOf(Json.string(json, "request_id"))));
    }
  }

  private static void addLimit(List<AgentEvent.RateLimit> limits, String label, @Nullable JsonObject window) {
    if (window != null) {
      limits.add(new AgentEvent.RateLimit(label, Json.decimal(window, "utilization") * 100));
    }
  }

  private static @Nullable AgentEvent.Usage usage(@Nullable JsonObject usage, @Nullable Long at) {
    if (usage == null) {
      return null;
    }
    JsonObject split = Json.object(usage, "cache_creation");
    return new AgentEvent.Usage(Json.number(usage, "input_tokens"), Json.number(usage, "output_tokens"),
      Json.number(usage, "cache_read_input_tokens"), Json.number(usage, "cache_creation_input_tokens"),
      Json.number(split, "ephemeral_1h_input_tokens"), Json.number(split, "ephemeral_5m_input_tokens"), at, null);
  }

  private static @Nullable Long timestamp(@Nullable String timestamp) {
    if (timestamp == null) {
      return null;
    }
    try {
      return Instant.parse(timestamp).toEpochMilli();
    } catch (DateTimeException e) {
      return null;
    }
  }

  /** A tool result's text, with non-text parts read as {@code [image]} and the like. */
  private static String resultText(@Nullable JsonElement content) {
    if (content == null) {
      return "";
    }
    if (content.isJsonPrimitive()) {
      return content.getAsString();
    }
    if (!content.isJsonArray()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (JsonElement element : content.getAsJsonArray()) {
      JsonObject part = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
      String type = Json.string(part, "type");
      parts.add(TEXT.equals(type) ? String.valueOf(Json.string(part, TEXT)) : "[" + type + "]");
    }
    return String.join("\n", parts);
  }

  private static @Nullable Integer optionalInt(@Nullable JsonObject object, String key) {
    return Json.isNumber(object, key) ? (int) Json.number(object, key) : null;
  }
}
