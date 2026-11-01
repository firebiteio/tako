package io.firebite.tako.ide.project;

import io.firebite.tako.agent.PromptText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshots of a thread's folder as it was when a prompt was sent, kept as hidden git commits under
 * {@code refs/tako/checkpoints/<thread>/<prompts>}, so a rewind can put the files back. Tracked and untracked files
 * count, ignored ones do not. The repository's own index is never written.
 */
public final class Checkpoints {
  private static final String PREFIX = "refs/tako/checkpoints/";
  private static final String BACKUP_PREFIX = "refs/tako/backup/";
  private static final String HASH_LINE = "Prompt: ";

  private Checkpoints() {
  }

  /**
   * Snapshots the folder for the prompt sent after the first {@code prompts} ones, replacing an older snapshot of that
   * number.
   */
  public static void capture(Path folder, String threadId, int prompts, String prompt, Map<String, String> environment)
    throws Worktrees.GitException {
    String commit = snapshot(folder, "Tako checkpoint\n\n" + HASH_LINE + PromptText.hash(prompt), environment);
    Worktrees.git(folder, environment, "update-ref", ref(threadId, prompts), commit);
  }

  /** The snapshot taken for that prompt, or null when there is none, it belongs to another prompt or git fails. */
  public static @Nullable String find(Path folder, String threadId, int prompts, String prompt,
    Map<String, String> environment) {
    try {
      String commit = Worktrees.git(folder, environment, "rev-parse", "-q", "--verify",
        ref(threadId, prompts) + "^{commit}").trim();
      String expected = HASH_LINE + PromptText.hash(prompt);
      for (String line : Worktrees.git(folder, environment, "cat-file", "commit", commit).split("\\R")) {
        if (line.equals(expected)) {
          return commit;
        }
      }
      return null;
    } catch (Worktrees.GitException e) {
      return null;
    }
  }

  /**
   * Puts the folder's files back as the snapshot has them: changed and deleted files are written again, files added
   * since are deleted with the folders that become empty. The state before is kept under
   * {@code refs/tako/backup/<thread>}.
   *
   * @return the files written or deleted
   */
  public static List<Path> restore(Path folder, String threadId, String commit, Map<String, String> environment)
    throws Worktrees.GitException {
    String backup = snapshot(folder, "Tako backup before rewind", environment);
    Worktrees.git(folder, environment, "update-ref", BACKUP_PREFIX + threadId, backup);
    String names = Worktrees.git(folder, environment, "diff", "--name-status", "-z", "--no-renames", "--relative",
      "--ignore-submodules=all", commit, backup);
    List<String> written = new ArrayList<>();
    List<String> added = new ArrayList<>();
    String[] fields = names.split("\0");
    for (int i = 0; i + 1 < fields.length; i += 2) {
      (fields[i].startsWith("A") ? added : written).add(fields[i + 1]);
    }
    List<Path> changed = new ArrayList<>();
    try {
      for (String path : added) {
        Path file = folder.resolve(path);
        Files.deleteIfExists(file);
        changed.add(file);
        deleteEmptyParents(folder, file);
      }
      if (!written.isEmpty()) {
        Path pathspec = Files.createTempFile("tako-pathspec", ".txt");
        try {
          Files.writeString(pathspec, String.join("\0", written), StandardCharsets.UTF_8);
          Map<String, String> literal = new HashMap<>(environment);
          literal.put("GIT_LITERAL_PATHSPECS", "1");
          Worktrees.git(folder, literal, "restore", "--source=" + commit, "--worktree",
            "--pathspec-from-file=" + pathspec, "--pathspec-file-nul");
        } finally {
          Files.deleteIfExists(pathspec);
        }
        for (String path : written) {
          changed.add(folder.resolve(path));
        }
      }
    } catch (IOException e) {
      throw new Worktrees.GitException(e.getMessage());
    }
    return changed;
  }

  /**
   * Deletes the thread's snapshots from number {@code fromPrompts} on, and with 0 also its backup. Never fails: a
   * folder that is gone or not a repository keeps nothing to delete.
   */
  public static void forget(Path folder, String threadId, int fromPrompts, Map<String, String> environment) {
    try {
      String refs = Worktrees.git(folder, environment, "for-each-ref", "--format=%(refname)",
        PREFIX + threadId + "/");
      for (String ref : refs.split("\\R")) {
        if (!ref.isBlank() && number(ref) >= fromPrompts) {
          Worktrees.git(folder, environment, "update-ref", "-d", ref);
        }
      }
      if (fromPrompts == 0) {
        Worktrees.git(folder, environment, "update-ref", "-d", BACKUP_PREFIX + threadId);
      }
    } catch (Worktrees.GitException ignored) {
    }
  }

  static String ref(String threadId, int prompts) {
    return PREFIX + threadId + "/" + prompts;
  }

  private static int number(String ref) {
    try {
      return Integer.parseInt(ref.substring(ref.lastIndexOf('/') + 1));
    } catch (NumberFormatException e) {
      return Integer.MAX_VALUE;
    }
  }

  /** Commits the folder's files through a copy of the index, so the repository's own index stays as it is. */
  private static String snapshot(Path folder, String message, Map<String, String> environment)
    throws Worktrees.GitException {
    Path index = folder.resolve(Worktrees.git(folder, environment, "rev-parse", "--git-path", "index").trim());
    Path copy;
    try {
      copy = Files.createTempFile("tako-checkpoint", ".index");
      if (Files.isRegularFile(index)) {
        Files.copy(index, copy, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.delete(copy);
      }
    } catch (IOException e) {
      throw new Worktrees.GitException(e.getMessage());
    }
    try {
      Map<String, String> snapshot = new HashMap<>(environment);
      snapshot.put("GIT_INDEX_FILE", copy.toString());
      snapshot.put("GIT_AUTHOR_NAME", "Tako");
      snapshot.put("GIT_AUTHOR_EMAIL", "tako@localhost");
      snapshot.put("GIT_COMMITTER_NAME", "Tako");
      snapshot.put("GIT_COMMITTER_EMAIL", "tako@localhost");
      Worktrees.git(folder, snapshot, "-c", "core.fsmonitor=false", "-c", "core.splitIndex=false", "add", "--all",
        "--", ".");
      String tree = Worktrees.git(folder, snapshot, "write-tree").trim();
      return Worktrees.git(folder, snapshot, "-c", "commit.gpgSign=false", "commit-tree", tree, "-m", message).trim();
    } finally {
      try {
        Files.deleteIfExists(copy);
      } catch (IOException ignored) {
      }
    }
  }

  private static void deleteEmptyParents(Path folder, Path file) throws IOException {
    Path root = folder.toAbsolutePath().normalize();
    Path parent = file.toAbsolutePath().normalize().getParent();
    while (parent != null && parent.startsWith(root) && !parent.equals(root) && Files.isDirectory(parent)) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
        if (entries.iterator().hasNext()) {
          return;
        }
      }
      Files.delete(parent);
      parent = parent.getParent();
    }
  }
}
