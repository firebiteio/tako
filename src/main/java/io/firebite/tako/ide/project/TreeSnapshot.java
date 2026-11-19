package io.firebite.tako.ide.project;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.ide.project.Worktrees.GitException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The working tree of a checkout, stored as a git tree. Includes untracked files and skips ignored ones. Compare it
 * with the working tree later to find every changed file, also ones a script wrote.
 *
 * <p>Writes file contents to the repository's object store. Git prunes them later, since no commit refers to them. Uses
 * a copy of the index, so the checkout's own index stays untouched. Close the snapshot to delete the copy.
 */
public final class TreeSnapshot implements AutoCloseable {
  private final Path topLevel;
  private final Map<String, String> environment;
  private final Worktrees.MarkedIndex index;
  private final String tree;

  private TreeSnapshot(Path topLevel, Map<String, String> environment, Worktrees.MarkedIndex index, String tree) {
    this.topLevel = topLevel;
    this.environment = environment;
    this.index = index;
    this.tree = tree;
  }

  /** Takes a snapshot of the checkout that contains {@code directory}. Runs git, so call off the EDT. */
  public static TreeSnapshot take(Path directory, Map<String, String> environment) throws GitException {
    Path topLevel = Worktrees.topLevel(directory, environment);
    Worktrees.MarkedIndex index = Worktrees.MarkedIndex.of(topLevel, environment);
    try {
      return new TreeSnapshot(topLevel, environment, index, writeTree(topLevel, index));
    } catch (GitException e) {
      index.close();
      throw e;
    }
  }

  /**
   * The files that differ between the snapshot and the working tree now, with absolute paths. Binary files have zero
   * added and removed lines.
   */
  public List<AgentEvent.Change> changes() throws GitException {
    String now = writeTree(this.topLevel, this.index);
    if (now.equals(this.tree)) {
      return List.of();
    }
    Map<String, int[]> counts = parseNumstat(Worktrees.git(this.topLevel, this.environment, "diff-tree", "-r", "-z",
      "--no-renames", "--numstat", this.tree, now));
    List<GitDiffs.Change> files = GitDiffs.parseNameStatus(Worktrees.git(this.topLevel, this.environment, "diff-tree",
      "-r", "-z", "--no-renames", "--name-status", this.tree, now));
    List<AgentEvent.Change> changes = new ArrayList<>();
    for (GitDiffs.Change file : files) {
      int[] count = counts.getOrDefault(file.path(), new int[2]);
      changes.add(new AgentEvent.Change(this.topLevel.resolve(file.path()).toString(), count[0], count[1],
        file.status() == 'A'));
    }
    return changes;
  }

  /**
   * The file's content when the snapshot was taken, with the checkout's line endings. The file must be in the snapshot.
   * Also works after {@link #close}.
   */
  public String original(Path file) throws GitException {
    String path = this.topLevel.relativize(file).toString().replace('\\', '/');
    return new String(Worktrees.gitBytes(this.topLevel, this.environment, "cat-file", "--filters",
      this.tree + ":" + path), StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    this.index.close();
  }

  /** Parses {@code git diff-tree --numstat -z --no-renames} output into added and removed lines per path. */
  static Map<String, int[]> parseNumstat(String output) {
    Map<String, int[]> counts = new HashMap<>();
    for (String record : output.split("\0")) {
      String[] parts = record.split("\t", 3);
      if (parts.length == 3) {
        counts.put(parts[2], new int[]{count(parts[0]), count(parts[1])});
      }
    }
    return counts;
  }

  private static int count(String text) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String writeTree(Path topLevel, Worktrees.MarkedIndex index) throws GitException {
    Worktrees.git(topLevel, index.environment(), "add", "--all");
    return Worktrees.git(topLevel, index.environment(), "write-tree").trim();
  }
}
