package io.firebite.tako.ide.context;

import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;

/**
 * Where review comments on a diff go and what its two versions are, for the agent. The versions do not say "your": a
 * comment whose thread was closed goes to another thread, whose agent did not make the change.
 *
 * @param threadId the thread whose changes the diff shows, null for the active thread
 * @param path     the file as the agent reads it, with {@code /} separators
 */
public record ReviewTarget(
  @Nullable String threadId,
  String path,
  Version before,
  Version after
) {
  /** Kept on the diff requests Tako opens. */
  public static final Key<ReviewTarget> KEY = Key.create("tako.review.target");

  public ReviewTarget {
    path = path.replace('\\', '/');
  }

  static final String PROPOSED_BEFORE = "the current file, before the proposed edit";
  static final String PROPOSED_AFTER = "the proposed edit, not applied yet";

  /** What the agent changed in a turn; the new version is the file on disk. */
  public static ReviewTarget turn(String threadId, String path) {
    return new ReviewTarget(threadId, path, new Version("before the agent's change", false),
      new Version("after the agent's change", true));
  }

  /** What a parallel run changed in its worktree since the commit it started from; the new version is on disk. */
  public static ReviewTarget run(String threadId, String path) {
    return new ReviewTarget(threadId, path, new Version("the commit the parallel run started from", false),
      new Version("as the parallel run's changes left it", true));
  }

  /** An edit a permission prompt proposes; the old version is the file on disk. */
  public static ReviewTarget proposed(String threadId, String path) {
    return new ReviewTarget(threadId, path, new Version(PROPOSED_BEFORE, true), new Version(PROPOSED_AFTER, false));
  }

  /** A file in an editor, as it is. */
  public static ReviewTarget file(String path) {
    return new ReviewTarget(null, path, new Version(null, false), new Version(null, false));
  }

  /**
   * @param description how the agent knows the version, null for the file as it is
   * @param isOnDisk    the version is the file on disk, so it can have changed since the diff opened
   */
  public record Version(
    @Nullable String description,
    boolean isOnDisk
  ) {
  }
}
