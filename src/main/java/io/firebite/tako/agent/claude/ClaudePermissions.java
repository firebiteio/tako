package io.firebite.tako.agent.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.PermissionAnswer;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.agent.claude.protocol.ClaudeTool;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** The CLI's {@code can_use_tool} requests as prompts, and the person's answers as its permission responses. */
final class ClaudePermissions {
  private static final String INPUT = "input";
  private static final String SUGGESTIONS = "permission_suggestions";

  private ClaudePermissions() {
  }

  static PermissionPrompt prompt(JsonObject request) {
    String toolName = String.valueOf(Json.string(request, "tool_name"));
    ClaudeTool tool = ClaudeTool.named(toolName);
    JsonObject input = Json.object(request, INPUT);
    if (input == null) {
      input = new JsonObject();
    }
    PermissionPrompt.Kind kind = tool == ClaudeTool.ASK_USER_QUESTION
      ? PermissionPrompt.Kind.QUESTION
      : tool == ClaudeTool.EXIT_PLAN_MODE ? PermissionPrompt.Kind.PLAN : PermissionPrompt.Kind.TOOL;
    String description = Json.string(request, "decision_reason");
    if (description == null || description.isEmpty()) {
      description = Json.string(request, "description");
    }
    return new PermissionPrompt(kind, toolName, ClaudeTool.kindOf(toolName), input, Json.string(request, "tool_use_id"),
      Json.string(request, "display_name"), description, ClaudeTool.kindOf(toolName) == ToolKind.EDIT,
      suggestions(request), questions(input), kind == PermissionPrompt.Kind.PLAN ? Json.string(input, "plan") : null);
  }

  /** The {@code control_response} body for the answer to {@code request}. */
  static JsonObject response(JsonObject request, PermissionAnswer answer) {
    if (!answer.allow()) {
      JsonObject deny = Json.of("behavior", "deny");
      deny.addProperty("message", answer.message() != null ? answer.message() : "The user declined this tool call.");
      if (answer.interrupt()) {
        deny.addProperty("interrupt", true);
      }
      return deny;
    }
    JsonObject input = Json.object(request, INPUT);
    JsonObject updatedInput = input != null ? input.deepCopy() : new JsonObject();
    if (!answer.answers().isEmpty()) {
      JsonObject answers = new JsonObject();
      for (Map.Entry<String, String> entry : answer.answers().entrySet()) {
        answers.addProperty(entry.getKey(), entry.getValue());
      }
      updatedInput.add("answers", answers);
    }
    JsonObject allow = Json.of("behavior", "allow");
    allow.add("updatedInput", updatedInput);
    List<JsonObject> suggestions = Json.objects(request, SUGGESTIONS);
    Integer index = answer.suggestion();
    if (index != null && index >= 0 && index < suggestions.size()) {
      JsonArray permissions = new JsonArray();
      permissions.add(suggestions.get(index));
      allow.add("updatedPermissions", permissions);
    }
    return allow;
  }

  private static List<PermissionPrompt.Suggestion> suggestions(JsonObject request) {
    List<PermissionPrompt.Suggestion> suggestions = new ArrayList<>();
    for (JsonObject suggestion : Json.objects(request, SUGGESTIONS)) {
      String type = String.valueOf(Json.string(suggestion, "type"));
      String mode = Json.string(suggestion, "mode");
      suggestions.add(switch (type) {
        case "setMode" -> new PermissionPrompt.Suggestion(TakoBundle.message("permission.switch.mode", modeLabel(mode)),
          mode);
        case "addRules" -> new PermissionPrompt.Suggestion(rulesLabel(suggestion), null);
        case "addDirectories" -> new PermissionPrompt.Suggestion(
          TakoBundle.message("permission.allow.directories", String.join(", ", strings(suggestion, "directories"))),
          null);
        default -> new PermissionPrompt.Suggestion(TakoBundle.message("permission.always.allow"), null);
      });
    }
    return suggestions;
  }

  private static String rulesLabel(JsonObject suggestion) {
    if (Json.array(suggestion, "rules") == null) {
      return TakoBundle.message("permission.always.allow");
    }
    List<String> rules = new ArrayList<>();
    for (JsonObject rule : Json.objects(suggestion, "rules")) {
      String content = Json.string(rule, "ruleContent");
      rules.add(Json.string(rule, "toolName") + (content != null && !content.isEmpty() ? "(" + content + ")" : ""));
    }
    return TakoBundle.message("permission.always.allow.rules", String.join(", ", rules));
  }

  private static String modeLabel(@Nullable String mode) {
    for (AgentInfo.Mode candidate : ClaudeBackend.modes()) {
      if (candidate.value().equals(mode)) {
        return candidate.label();
      }
    }
    return String.valueOf(mode);
  }

  private static List<PermissionPrompt.Question> questions(JsonObject input) {
    List<PermissionPrompt.Question> questions = new ArrayList<>();
    for (JsonObject question : Json.objects(input, "questions")) {
      List<PermissionPrompt.Option> options = new ArrayList<>();
      for (JsonObject option : Json.objects(question, "options")) {
        options.add(new PermissionPrompt.Option(String.valueOf(Json.string(option, "label")),
          Json.string(option, "description")));
      }
      questions.add(new PermissionPrompt.Question(String.valueOf(Json.string(question, "question")),
        Json.string(question, "header"), Json.bool(question, "multiSelect"), options));
    }
    return questions;
  }

  private static List<String> strings(JsonObject object, String key) {
    List<String> values = new ArrayList<>();
    JsonArray array = Json.array(object, key);
    if (array != null) {
      array.forEach(element -> values.add(element.isJsonPrimitive() ? element.getAsString() : element.toString()));
    }
    return values;
  }
}
