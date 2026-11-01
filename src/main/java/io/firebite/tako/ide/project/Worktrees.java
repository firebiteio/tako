package io.firebite.tako.ide.project;

import io.firebite.tako.agent.EditPreview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/** Git worktrees for runs of a bench: adding and removing them, and reading and applying what a run changed. */
public final class Worktrees {
  private static final long TIMEOUT_SECONDS = 120;
  private static final int SLUG_LENGTH = 24;
  private static final int REMOVE_ATTEMPTS = 5;
  private static final long REMOVE_RETRY_MILLIS = 1000;

  private Worktrees() {
  }

  /** The repository's top folder, which holds {@code directory}. */
  public static Path topLevel(Path directory, Map<String, String> environment) throws GitException {
    return Path.of(git(directory, environment, "rev-parse", "--show-toplevel").trim());
  }

  public static String head(Path repository, Map<String, String> environment) throws GitException {
    return git(repository, environment, "rev-parse", "HEAD").trim();
  }

  /** Whether the checkout has changes or untracked files that a worktree off HEAD would not have. */
  public static boolean isDirty(Path repository, Map<String, String> environment) throws GitException {
    return !git(repository, environment, "status", "--porcelain").isBlank();
  }

  /** Where a bench's worktrees go: a folder beside the repository, so the IDE does not index them. */
  public static Path benchFolder(Path repository, String slug) {
    Path parent = repository.getParent();
    String folder = repository.getFileName() + "-tako";
    return (parent != null ? parent.resolve(folder) : Path.of(folder)).resolve(slug);
  }

  /** {@code Fix the login bug!} → {@code fix-the-login-bug}, for folder and branch names. */
  public static String slug(String text) {
    StringBuilder slug = new StringBuilder();
    for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
      boolean isPlain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (isPlain) {
        slug.append(c);
      } else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
        slug.append('-');
      }
      if (slug.length() >= SLUG_LENGTH) {
        break;
      }
    }
    while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') {
      slug.setLength(slug.length() - 1);
    }
    return slug.isEmpty() ? "run" : slug.toString();
  }

  /**
   * Adds a worktree at {@code location} on a new {@code branch} starting at {@code commit}, with the files
   * {@code .worktreeinclude} lists.
   */
  public static void add(Path repository, Path location, String branch, String commit,
    Map<String, String> environment) throws GitException {
    git(repository, environment, "worktree", "add", "-b", branch, location.toString(), commit);
    WorktreeInclude.copy(location, environment);
  }

  /**
   * Removes the worktree, even with changes in it, and deletes its branch. Either may be gone already. Retries while
   * the stopped agent's processes may still hold files open, which blocks deleting them on Windows.
   */
  public static void remove(Path repository, Path location, String branch, Map<String, String> environment)
    throws GitException {
    for (int attempt = 1; Files.exists(location); attempt++) {
      try {
        git(repository, environment, "worktree", "remove", "--force", location.toString());
      } catch (GitException e) {
        if (attempt == REMOVE_ATTEMPTS) {
          throw e;
        }
        sleep(REMOVE_RETRY_MILLIS);
      }
    }
    try {
      git(repository, environment, "branch", "-D", branch);
    } catch (GitException ignored) {
    }
  }

  /** Forgets worktrees whose folder is gone, which {@link #remove} leaves when the folder was deleted by hand. */
  public static void prune(Path repository, Map<String, String> environment) {
    try {
      git(repository, environment, "worktree", "prune");
    } catch (GitException ignored) {
    }
  }

  /** Counts what the worktree changed against {@code commit}, including new files and commits made in it. */
  public static DiffStat diffStat(Path worktree, String commit, Map<String, String> environment)
    throws GitException {
    try (MarkedIndex index = MarkedIndex.of(worktree, environment)) {
      return parseNumstat(git(worktree, index.environment(), "diff", "--numstat", "--no-renames", commit));
    }
  }

  /** Each file the worktree changed against {@code commit}, named by its path in the repository. */
  public static List<EditPreview> changes(Path worktree, String commit, Map<String, String> environment)
    throws GitException {
    try (MarkedIndex index = MarkedIndex.of(worktree, environment)) {
      List<EditPreview> previews = new ArrayList<>();
      String names = git(worktree, index.environment(), "diff", "--name-status", "--no-renames", commit);
      for (String line : names.split("\\R")) {
        int tab = line.indexOf('\t');
        if (tab < 0) {
          continue;
        }
        char status = line.charAt(0);
        String path = line.substring(tab + 1);
        String before = status == 'A' ? "" : git(worktree, environment, "show", commit + ":" + path);
        String after = status == 'D' ? "" : readText(worktree.resolve(path));
        previews.add(new EditPreview(path, before, after));
      }
      return previews;
    }
  }

  /**
   * Applies what the worktree changed against {@code commit} to the {@code repository}'s checkout as uncommitted
   * changes. Falls back to a three-way merge, which stages the result, when the checkout moved on. The patch goes
   * through a file as bytes, so files in any encoding survive.
   */
  public static void applyTo(Path repository, Path worktree, String commit, Map<String, String> environment)
    throws GitException {
    Path patch = createTemp(".patch");
    try (MarkedIndex index = MarkedIndex.of(worktree, environment)) {
      run(worktree, index.environment(), patch, "diff", "--binary", "--no-renames", commit);
      if (Files.size(patch) == 0) {
        throw new GitException("The run changed nothing.");
      }
      try {
        git(repository, environment, "apply", "--whitespace=nowarn", patch.toString());
      } catch (GitException e) {
        git(repository, environment, "apply", "--3way", "--whitespace=nowarn", patch.toString());
      }
    } catch (IOException e) {
      throw new GitException(e.getMessage());
    } finally {
      deleteQuietly(patch);
    }
  }

  static DiffStat parseNumstat(String output) {
    int files = 0;
    int added = 0;
    int removed = 0;
    for (String line : output.split("\\R")) {
      String[] parts = line.split("\t", 3);
      if (parts.length < 3) {
        continue;
      }
      files++;
      added += parseCount(parts[0]);
      removed += parseCount(parts[1]);
    }
    return new DiffStat(files, added, removed);
  }

  /** Binary files count as {@code -}. */
  private static int parseCount(String count) {
    try {
      return Integer.parseInt(count);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Bytes that are not UTF-8 read as replacement characters instead of failing. */
  private static String readText(Path file) throws GitException {
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GitException(e.getMessage());
    }
  }

  static Path createTemp(String suffix) throws GitException {
    try {
      return Files.createTempFile("tako-git", suffix);
    } catch (IOException e) {
      throw new GitException(e.getMessage());
    }
  }

  static String git(Path directory, Map<String, String> environment, String... arguments)
    throws GitException {
    Path output = createTemp(".out");
    try {
      run(directory, environment, output, arguments);
      return readText(output);
    } finally {
      deleteQuietly(output);
    }
  }

  /** Runs git with its output going to {@code output}. */
  static void run(Path directory, Map<String, String> environment, Path output, String... arguments)
    throws GitException {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-c");
    command.add("core.quotePath=false");
    command.addAll(List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Path error = createTemp(".err");
    try {
      builder.redirectOutput(output.toFile());
      builder.redirectError(error.toFile());
      Process process = builder.start();
      process.getOutputStream().close();
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new GitException("git " + arguments[0] + " timed out");
      }
      if (process.exitValue() != 0) {
        String message = readText(error).trim();
        throw new GitException(message.isEmpty() ? "git " + arguments[0] + " failed" : message);
      }
    } catch (IOException e) {
      throw new GitException(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitException("Interrupted");
    } finally {
      deleteQuietly(error);
    }
  }

  private static void sleep(long millis) throws GitException {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitException("Interrupted");
    }
  }

  static void deleteQuietly(@Nullable Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  /**
   * A copy of the worktree's index with new files marked, so diffs against a commit see them. The agent may still work
   * in the worktree, so its own index stays untouched. Closing deletes the copy. A repository without an index yet,
   * before its first {@code git add}, starts from none, since git rejects an empty index file.
   *
   * @param environment the environment that makes git use the copy
   */
  record MarkedIndex(
    Path file,
    Map<String, String> environment
  ) implements AutoCloseable {
    static MarkedIndex of(Path worktree, Map<String, String> environment) throws GitException {
      Path index = worktree.resolve(git(worktree, environment, "rev-parse", "--git-path", "index").trim());
      Path copy = createTemp(".index");
      Map<String, String> marked = new HashMap<>(environment);
      marked.put("GIT_INDEX_FILE", copy.toString());
      MarkedIndex markedIndex = new MarkedIndex(copy, marked);
      try {
        if (Files.exists(index)) {
          Files.copy(index, copy, StandardCopyOption.REPLACE_EXISTING);
        } else {
          Files.delete(copy);
        }
        git(worktree, marked, "add", "--all", "--intent-to-add");
        return markedIndex;
      } catch (IOException | GitException e) {
        markedIndex.close();
        throw e instanceof GitException gitException ? gitException : new GitException(e.getMessage());
      }
    }

    @Override
    public void close() {
      deleteQuietly(this.file);
    }
  }

  public record DiffStat(
    int files,
    int added,
    int removed
  ) {
  }

  /** A git command failed; the message is git's own. */
  public static final class GitException extends Exception {
    public GitException(String message) {
      super(message);
    }
  }
}
