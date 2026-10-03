package io.firebite.tako.agent;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The person's answer to a {@link PermissionPrompt}.
 *
 * @param message    why it was declined, which the agent reads
 * @param interrupt  decline and stop the turn
 * @param answers    question → answer, for {@link PermissionPrompt.Kind#QUESTION}
 * @param suggestion the index of the {@link PermissionPrompt.Suggestion} approved with
 */
public record PermissionAnswer(
  boolean allow,
  @Nullable String message,
  boolean interrupt,
  Map<String, String> answers,
  @Nullable Integer suggestion
) {
}
