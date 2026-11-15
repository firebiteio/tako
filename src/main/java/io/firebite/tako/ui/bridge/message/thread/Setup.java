package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;
import org.jetbrains.annotations.Nullable;

/**
 * How the {@code .worktreesetup} of the thread's new worktree is doing. DONE with no steps only takes the card away.
 *
 * @param step      the running or failed command's number, from 1
 * @param output    the end of the output so far
 * @param error     why it failed, when it did
 * @param startedAt when the setup started, in epoch millis
 * @param endedAt   when it ended, 0 while it runs
 */
public record Setup(
  SetupPhase phase,
  int step,
  int steps,
  String command,
  String output,
  @Nullable String error,
  long startedAt,
  long endedAt
) implements ThreadMessage {
}
