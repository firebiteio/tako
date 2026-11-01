package io.firebite.tako.ide.project;

import io.firebite.tako.agent.Processes;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a checkout's state and commits and pushes it with plain {@code git} processes, for a thread's git actions. A
 * long command stops when its caller cancels.
 */
public final class GitCommands {
  public static final BooleanSupplier NEVER_CANCELLED = () -> false;
  static final long GH_TIMEOUT_SECONDS = 60;
  private static final long READ_TIMEOUT_SECONDS = 30;
  private static final long COMMIT_TIMEOUT_SECONDS = 600;
  private static final long PUSH_TIMEOUT_SECONDS = 300;
  private static final long POLL_MILLIS = 100;
  private static final int MAX_OUTPUT_BYTES = 256 * 1024;
  private static final int MAX_DETAIL_CHARS = 4000;
  private static final long STOP_WAIT_SECONDS = 10;

  private GitCommands() {
  }

  /** The checkout's branch, changes and remote. Not a repository when the folder is gone or not in git. */
  public static Status status(Path directory, Map<String, String> environment) {
    String topLevel = topLevel(directory, environment);
    if (topLevel == null) {
      return Status.NONE;
    }
    try {
      String branch = branch(directory, environment);
      boolean hasCommits = read(directory, environment, "rev-parse", "-q", "--verify", "HEAD").exitCode() == 0;
      boolean isMerging = read(directory, environment, "rev-parse", "-q", "--verify", "MERGE_HEAD").exitCode() == 0;
      int changedFiles = 0;
      for (String line : success(read(directory, environment, "status", "--porcelain", "--untracked-files=all"))
        .split("\\R")) {
        if (line.length() >= 2) {
          changedFiles++;
        }
      }
      boolean hasConflicts = hasConflicts(directory, environment);
      List<String> remotes = lines(read(directory, environment, "remote").stdout());
      String remote = branch == null ? null : pushRemote(directory, environment, branch, remotes);
      boolean tracksBranch = false;
      int ahead = 0;
      if (remote != null) {
        String merge = config(directory, environment, "branch." + branch + ".merge");
        boolean tracks = ("refs/heads/" + branch).equals(merge)
          && remote.equals(config(directory, environment, "branch." + branch + ".remote"));
        Output counts = tracks
          ? read(directory, environment, "rev-list", "--left-right", "--count", "@{u}...HEAD")
          : null;
        String[] behindAhead = counts == null || counts.exitCode() != 0
          ? new String[0]
          : counts.stdout().strip()
            .split("\\s+");
        if (behindAhead.length == 2) {
          tracksBranch = true;
          ahead = Integer.parseInt(behindAhead[1]);
        }
      }
      String defaultBranch = remote == null ? null : defaultBranch(directory, environment, remote);
      GitHubRepo gitHub = "origin".equals(remote)
        ? parseGitHub(read(directory, environment, "remote", "get-url", "origin").stdout())
        : null;
      return new Status(true, topLevel, branch, changedFiles, hasConflicts, isMerging, hasCommits, remote,
        tracksBranch, ahead, defaultBranch, gitHub);
    } catch (CommandException | NumberFormatException e) {
      return Status.NONE;
    }
  }

  /** The top folder of the repository the folder is in, normalized, or null when it is gone or not in git. */
  public static @Nullable String topLevel(Path directory, Map<String, String> environment) {
    if (!Files.isDirectory(directory)) {
      return null;
    }
    try {
      Output top = read(directory, environment, "rev-parse", "--show-toplevel");
      return top.exitCode() != 0 || top.stdout().isBlank()
        ? null
        : Path.of(top.stdout().strip()).toAbsolutePath().normalize().toString();
    } catch (CommandException e) {
      return null;
    }
  }

  /** The checked-out branch, null when HEAD is detached or the folder is not in git. */
  public static @Nullable String branch(Path directory, Map<String, String> environment) {
    if (!Files.isDirectory(directory)) {
      return null;
    }
    try {
      Output branch = read(directory, environment, "symbolic-ref", "-q", "--short", "HEAD");
      return branch.exitCode() == 0 && !branch.stdout().isBlank() ? branch.stdout().strip() : null;
    } catch (CommandException e) {
      return null;
    }
  }

  /**
   * What committing everything would change, new files included: a summary per file, then the diff, cut to
   * {@code maxChars}.
   */
  public static String pendingDiff(Path directory, Map<String, String> environment, boolean hasCommits, int maxChars)
    throws CommandException {
    String base = hasCommits
      ? "HEAD"
      : success(read(directory, environment, "hash-object", "-t", "tree", "--stdin"))
        .strip();
    try (Worktrees.MarkedIndex index = Worktrees.MarkedIndex.of(directory, environment)) {
      String stat = success(read(directory, index.environment(), "diff", "--stat", base));
      String diff = success(read(directory, index.environment(), "diff", base));
      return cut(stat.strip() + "\n\n" + diff, maxChars);
    } catch (Worktrees.GitException e) {
      throw new CommandException(CommandException.Kind.FAILED, e.getMessage());
    }
  }

  /** The newest commits' subjects, newest first. Empty without commits. */
  public static List<String> recentSubjects(Path directory, Map<String, String> environment, int max) {
    return subjects(directory, environment, List.of("log", "--format=%s", "-n", String.valueOf(max)));
  }

  /**
   * The subjects of the branch's commits that no other branch of {@code remote} has, newest first, including those
   * already pushed to the branch itself. Empty without commits.
   */
  public static List<String> branchSubjects(Path directory, Map<String, String> environment, String remote,
    String branch, int max) {
    return subjects(directory, environment, List.of("log", "--format=%s", "-n", String.valueOf(max), "HEAD", "--not",
      "--exclude=" + remote + "/" + branch, "--remotes=" + remote));
  }

  /**
   * Stages every change and commits it with {@code message}, running the repository's hooks. When no commit is made,
   * e.g. a hook fails or it is cancelled, the index is put back as it was, so what the person had staged stays staged
   * and nothing else is. A cancelled commit's index lock is removed.
   *
   * @return the new commit's short hash
   */
  public static String commit(Path directory, Map<String, String> environment, String message,
    BooleanSupplier cancelled) throws CommandException {
    Path index = gitPath(directory, environment, "index");
    Path lock = gitPath(directory, environment, "index.lock");
    boolean wasLocked = Files.exists(lock);
    String head = read(directory, environment, "rev-parse", "-q", "--verify", "HEAD").stdout().strip();
    Path saved = createTemp(".index");
    try {
      boolean hadIndex = Files.exists(index);
      if (hadIndex) {
        Files.copy(index, saved, StandardCopyOption.REPLACE_EXISTING);
      }
      try {
        return commitAll(directory, environment, message, cancelled);
      } catch (CommandException e) {
        boolean isKilled = e.kind() == CommandException.Kind.CANCELLED || e.kind() == CommandException.Kind.TIMED_OUT;
        if (isKilled && !wasLocked) {
          deleteQuietly(lock);
        }
        boolean isUncommitted = head.equals(read(directory, environment, "rev-parse", "-q", "--verify", "HEAD")
          .stdout().strip());
        if (isUncommitted && !Files.exists(lock)) {
          if (hadIndex) {
            Files.copy(saved, index, StandardCopyOption.REPLACE_EXISTING);
          } else {
            deleteQuietly(index);
          }
        }
        throw e;
      }
    } catch (IOException e) {
      throw new CommandException(CommandException.Kind.FAILED, String.valueOf(e.getMessage()));
    } finally {
      deleteQuietly(saved);
    }
  }

  private static String commitAll(Path directory, Map<String, String> environment, String message,
    BooleanSupplier cancelled) throws CommandException {
    if (hasConflicts(directory, environment)) {
      throw new CommandException(CommandException.Kind.CONFLICTS, "");
    }
    success(git(directory, environment, COMMIT_TIMEOUT_SECONDS, cancelled, List.of("add", "-A")));
    boolean isMerging = read(directory, environment, "rev-parse", "-q", "--verify", "MERGE_HEAD").exitCode() == 0;
    Output staged = read(directory, environment, "diff", "--cached", "--quiet");
    if (staged.exitCode() == 0 && !isMerging) {
      throw new CommandException(CommandException.Kind.NOTHING_TO_COMMIT, "");
    }
    if (staged.exitCode() > 1) {
      throw new CommandException(CommandException.Kind.FAILED, detail(staged));
    }
    Path file = createTemp(".msg");
    try {
      Files.writeString(file, message, StandardCharsets.UTF_8);
      success(git(directory, environment, COMMIT_TIMEOUT_SECONDS, cancelled, List.of("commit", "-F", file.toString())));
    } catch (IOException e) {
      throw new CommandException(CommandException.Kind.FAILED, String.valueOf(e.getMessage()));
    } finally {
      deleteQuietly(file);
    }
    return success(read(directory, environment, "rev-parse", "--short", "HEAD")).strip();
  }

  /**
   * Pushes HEAD to the branch of the same name on the status's remote. Names the target, so neither
   * {@code push.default} nor an upstream of another name sends it elsewhere. Tracks the pushed branch when the branch
   * tracked nothing or a branch of another name on the same remote; an upstream on another remote, as when pulling
   * from one remote and pushing to a fork, stays.
   *
   * @return where it pushed, e.g. {@code origin/feature}
   */
  public static String push(Path directory, Map<String, String> environment, Status status, BooleanSupplier cancelled)
    throws CommandException {
    if (status.remote() == null || status.branch() == null) {
      throw new CommandException(CommandException.Kind.FAILED, "No remote or branch");
    }
    List<String> arguments = new ArrayList<>(List.of("push"));
    String upstreamRemote = config(directory, environment, "branch." + status.branch() + ".remote");
    if (!status.tracksBranch() && (upstreamRemote == null || upstreamRemote.equals(status.remote()))) {
      arguments.add("-u");
    }
    arguments.add(status.remote());
    arguments.add("HEAD:refs/heads/" + status.branch());
    Output output = git(directory, environment, PUSH_TIMEOUT_SECONDS, cancelled, arguments);
    if (output.exitCode() != 0) {
      String detail = detail(output);
      throw new CommandException(isRejected(detail)
        ? CommandException.Kind.PUSH_REJECTED
        : CommandException.Kind.FAILED, detail);
    }
    return status.remote() + "/" + status.branch();
  }

  static boolean isRejected(String output) {
    return output.contains("[rejected]") || output.contains("non-fast-forward") || output.contains("fetch first");
  }

  /**
   * The GitHub repository a remote URL points to: {@code git@host:o/r.git}, {@code ssh://git@host:22/o/r} or
   * {@code https://host/o/r.git}. Null for other hosts and for paths.
   */
  public static @Nullable GitHubRepo parseGitHub(@Nullable String url) {
    if (url == null) {
      return null;
    }
    String text = url.strip();
    String host;
    String path;
    int scheme = text.indexOf("://");
    if (scheme >= 0) {
      String rest = text.substring(scheme + 3);
      int slash = rest.indexOf('/');
      if (slash < 0) {
        return null;
      }
      host = rest.substring(0, slash);
      host = host.substring(host.lastIndexOf('@') + 1);
      int port = host.indexOf(':');
      host = port < 0 ? host : host.substring(0, port);
      path = rest.substring(slash + 1);
    } else {
      int colon = text.indexOf(':');
      if (colon < 0 || text.substring(0, colon).contains("/")) {
        return null;
      }
      host = text.substring(text.lastIndexOf('@', colon) + 1, colon);
      path = text.substring(colon + 1);
    }
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.endsWith(".git")) {
      path = path.substring(0, path.length() - ".git".length());
    }
    String[] parts = path.split("/");
    host = host.toLowerCase(Locale.ROOT);
    if (!host.contains("github") || parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      return null;
    }
    return new GitHubRepo(host, parts[0], parts[1]);
  }

  /** The output's end, at most {@link #MAX_DETAIL_CHARS}: git's error output, then what it printed. */
  static String detail(Output output) {
    String text = (output.stderr().strip() + "\n" + output.stdout().strip()).strip();
    return text.length() <= MAX_DETAIL_CHARS ? text : "…" + text.substring(text.length() - MAX_DETAIL_CHARS);
  }

  /**
   * The remote a plain {@code git push} would use: the branch's push remote, the default push remote, the branch's
   * remote, {@code origin} or the first one. Only configured remotes count.
   */
  private static @Nullable String pushRemote(Path directory, Map<String, String> environment, String branch,
    List<String> remotes) throws CommandException {
    for (String key : List.of("branch." + branch + ".pushRemote", "remote.pushDefault",
      "branch." + branch + ".remote")) {
      String remote = config(directory, environment, key);
      if (remote != null && remotes.contains(remote)) {
        return remote;
      }
    }
    if (remotes.contains("origin")) {
      return "origin";
    }
    return remotes.isEmpty() ? null : remotes.getFirst();
  }

  /** The remote's default branch as the last fetch or clone recorded it, else null. */
  private static @Nullable String defaultBranch(Path directory, Map<String, String> environment, String remote)
    throws CommandException {
    Output head = read(directory, environment, "symbolic-ref", "-q", "--short", "refs/remotes/" + remote + "/HEAD");
    String name = head.stdout().strip();
    return head.exitCode() == 0 && name.startsWith(remote + "/") ? name.substring(remote.length() + 1) : null;
  }

  /** Whether the index has unmerged paths, which {@code add -A} would mark resolved with their conflict markers. */
  private static boolean hasConflicts(Path directory, Map<String, String> environment) throws CommandException {
    return !success(read(directory, environment, "ls-files", "--unmerged")).isBlank();
  }

  /** A file in the repository's git folder, e.g. {@code index}, which may not exist. */
  private static Path gitPath(Path directory, Map<String, String> environment, String name) throws CommandException {
    return directory.resolve(success(read(directory, environment, "rev-parse", "--git-path", name)).strip());
  }

  private static @Nullable String config(Path directory, Map<String, String> environment, String key)
    throws CommandException {
    Output value = read(directory, environment, "config", "--get", key);
    return value.exitCode() == 0 && !value.stdout().isBlank() ? value.stdout().strip() : null;
  }

  private static List<String> subjects(Path directory, Map<String, String> environment, List<String> arguments) {
    try {
      Output log = git(directory, environment, READ_TIMEOUT_SECONDS, NEVER_CANCELLED, arguments);
      return log.exitCode() == 0 ? lines(log.stdout()) : List.of();
    } catch (CommandException e) {
      return List.of();
    }
  }

  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\\R")) {
      if (!line.isBlank()) {
        lines.add(line.strip());
      }
    }
    return lines;
  }

  private static String cut(String text, int maxChars) {
    return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n[cut]";
  }

  private static String success(Output output) throws CommandException {
    if (output.exitCode() != 0) {
      throw new CommandException(CommandException.Kind.FAILED, detail(output));
    }
    return output.stdout();
  }

  /** A quick git command that reads, without taking locks another git process may need. */
  private static Output read(Path directory, Map<String, String> environment, String... arguments)
    throws CommandException {
    List<String> command = new ArrayList<>(List.of("--no-optional-locks"));
    command.addAll(List.of(arguments));
    return git(directory, environment, READ_TIMEOUT_SECONDS, NEVER_CANCELLED, command);
  }

  private static Output git(Path directory, Map<String, String> environment, long timeoutSeconds,
    BooleanSupplier cancelled, List<String> arguments) throws CommandException {
    List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false"));
    command.addAll(arguments);
    return exec(command, directory, environment, timeoutSeconds, cancelled);
  }

  /**
   * Runs the command with exactly {@code environment} and no terminal prompts, and waits for it.
   *
   * @throws CommandException when it cannot start, is cancelled or outlasts the timeout; its process tree is stopped
   */
  static Output exec(List<String> command, Path directory, Map<String, String> environment, long timeoutSeconds,
    BooleanSupplier cancelled) throws CommandException {
    if (!Files.isDirectory(directory)) {
      throw new CommandException(CommandException.Kind.FAILED, "Folder not found: " + directory);
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("GH_PROMPT_DISABLED", "1");
    Path output = createTemp(".out");
    Path error = createTemp(".err");
    try {
      builder.redirectOutput(output.toFile());
      builder.redirectError(error.toFile());
      Process process = builder.start();
      process.getOutputStream().close();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
        if (cancelled.getAsBoolean()) {
          stop(process);
          throw new CommandException(CommandException.Kind.CANCELLED, "");
        }
        if (System.nanoTime() > deadline) {
          stop(process);
          throw new CommandException(CommandException.Kind.TIMED_OUT,
            command.getFirst() + " timed out after " + timeoutSeconds + " s");
        }
      }
      return new Output(process.exitValue(), readStart(output), readEnd(error));
    } catch (IOException e) {
      throw new CommandException(CommandException.Kind.FAILED, String.valueOf(e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CommandException(CommandException.Kind.CANCELLED, "");
    } finally {
      deleteQuietly(output);
      deleteQuietly(error);
    }
  }

  /**
   * Stops the process and the ones it started, such as hooks, and waits until they exited, so nothing writes to the
   * repository afterwards.
   */
  private static void stop(Process process) throws InterruptedException {
    List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
    tree.add(process.toHandle());
    Processes.destroyTree(process);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_WAIT_SECONDS);
    for (ProcessHandle handle : tree) {
      long left = deadline - System.nanoTime();
      try {
        handle.onExit().get(Math.max(0, left), TimeUnit.NANOSECONDS);
      } catch (ExecutionException | TimeoutException e) {
        handle.destroyForcibly();
      }
    }
  }

  private static String readStart(Path file) throws IOException {
    try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
      byte[] bytes = new byte[(int) Math.min(reader.length(), MAX_OUTPUT_BYTES)];
      reader.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static String readEnd(Path file) throws IOException {
    try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
      long length = reader.length();
      byte[] bytes = new byte[(int) Math.min(length, MAX_OUTPUT_BYTES)];
      reader.seek(length - bytes.length);
      reader.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static Path createTemp(String suffix) throws CommandException {
    try {
      return Files.createTempFile("tako-git", suffix);
    } catch (IOException e) {
      throw new CommandException(CommandException.Kind.FAILED, String.valueOf(e.getMessage()));
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  /**
   * A checkout's state for its git actions.
   *
   * @param topLevel      the repository's top folder, normalized
   * @param branch        null when HEAD is detached
   * @param changedFiles  changed and untracked files
   * @param isMerging     a merge waits to be committed
   * @param remote        the remote a push goes to, null without one
   * @param tracksBranch  the branch tracks the branch of the same name on {@code remote}, which exists
   * @param ahead         commits not on the tracked branch, 0 when it tracks none
   * @param defaultBranch the remote's default branch as the last fetch recorded it, null when unknown
   * @param gitHub        the GitHub repository of {@code origin} when the push goes there
   */
  public record Status(
    boolean isRepository,
    @Nullable String topLevel,
    @Nullable String branch,
    int changedFiles,
    boolean hasConflicts,
    boolean isMerging,
    boolean hasCommits,
    @Nullable String remote,
    boolean tracksBranch,
    int ahead,
    @Nullable String defaultBranch,
    @Nullable GitHubRepo gitHub
  ) {
    public static final Status NONE = new Status(false, null, null, 0, false, false, false, null, false, 0, null, null);

    /** Whether a commit has something to record. */
    public boolean hasChanges() {
      return this.changedFiles > 0 || this.isMerging;
    }
  }

  public record GitHubRepo(
    String host,
    String owner,
    String name
  ) {
    /** {@code repos/owner/name}, the repository's REST path. */
    public String apiPath() {
      return "repos/" + this.owner + "/" + this.name;
    }
  }

  record Output(
    int exitCode,
    String stdout,
    String stderr
  ) {
  }

  /** A git or gh command failed, was cancelled or took too long. The message is the command's own output. */
  public static final class CommandException extends Exception {
    private final Kind kind;

    public CommandException(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return this.kind;
    }

    public enum Kind {
      FAILED,
      NOTHING_TO_COMMIT,
      CONFLICTS,
      PUSH_REJECTED,
      TIMED_OUT,
      CANCELLED
    }
  }
}
