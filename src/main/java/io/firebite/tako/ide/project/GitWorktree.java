package io.firebite.tako.ide.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Makes linked git worktrees next to the main checkout, in {@code <repo>.worktrees/<name>}, each on a new branch. */
public final class GitWorktree {
  private static final int NAME_LENGTH = 40;
  private static final long ADD_TIMEOUT_SECONDS = 120;

  private GitWorktree() {
  }

  /**
   * Checks out the commit {@code directory} is on into a new worktree on branch {@code <name>}, with the files
   * {@code .worktreeinclude} lists.
   *
   * @return the worktree, or null when {@code directory} is not in a git repository or git fails
   */
  public static @Nullable Worktree create(Path directory, String name, Map<String, String> environment) {
    Path repository;
    try {
      repository = Worktrees.mainCheckout(directory, environment);
    } catch (Worktrees.GitException e) {
      return null;
    }
    Path parent = repository.resolveSibling(repository.getFileName() + ".worktrees");
    String slug = slug(name);
    for (int i = 1;; i++) {
      String candidate = i == 1 ? slug : slug + "-" + i;
      Path folder = parent.resolve(candidate);
      boolean isTaken = Files.exists(folder) || GitInfo.run(directory, environment,
        List.of("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + candidate), ADD_TIMEOUT_SECONDS) != null;
      if (!isTaken) {
        String added = GitInfo.run(directory, environment,
          List.of("git", "worktree", "add", "-b", candidate, folder.toString()), ADD_TIMEOUT_SECONDS);
        if (added == null) {
          return null;
        }
        WorktreeInclude.copy(folder, environment);
        return new Worktree(folder, candidate, repository);
      }
    }
  }

  /** Lower case letters and digits joined by dashes, e.g. {@code skeletal-animation}. */
  static String slug(String name) {
    String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (slug.length() > NAME_LENGTH) {
      slug = slug.substring(0, NAME_LENGTH).replaceAll("-+$", "");
    }
    return slug.isEmpty() ? "thread" : slug;
  }

  /** @param repository the main checkout's top folder */
  public record Worktree(
    Path folder,
    String branch,
    Path repository
  ) {
  }
}
