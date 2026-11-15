package io.firebite.tako.ui.bridge.message.workspace;

import org.jetbrains.annotations.Nullable;

/**
 * The usage limit the thread's agent stopped on, while the chat offers to resume after it resets.
 *
 * @param label    the limit's window, e.g. {@code 5h}, when the agent named it
 * @param resetsAt epoch millis, 0 when the agent did not say
 * @param prompt   what the resume sends
 * @param fireAt   when the resume goes out, if scheduled: a little after the reset
 */
public record LimitItem(
  @Nullable String label,
  long resetsAt,
  boolean scheduled,
  String prompt,
  long fireAt
) {
}
