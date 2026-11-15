package io.firebite.tako.agent.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

/**
 * Turns ACP {@code session/update}s into {@link AgentEvent}s. ACP streams chunks without block boundaries, so a text or
 * thought block ends when anything else arrives or the turn ends.
 */
final class AcpTranslator {
  private static final String TEXT = "text";
  private static final String CONTENT = "content";

  /** Receives the content a file had before a tool call changed it. */
  private final BiConsumer<String, String> onFileEdited;
  private final Map<String, JsonObject> tools = new HashMap<>();
  private @Nullable String textBlock;
  private final StringBuilder text = new StringBuilder();
  private @Nullable String thoughtBlock;
  private final StringBuilder prompt = new StringBuilder();
  private int blocks;
  private int plans;

  AcpTranslator(BiConsumer<String, String> onFileEdited) {
    this.onFileEdited = onFileEdited;
  }

  List<AgentEvent> update(JsonObject update) {
    List<AgentEvent> events = new ArrayList<>();
    String kind = String.valueOf(Json.string(update, "sessionUpdate"));
    if (!"agent_message_chunk".equals(kind)) {
      this.closeText(events);
    }
    if (!"agent_thought_chunk".equals(kind)) {
      this.closeThought(events);
    }
    if (!"user_message_chunk".equals(kind)) {
      this.closePrompt(events);
    }
    switch (kind) {
      case "agent_message_chunk" -> this.message(update, events);
      case "agent_thought_chunk" -> {
        if (this.thoughtBlock == null) {
          this.thoughtBlock = "thought-" + this.blocks++;
          events.add(new AgentEvent.ThinkingStarted(this.thoughtBlock));
        }
      }
      case "user_message_chunk" -> this.prompt.append(chunkText(update));
      case "tool_call" -> this.toolCall(update, events);
      case "tool_call_update" -> this.toolCallUpdate(update, events);
      case "plan" -> events.addAll(this.plan(update));
      case "available_commands_update" -> events.add(new AgentEvent.CommandsChanged(commands(update)));
      case "current_mode_update" -> events.add(new AgentEvent.ModeChanged(String.valueOf(Json.string(update,
        "currentModeId"))));
      case "usage_update" -> events.add(new AgentEvent.Usage(Json.number(update, "used"), 0, 0, 0, 0, 0, null,
        Json.optionalNumber(update, "size")));
      default -> {
      }
    }
    return events;
  }

  /** Ends what still streams, at the end of a turn or of a replayed session. */
  List<AgentEvent> flush() {
    List<AgentEvent> events = new ArrayList<>();
    this.closeText(events);
    this.closeThought(events);
    this.closePrompt(events);
    return events;
  }

  /** The tool call a permission request is about, as the agent described it so far. */
  @Nullable
  JsonObject tool(String toolCallId) {
    return this.tools.get(toolCallId);
  }

  static ToolKind kind(@Nullable String kind) {
    if (kind == null) {
      return ToolKind.OTHER;
    }
    return switch (kind) {
      case "read" -> ToolKind.READ;
      case "edit", "delete", "move" -> ToolKind.EDIT;
      case "search" -> ToolKind.SEARCH;
      case "execute" -> ToolKind.COMMAND;
      case "fetch" -> ToolKind.WEB;
      case "switch_mode" -> ToolKind.PLAN;
      default -> ToolKind.OTHER;
    };
  }

  /** The call's arguments with the fields the chat reads filled in from its kind, locations and title. */
  static JsonObject input(JsonObject toolCall) {
    JsonObject raw = Json.object(toolCall, "rawInput");
    JsonObject input = raw != null ? raw.deepCopy() : new JsonObject();
    String title = Json.string(toolCall, "title");
    List<JsonObject> locations = Json.objects(toolCall, "locations");
    String path = !locations.isEmpty() ? Json.string(locations.getFirst(), "path") : null;
    switch (kind(Json.string(toolCall, "kind"))) {
      case COMMAND -> {
        if (Json.array(input, "command") != null) {
          input.addProperty("command", String.join(" ", Json.strings(input, "command")));
        } else if (Json.string(input, "command") == null && title != null) {
          input.addProperty("command", title);
        }
      }
      case READ, EDIT -> {
        if (Json.string(input, "file_path") == null && path != null) {
          input.addProperty("file_path", path);
        }
      }
      case SEARCH -> {
        if (Json.string(input, "pattern") == null) {
          String query = Json.string(input, "query");
          input.addProperty("pattern", query != null ? query : title);
        }
      }
      default -> {
      }
    }
    return input;
  }

  private void message(JsonObject update, List<AgentEvent> events) {
    if (this.textBlock == null) {
      String messageId = Json.string(update, "messageId");
      this.textBlock = messageId != null ? messageId : "message-" + this.blocks++;
      events.add(new AgentEvent.MessageStarted(this.textBlock));
      events.add(new AgentEvent.TextStarted(this.textBlock));
    }
    String chunk = chunkText(update);
    this.text.append(chunk);
    events.add(new AgentEvent.TextDelta(this.textBlock, chunk));
  }

  private void toolCall(JsonObject update, List<AgentEvent> events) {
    String id = String.valueOf(Json.string(update, "toolCallId"));
    this.tools.put(id, update.deepCopy());
    events.add(new AgentEvent.ToolStarted(id, toolName(update), kind(Json.string(update, "kind")), input(update),
      Json.string(update, "title"), null));
    this.finishIfDone(id, update, events);
  }

  private void toolCallUpdate(JsonObject update, List<AgentEvent> events) {
    String id = String.valueOf(Json.string(update, "toolCallId"));
    merge(this.tools.computeIfAbsent(id, key -> new JsonObject()), update);
    this.finishIfDone(id, update, events);
  }

  /** Copies the fields an update sets onto what is known of the tool call. */
  static void merge(JsonObject toolCall, @Nullable JsonObject update) {
    if (update == null) {
      return;
    }
    for (Map.Entry<String, JsonElement> field : update.entrySet()) {
      if (!field.getValue().isJsonNull()) {
        toolCall.add(field.getKey(), field.getValue());
      }
    }
  }

  static String toolName(JsonObject toolCall) {
    String name = Json.string(toolCall, "name");
    String title = Json.string(toolCall, "title");
    return name != null ? name : title != null ? title : "tool";
  }

  private void finishIfDone(String id, JsonObject update, List<AgentEvent> events) {
    String status = Json.string(update, "status");
    if (!"completed".equals(status) && !"failed".equals(status)) {
      return;
    }
    JsonObject tool = this.tools.remove(id);
    AgentEvent.Change change = null;
    List<String> output = new ArrayList<>();
    for (JsonObject content : Json.objects(tool, CONTENT)) {
      String type = Json.string(content, "type");
      if ("diff".equals(type)) {
        String oldText = Json.string(content, "oldText");
        String newText = String.valueOf(Json.string(content, "newText"));
        String path = String.valueOf(Json.string(content, "path"));
        this.onFileEdited.accept(path, oldText != null ? oldText : "");
        if (change == null) {
          change = change(path, oldText, newText);
        }
      } else if (CONTENT.equals(type)) {
        String part = Json.string(Json.object(content, CONTENT), TEXT);
        if (part != null) {
          output.add(part);
        }
      }
    }
    events.add(new AgentEvent.ToolFinished(id, String.join("\n", output), "failed".equals(status), change));
  }

  /** Lines added and removed, counted as the lines each side has that the other lacks. */
  static AgentEvent.Change change(String path, @Nullable String oldText, String newText) {
    Map<String, Integer> before = new HashMap<>();
    if (oldText != null && !oldText.isEmpty()) {
      for (String line : oldText.split("\n", -1)) {
        before.merge(line, 1, Integer::sum);
      }
    }
    int added = 0;
    for (String line : newText.isEmpty() ? new String[0] : newText.split("\n", -1)) {
      Integer count = before.get(line);
      if (count == null || count == 0) {
        added++;
      } else {
        before.put(line, count - 1);
      }
    }
    int removed = 0;
    for (int count : before.values()) {
      removed += count;
    }
    return new AgentEvent.Change(path, added, removed, oldText == null);
  }

  private List<AgentEvent> plan(JsonObject update) {
    JsonArray todos = new JsonArray();
    for (JsonObject entry : Json.objects(update, "entries")) {
      todos.add(AgentEvent.todo(String.valueOf(Json.string(entry, CONTENT)), String.valueOf(Json.string(entry,
        "status"))));
    }
    return AgentEvent.todoList("plan-" + this.plans++, "plan", todos);
  }

  private static List<AgentInfo.Command> commands(JsonObject update) {
    List<AgentInfo.Command> commands = new ArrayList<>();
    for (JsonObject command : Json.objects(update, "availableCommands")) {
      String description = Json.string(command, "description");
      String hint = Json.string(Json.object(command, "input"), "hint");
      commands.add(new AgentInfo.Command(String.valueOf(Json.string(command, "name")),
        description != null ? description : "", hint != null ? hint : ""));
    }
    return commands;
  }

  private void closeText(List<AgentEvent> events) {
    if (this.textBlock != null) {
      events.add(new AgentEvent.Text(this.textBlock, this.text.toString(), null));
      this.textBlock = null;
      this.text.setLength(0);
    }
  }

  private void closeThought(List<AgentEvent> events) {
    if (this.thoughtBlock != null) {
      events.add(new AgentEvent.BlockEnded(this.thoughtBlock));
      this.thoughtBlock = null;
    }
  }

  private void closePrompt(List<AgentEvent> events) {
    if (this.prompt.isEmpty()) {
      return;
    }
    String typed = this.prompt.toString();
    this.prompt.setLength(0);
    events.add(new AgentEvent.Prompt(PromptComposer.typedPart(typed), PromptComposer.hasContext(typed), List.of()));
  }

  private static String chunkText(JsonObject update) {
    JsonObject content = Json.object(update, CONTENT);
    String type = Json.string(content, "type");
    if (TEXT.equals(type)) {
      return String.valueOf(Json.string(content, TEXT));
    }
    return type == null ? "" : "[" + type + "]";
  }
}
