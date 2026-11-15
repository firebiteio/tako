package io.firebite.tako.agent.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Turns the app-server's thread items, live or from a resumed thread, into {@link AgentEvent}s. */
final class CodexTranslator {
  static final String SHELL = "Shell";
  private static final String TYPE = "type";
  private static final String TEXT = "text";
  private static final String ID = "id";
  private static final double FULL_PERCENT = 100;
  private static final long TOO_MANY_REQUESTS = 429;

  private CodexTranslator() {
  }

  /** An item began. Text and thinking start streaming, tool calls start running. */
  static List<AgentEvent> started(JsonObject item) {
    List<AgentEvent> events = new ArrayList<>();
    String id = String.valueOf(Json.string(item, ID));
    switch (String.valueOf(Json.string(item, TYPE))) {
      case "agentMessage" -> {
        events.add(new AgentEvent.MessageStarted(id));
        events.add(new AgentEvent.TextStarted(id));
      }
      case "reasoning" -> events.add(new AgentEvent.ThinkingStarted(id));
      case "contextCompaction" -> events.add(new AgentEvent.Compacted());
      default -> {
        AgentEvent.ToolStarted tool = tool(item);
        if (tool != null) {
          events.add(tool);
        }
      }
    }
    return events;
  }

  /** An item is done, with its final text or its tool call's result. */
  static List<AgentEvent> completed(JsonObject item) {
    List<AgentEvent> events = new ArrayList<>();
    String id = String.valueOf(Json.string(item, ID));
    switch (String.valueOf(Json.string(item, TYPE))) {
      case "agentMessage" -> events.add(new AgentEvent.Text(id, String.valueOf(Json.string(item, TEXT)), null));
      case "plan" -> events.add(new AgentEvent.Text(null, String.valueOf(Json.string(item, TEXT)), null));
      case "reasoning" -> events.add(new AgentEvent.BlockEnded(id));
      case "commandExecution" -> {
        String status = Json.string(item, "status");
        boolean failed = "failed".equals(status) || "declined".equals(status)
          || (Json.isNumber(item, "exitCode") && Json.number(item, "exitCode") != 0);
        String output = Json.string(item, "aggregatedOutput");
        events.add(new AgentEvent.ToolFinished(id, output != null ? output : "", failed, null));
      }
      case "fileChange" -> {
        String status = Json.string(item, "status");
        List<JsonObject> changes = Json.objects(item, "changes");
        events.add(new AgentEvent.ToolFinished(id, "", "failed".equals(status) || "declined".equals(status),
          changes.isEmpty() || !"completed".equals(status) ? null : change(changes.getFirst())));
      }
      case "mcpToolCall" -> {
        JsonObject error = Json.object(item, "error");
        events.add(new AgentEvent.ToolFinished(id, error != null
          ? String.valueOf(Json.string(error, "message"))
          : contentText(Json.array(Json.object(item, "result"), "content")), error != null, null));
      }
      case "dynamicToolCall" -> events.add(new AgentEvent.ToolFinished(id,
        contentText(Json.array(item, "contentItems")), Boolean.FALSE.equals(Json.optionalBool(item, "success")), null));
      case "webSearch", "imageView", "collabAgentToolCall" -> events.add(new AgentEvent.ToolFinished(id, "", false,
        null));
      default -> {
      }
    }
    return events;
  }

  /** A resumed thread's turns, as if they had just happened. */
  static List<AgentEvent> history(JsonObject thread) {
    List<AgentEvent> events = new ArrayList<>();
    for (JsonObject turn : Json.objects(thread, "turns")) {
      for (JsonObject item : Json.objects(turn, "items")) {
        String type = Json.string(item, TYPE);
        if ("userMessage".equals(type)) {
          AgentEvent.Prompt prompt = prompt(item);
          if (prompt != null) {
            events.add(prompt);
          }
        } else if ("agentMessage".equals(type) || "plan".equals(type)) {
          events.addAll(completed(item));
        } else if (!"reasoning".equals(type)) {
          events.addAll(started(item));
          events.addAll(completed(item));
        }
      }
      events.add(turnEnded(turn));
    }
    return events;
  }

  static AgentEvent.TurnEnded turnEnded(JsonObject turn) {
    String status = Json.string(turn, "status");
    String error = "failed".equals(status) ? Json.string(Json.object(turn, "error"), "message") : null;
    return new AgentEvent.TurnEnded(Json.optionalNumber(turn, "durationMs"),
      "failed".equals(status) && error == null ? TakoBundle.message("error.codex.failed") : error, null);
  }

  /** The plan Codex keeps for a turn, shown as a todo list. */
  static List<AgentEvent> plan(String id, JsonObject update) {
    JsonArray todos = new JsonArray();
    for (JsonObject step : Json.objects(update, "plan")) {
      String status = String.valueOf(Json.string(step, "status"));
      todos.add(AgentEvent.todo(String.valueOf(Json.string(step, "step")),
        "inProgress".equals(status) ? "in_progress" : status));
    }
    return AgentEvent.todoList(id, "update_plan", todos);
  }

  /** Tokens of the turn's last request. The chat shows how full the context is, not a prompt cache. */
  static AgentEvent.Usage usage(JsonObject update) {
    JsonObject usage = Json.object(update, "tokenUsage");
    JsonObject last = Json.object(usage, "last");
    return new AgentEvent.Usage(Json.number(last, "inputTokens"), Json.number(last, "outputTokens"), 0, 0, 0, 0, null,
      Json.optionalNumber(usage, "modelContextWindow"));
  }

  static List<AgentEvent.RateLimit> rateLimits(@Nullable JsonObject snapshot) {
    List<AgentEvent.RateLimit> limits = new ArrayList<>();
    for (String key : new String[]{"primary", "secondary"}) {
      JsonObject window = Json.object(snapshot, key);
      if (window != null) {
        limits.add(new AgentEvent.RateLimit(windowLabel(window, key), Json.decimal(window, "usedPercent")));
      }
    }
    return limits;
  }

  /**
   * The limit a turn that ended stopped on, or null when it did not stop on one. The reset is the latest of the used up
   * windows', unknown when one of them has none.
   *
   * @param primary   the account's primary rate-limit window as last reported, if any
   * @param secondary the secondary one
   */
  static @Nullable AgentEvent.LimitReached limitReached(JsonObject turn, @Nullable JsonObject primary,
    @Nullable JsonObject secondary, long now) {
    if (!"failed".equals(Json.string(turn, "status")) || !isLimit(Json.object(turn, "error"))) {
      return null;
    }
    return usedUp(primary, secondary, now);
  }

  /**
   * The limit of the used up window that resets last; unknown without one, or when one of them has no reset. A window
   * whose reset passed is old news and does not count.
   *
   * @param now epoch millis
   */
  static AgentEvent.LimitReached usedUp(@Nullable JsonObject primary, @Nullable JsonObject secondary, long now) {
    JsonObject latest = null;
    Long resetsAt = null;
    for (JsonObject window : new JsonObject[]{primary, secondary}) {
      if (window == null || Json.decimal(window, "usedPercent") < FULL_PERCENT) {
        continue;
      }
      Long reset = AgentEvent.LimitReached.epochSeconds(window, "resetsAt");
      if (reset == null) {
        return new AgentEvent.LimitReached(null, null);
      }
      if (reset <= now) {
        continue;
      }
      if (resetsAt == null || reset > resetsAt) {
        resetsAt = reset;
        latest = window;
      }
    }
    String label = latest == null ? null : windowLabel(latest, null);
    return new AgentEvent.LimitReached(label, resetsAt);
  }

  private static boolean isLimit(@Nullable JsonObject error) {
    JsonElement info = error == null ? null : error.get("codexErrorInfo");
    String code = Json.asString(info);
    if (code != null) {
      return "usageLimitExceeded".equals(code) || "rateLimitExceeded".equals(code);
    }
    JsonObject failedAttempts = Json.object(Json.asObject(info), "responseTooManyFailedAttempts");
    return Json.number(failedAttempts, "httpStatusCode") == TOO_MANY_REQUESTS;
  }

  private static @Nullable String windowLabel(JsonObject window, @Nullable String fallback) {
    if (!Json.isNumber(window, "windowDurationMins")) {
      return fallback;
    }
    long minutes = Json.number(window, "windowDurationMins");
    if (minutes % (60 * 24) == 0) {
      return minutes / (60 * 24) + "d";
    }
    return minutes % 60 == 0 ? minutes / 60 + "h" : minutes + "m";
  }

  /** The prompt a {@code userMessage} item holds, null for one without text, which the history leaves out too. */
  static @Nullable AgentEvent.Prompt prompt(JsonObject item) {
    StringBuilder text = new StringBuilder();
    for (JsonObject input : Json.objects(item, "content")) {
      if (TEXT.equals(Json.string(input, TYPE))) {
        text.append(Json.string(input, TEXT));
      }
    }
    if (text.isEmpty()) {
      return null;
    }
    String sent = text.toString();
    return new AgentEvent.Prompt(PromptComposer.typedPart(sent), PromptComposer.hasContext(sent), List.of());
  }

  private static @Nullable AgentEvent.ToolStarted tool(JsonObject item) {
    String id = String.valueOf(Json.string(item, ID));
    JsonObject input = new JsonObject();
    return switch (String.valueOf(Json.string(item, TYPE))) {
      case "commandExecution" -> {
        input.addProperty("command", Json.string(item, "command"));
        input.addProperty("cwd", Json.string(item, "cwd"));
        yield new AgentEvent.ToolStarted(id, SHELL, ToolKind.COMMAND, input, null, null);
      }
      case "fileChange" -> {
        List<JsonObject> changes = Json.objects(item, "changes");
        JsonObject first = changes.isEmpty() ? new JsonObject() : changes.getFirst();
        input.addProperty("file_path", Json.string(first, "path"));
        input.add("changes", Json.array(item, "changes"));
        boolean isNew = "add".equals(Json.string(Json.object(first, "kind"), TYPE));
        yield new AgentEvent.ToolStarted(id, isNew ? "Write" : "Edit", ToolKind.EDIT, input,
          changes.size() > 1 ? "Edited " + changes.size() + " files" : null, null);
      }
      case "mcpToolCall" -> {
        JsonObject arguments = Json.object(item, "arguments");
        yield new AgentEvent.ToolStarted(id, "mcp__" + Json.string(item, "server") + "__" + Json.string(item, "tool"),
          ToolKind.OTHER, arguments != null ? arguments : input, null, null);
      }
      case "dynamicToolCall" -> {
        JsonObject arguments = Json.object(item, "arguments");
        yield new AgentEvent.ToolStarted(id, String.valueOf(Json.string(item, "tool")), ToolKind.OTHER,
          arguments != null ? arguments : input, null, null);
      }
      case "webSearch" -> {
        input.addProperty("query", Json.string(item, "query"));
        yield new AgentEvent.ToolStarted(id, "WebSearch", ToolKind.WEB, input, null, null);
      }
      case "imageView" -> {
        input.addProperty("file_path", Json.string(item, "path"));
        yield new AgentEvent.ToolStarted(id, "ViewImage", ToolKind.READ, input, null, null);
      }
      case "collabAgentToolCall" -> {
        input.addProperty("prompt", Json.string(item, "prompt"));
        yield new AgentEvent.ToolStarted(id, String.valueOf(Json.string(item, "tool")), ToolKind.OTHER, input, null,
          null);
      }
      default -> null;
    };
  }

  /** The lines a unified diff adds and removes. A diff of a new file may be its bare content. */
  static AgentEvent.Change change(JsonObject change) {
    String path = String.valueOf(Json.string(change, "path"));
    boolean isNew = "add".equals(Json.string(Json.object(change, "kind"), TYPE));
    String diff = String.valueOf(Json.string(change, "diff"));
    int added = 0;
    int removed = 0;
    boolean isUnified = diff.startsWith("@@") || diff.startsWith("---") || diff.contains("\n@@");
    for (String line : diff.split("\n", -1)) {
      if (!isUnified) {
        added += line.isEmpty() ? 0 : 1;
      } else if (line.startsWith("+") && !line.startsWith("+++")) {
        added++;
      } else if (line.startsWith("-") && !line.startsWith("---")) {
        removed++;
      }
    }
    return new AgentEvent.Change(path, added, removed, isNew);
  }

  private static String contentText(@Nullable JsonArray content) {
    if (content == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (JsonElement element : content) {
      JsonObject part = Json.asObject(element);
      String text = Json.string(part, TEXT);
      parts.add(text != null ? text : "[" + Json.string(part, TYPE) + "]");
    }
    return String.join("\n", parts);
  }
}
