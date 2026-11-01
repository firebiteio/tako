package io.firebite.tako.ide.context;

import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;

/**
 * Where review comments on a diff go and what its two versions are, for the agent.
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

  /** What the agent changed in a turn or a parallel run; the new version is the file on disk. */
  public static ReviewTarget turn(String threadId, String path) {
    return new ReviewTarget(threadId, path, new Version("before your change", false),
      new Version("after your change", true));
  }

  /** What a parallel run changed in its worktree since the commit it started from; the new version is on disk. */
  public static ReviewTarget run(String threadId, String path) {
    return new ReviewTarget(threadId, path, new Version("the commit this run started from", false),
      new Version("as your changes left it", true));
  }

  /** An edit a permission prompt proposes; the old version is the file on disk. */
  public static ReviewTarget proposed(String threadId, String path) {
    return new ReviewTarget(threadId, path, new Version("the current file, before your proposed edit", true),
      new Version("your proposed edit, not applied yet", false));
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
