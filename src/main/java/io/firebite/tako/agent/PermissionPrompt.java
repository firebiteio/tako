package io.firebite.tako.agent;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The agent waits for the person to approve a tool call, answer questions or accept a plan.
 *
 * @param toolName    the agent's tool name
 * @param input       the call's arguments, shown as its detail
 * @param toolUseId   the tool call it is about, which declining marks as declined
 * @param label       what to call the tool instead of its name
 * @param canDiff     the IDE can show what the call would change
 * @param suggestions broader approvals offered beside approving once, e.g. "always allow"
 */
public record PermissionPrompt(
  Kind kind,
  String toolName,
  ToolKind category,
  JsonObject input,
  @Nullable String toolUseId,
  @Nullable String label,
  @Nullable String description,
  boolean canDiff,
  List<Suggestion> suggestions,
  List<Question> questions,
  @Nullable String plan
) {

  public enum Kind {
    @SerializedName("tool") TOOL,
    @SerializedName("question") QUESTION,
    @SerializedName("plan") PLAN
  }

  /** @param mode the permission mode approving with this suggestion switches to */
  public record Suggestion(
    String label,
    @Nullable String mode
  ) {
  }

  public record Question(
    String question,
    @Nullable String header,
    boolean multiSelect,
    List<Option> options
  ) {
  }

  public record Option(
    String label,
    @Nullable String description
  ) {
  }
}
