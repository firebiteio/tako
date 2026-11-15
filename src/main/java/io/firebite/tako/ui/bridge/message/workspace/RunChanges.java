package io.firebite.tako.ui.bridge.message.workspace;

import org.jetbrains.annotations.Nullable;

/**
 * What a run changed against the commit the bench started from.
 *
 * @param error why the run's changes could not be read, such as a removed worktree
 */
public record RunChanges(
  int files,
  int added,
  int removed,
  @Nullable String error
) {
}
