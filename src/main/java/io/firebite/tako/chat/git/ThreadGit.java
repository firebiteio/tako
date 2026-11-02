package io.firebite.tako.chat.git;

import io.firebite.tako.agent.AgentException;
import io.firebite.tako.ide.project.Forge;
import io.firebite.tako.ide.project.ForgeCli;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * A thread's git actions on its folder: what the menu offers, the texts an agent writes for them, and running them.
 * Only one action runs per repository at a time, whichever thread started it.
 */
public final class ThreadGit {
  /** The top folders of the repositories a git action runs in, each with what completes when it ended. */
  private static final Map<String, CompletableFuture<Void>> RUNNING = new ConcurrentHashMap<>();
  /** How long a logged-in {@code gh} or {@code glab} is trusted without asking again. */
  private static final long CLI_READY_MILLIS = 300_000;
  /**
   * How long a missing or logged-out CLI is trusted without asking again: short, so a login soon shows, and long
   * enough that turns ending one after another do not ask each time.
   */
  private static final long CLI_NOT_READY_MILLIS = 30_000;
  /** What the CLI answered, by {@link #cliKey}. */
  private static final Map<String, KnownState> CLI_STATES = new ConcurrentHashMap<>();
  /** The CLIs and hosts whose state is being asked for, by {@link #cliKey}, done once it answered. */
  private static final Map<String, CompletableFuture<Void>> CLI_ASKING = new ConcurrentHashMap<>();

  private final Supplier<Path> folder;
  private final Map<String, String> environment;
  private final Function<String, Busy> busy;
  private final Function<Forge, ForgeCli> clis;

  /**
   * @param folder the folder the thread works in, null when it has none
   * @param busy   who works in the repository with the given top folder now, null when nobody does
   * @param clis   the CLI that opens pull requests on each forge
   */
  public ThreadGit(Supplier<Path> folder, Map<String, String> environment,
    Function<String, Busy> busy, Function<Forge, ForgeCli> clis) {
    this.folder = folder;
    this.environment = environment;
    this.busy = busy;
    this.clis = clis;
  }

  /**
   * The checkout's state and the actions possible on it. Reads git locally and asks the forge nothing: until
   * {@link #checkForge} learned the CLI's state, creating a pull request waits on {@link Blocker#CLI_CHECKING}. Call
   * off the EDT.
   *
   * @param stored       the pull request the thread last found
   * @param storedBranch the branch it was found for, so another branch's pull request does not count
   */
  public Menu menu(@Nullable GitInfo.PullRequest stored, @Nullable String storedBranch) {
    Path directory = this.folder.get();
    GitCommands.Status status = directory == null
      ? GitCommands.Status.NONE
      : GitCommands.status(directory, this.environment);
    String topLevel = status.topLevel();
    Busy working = topLevel == null ? null : this.busy.apply(topLevel);
    GitCommands.HostedRepo repository = status.hosted();
    ForgeCli.State cli = repository == null ? null : knownState(repository);
    boolean running = topLevel != null && RUNNING.containsKey(topLevel);
    boolean isSameBranch = status.branch() != null && status.branch().equals(storedBranch);
    return new Menu(status, options(status, cli, running, working == null ? null : working.blocker(),
      isSameBranch ? stored : null), working == null ? null : working.name());
  }

  /**
   * Asks the forge's CLI whether it is installed and logged in, unless it answered lately, so the next {@link #menu}
   * knows. Callers asking for the same CLI and host meanwhile wait for that one answer. Asks the forge over the
   * network, so call off the EDT.
   */
  public void checkForge(Menu menu) {
    GitCommands.HostedRepo repository = menu.status().hosted();
    if (repository == null || knownState(repository) != null) {
      return;
    }
    String key = cliKey(repository);
    CompletableFuture<Void> asking = new CompletableFuture<>();
    CompletableFuture<Void> other = CLI_ASKING.putIfAbsent(key, asking);
    if (other != null) {
      other.exceptionally(error -> null).join();
      return;
    }
    try {
      ForgeCli.State state = this.clis.apply(repository.forge()).state(repository.host(), this.environment);
      CLI_STATES.put(key, new KnownState(state, System.currentTimeMillis()));
    } finally {
      CLI_ASKING.remove(key, asking);
      asking.complete(null);
    }
  }

  /** Has the agent write the action's texts from the changes, the repository's style and the conversation. */
  public Draft draft(Option option, GitCommands.Status status, String conversation, Asker asker)
    throws AgentException {
    Path directory = this.folder.get();
    if (directory == null) {
      throw new AgentException("No folder");
    }
    boolean pullRequest = option.action().createsPullRequest();
    String diff = "";
    if (option.commits()) {
      try {
        diff = GitCommands.pendingDiff(directory, this.environment, status.hasCommits(), GitDrafts.MAX_DIFF);
      } catch (GitCommands.CommandException e) {
        throw new AgentException(String.valueOf(e.getMessage()), e);
      }
    }
    List<String> recent = option.commits()
      ? GitCommands.recentSubjects(directory, this.environment, GitDrafts.RECENT_SUBJECTS)
      : List.of();
    String remote = status.remote();
    String branch = status.branch();
    List<String> branchSubjects = pullRequest && remote != null && branch != null
      ? GitCommands.branchSubjects(directory, this.environment, remote, branch, GitDrafts.BRANCH_SUBJECTS)
      : List.of();
    String prompt = GitDrafts.prompt(option.commits(), pullRequest, diff, recent, branchSubjects, conversation);
    return GitDrafts.parse(asker.ask(directory, prompt));
  }

  /**
   * Runs the action's steps: commit, push, pull request. Checks first that nothing changed since {@code status} was
   * read in a way that makes them wrong. Stops at the first failing step; what finished stays.
   *
   * @param status   the state the menu showed
   * @param progress told each step as it starts
   */
  public Result run(Option option, GitCommands.Status status, Draft draft, Consumer<Step> progress,
    BooleanSupplier cancelled) throws ActionException {
    String topLevel = status.topLevel();
    Path directory = this.folder.get();
    if (topLevel == null || directory == null) {
      throw new ActionException(Failure.NOT_REPOSITORY, "", Result.NONE);
    }
    CompletableFuture<Void> done = new CompletableFuture<>();
    if (RUNNING.putIfAbsent(topLevel, done) != null) {
      throw new ActionException(Failure.ALREADY_RUNNING, "", Result.NONE);
    }
    try {
      return this.runSteps(option, status, draft, directory, progress, cancelled);
    } finally {
      RUNNING.remove(topLevel, done);
      done.complete(null);
    }
  }

  /**
   * Waits until no git action runs in a repository holding {@code folder}, or held in it, so an agent does not change
   * files while they are committed. The caller must count as busy before, so an action that has not checked yet
   * stops instead, see {@link Failure#BUSY}.
   */
  public static void awaitActions(Path folder) {
    for (CompletableFuture<Void> action : actionsAround(folder)) {
      action.join();
    }
  }

  /** Whether a git action runs in a repository holding {@code folder}, or held in it. */
  public static boolean hasActions(Path folder) {
    return !actionsAround(folder).isEmpty();
  }

  private static List<CompletableFuture<Void>> actionsAround(Path folder) {
    Path normalized = folder.toAbsolutePath().normalize();
    List<CompletableFuture<Void>> actions = new ArrayList<>();
    for (Map.Entry<String, CompletableFuture<Void>> running : RUNNING.entrySet()) {
      Path topLevel = Path.of(running.getKey());
      if (normalized.startsWith(topLevel) || topLevel.startsWith(normalized)) {
        actions.add(running.getValue());
      }
    }
    return actions;
  }

  /** Reads the status again, since the menu may show one read a while ago, and acts on that. */
  private Result runSteps(Option option, GitCommands.Status shown, Draft draft, Path directory,
    Consumer<Step> progress, BooleanSupplier cancelled) throws ActionException {
    GitCommands.Status status = GitCommands.status(directory, this.environment);
    if (!status.isRepository()) {
      throw new ActionException(Failure.NOT_REPOSITORY, "", Result.NONE);
    }
    Busy working = this.busy.apply(Objects.requireNonNull(status.topLevel()));
    if (working != null) {
      throw new ActionException(Failure.BUSY, working.name(), Result.NONE);
    }
    String branch = status.branch();
    if (branch == null || !branch.equals(shown.branch())) {
      throw new ActionException(Failure.BRANCH_CHANGED, branch == null ? "" : branch, Result.NONE);
    }
    GitCommands.HostedRepo repository = status.hosted();
    ForgeCli cli = repository == null ? null : this.clis.apply(repository.forge());
    String base = null;
    if (option.action().createsPullRequest()) {
      if (repository == null) {
        throw new ActionException(Failure.PULL_REQUEST_FAILED, "", Result.NONE);
      }
      base = this.step(() -> cli.defaultBranch(directory, this.environment, repository, cancelled),
        Failure.PULL_REQUEST_FAILED, Result.NONE);
      if (base.equals(branch)) {
        throw new ActionException(Failure.ON_DEFAULT_BRANCH, base, Result.NONE);
      }
    }
    String commit = null;
    if (option.commits()) {
      progress.accept(Step.COMMIT);
      commit = this.step(() -> GitCommands.commit(directory, this.environment, draft.commitMessage(), cancelled),
        Failure.COMMIT_FAILED, Result.NONE);
    }
    String pushedTo = null;
    if (option.action().pushes()) {
      progress.accept(Step.PUSH);
      pushedTo = this.step(() -> GitCommands.push(directory, this.environment, status, cancelled),
        Failure.PUSH_FAILED, new Result(commit, null, null));
    }
    GitInfo.PullRequest pullRequest = null;
    if (repository != null && base != null) {
      progress.accept(Step.PULL_REQUEST);
      String head = branch;
      String into = base;
      pullRequest = this.step(() -> cli.create(directory, this.environment, repository, head, into,
        draft.title().strip(), draft.body().strip(), cancelled), Failure.PULL_REQUEST_FAILED,
        new Result(commit, pushedTo, null));
    }
    return new Result(commit, pushedTo, pullRequest);
  }

  /** Runs a step, turning its failure into {@code failure} unless it was cancelled or found nothing to do. */
  private <T> T step(Command<T> command, Failure failure, Result done) throws ActionException {
    try {
      return command.run();
    } catch (GitCommands.CommandException e) {
      Failure kind = switch (e.kind()) {
        case CANCELLED -> Failure.CANCELLED;
        case NOTHING_TO_COMMIT -> Failure.NOTHING_TO_COMMIT;
        case CONFLICTS -> Failure.CONFLICTS;
        case PUSH_REJECTED -> Failure.PUSH_REJECTED;
        case FAILED, TIMED_OUT -> failure;
      };
      throw new ActionException(kind, String.valueOf(e.getMessage()), done);
    }
  }

  /** What the forge's CLI answered for the host lately, null when it was not asked or too long ago. */
  private static @Nullable ForgeCli.State knownState(GitCommands.HostedRepo repository) {
    KnownState known = CLI_STATES.get(cliKey(repository));
    if (known == null) {
      return null;
    }
    long maxAge = known.state() == ForgeCli.State.READY ? CLI_READY_MILLIS : CLI_NOT_READY_MILLIS;
    return System.currentTimeMillis() - known.at() < maxAge ? known.state() : null;
  }

  private static String cliKey(GitCommands.HostedRepo repository) {
    return repository.forge().cli() + " " + repository.host();
  }

  /**
   * One entry per action, in menu order, each with what blocks it.
   *
   * @param cli     the forge CLI's state, null when it is not known yet or the push remote is on no known forge
   * @param running a git action runs in the repository
   * @param busy    why an agent blocks every action, if one does
   * @param stored  the pull request the thread knows for its branch
   */
  static List<Option> options(GitCommands.Status status, @Nullable ForgeCli.State cli, boolean running,
    @Nullable Blocker busy, @Nullable GitInfo.PullRequest stored) {
    List<Option> options = new ArrayList<>();
    for (GitAction action : GitAction.values()) {
      options.add(new Option(action, status.hasChanges(), blocker(action, status, cli, running, busy, stored)));
    }
    return options;
  }

  private static @Nullable Blocker blocker(GitAction action, GitCommands.Status status, @Nullable ForgeCli.State cli,
    boolean running, @Nullable Blocker busy, @Nullable GitInfo.PullRequest stored) {
    if (!status.isRepository()) {
      return Blocker.NOT_REPOSITORY;
    }
    if (running) {
      return Blocker.RUNNING;
    }
    if (busy != null) {
      return busy;
    }
    if (status.branch() == null) {
      return Blocker.DETACHED;
    }
    if (status.hasConflicts()) {
      return Blocker.CONFLICTS;
    }
    boolean commits = status.hasChanges();
    if (action == GitAction.COMMIT) {
      return commits ? null : Blocker.NOTHING_TO_COMMIT;
    }
    if (status.remote() == null) {
      return Blocker.NO_REMOTE;
    }
    boolean isPushed = status.tracksBranch() && status.ahead() == 0;
    if (!commits && (!status.hasCommits() || action == GitAction.COMMIT_AND_PUSH && isPushed)) {
      return Blocker.NOTHING_TO_PUSH;
    }
    if (action == GitAction.COMMIT_AND_PUSH) {
      return null;
    }
    if (status.hosted() == null) {
      return Blocker.NOT_HOSTED;
    }
    if (cli == null) {
      return Blocker.CLI_CHECKING;
    }
    if (cli != ForgeCli.State.READY) {
      return cli == ForgeCli.State.LOGGED_OUT ? Blocker.CLI_LOGGED_OUT : Blocker.CLI_MISSING;
    }
    if (stored != null && "open".equals(stored.state())) {
      return Blocker.PULL_REQUEST_OPEN;
    }
    return status.branch().equals(status.defaultBranch()) ? Blocker.DEFAULT_BRANCH : null;
  }

  /** One-shot agent call that writes the texts. */
  @FunctionalInterface
  public interface Asker {
    String ask(Path directory, String prompt) throws AgentException;
  }

  @FunctionalInterface
  private interface Command<T> {
    T run() throws GitCommands.CommandException;
  }

  /**
   * An agent working in the repository.
   *
   * @param blocker {@link Blocker#AGENT_BUSY} or {@link Blocker#OTHER_THREAD_BUSY}
   * @param name    the agent's name, or the other thread's title
   */
  public record Busy(
    Blocker blocker,
    String name
  ) {
  }

  /**
   * @param busyName who blocks the actions by working, see {@link Busy#name}
   */
  public record Menu(
    GitCommands.Status status,
    List<Option> options,
    @Nullable String busyName
  ) {
    /** Whether an entry waits for {@link #checkForge}. */
    public boolean waitsForForge() {
      for (Option option : this.options) {
        if (option.blocker() == Blocker.CLI_CHECKING) {
          return true;
        }
      }
      return false;
    }
  }

  private record KnownState(
    ForgeCli.State state,
    long at
  ) {
  }

  /**
   * @param commits whether the action commits first, which it does when there are changes
   * @param blocker why it is not possible now, null when it is
   */
  public record Option(
    GitAction action,
    boolean commits,
    @Nullable Blocker blocker
  ) {
  }

  /** What an action did. Each part is null when it did not get that far or had no such step. */
  public record Result(
    @Nullable String commit,
    @Nullable String pushedTo,
    @Nullable GitInfo.PullRequest pullRequest
  ) {
    public static final Result NONE = new Result(null, null, null);
  }

  public enum Step {
    COMMIT,
    PUSH,
    PULL_REQUEST
  }

  public enum Failure {
    NOT_REPOSITORY,
    ALREADY_RUNNING,
    /** An agent started working in the repository after the menu was read. The detail names it. */
    BUSY,
    /** The checkout is on another branch or detached now. The detail is the new branch, if any. */
    BRANCH_CHANGED,
    /** The branch is the repository's default branch. The detail is its name. */
    ON_DEFAULT_BRANCH,
    NOTHING_TO_COMMIT,
    /** Unmerged paths appeared after the menu was read. */
    CONFLICTS,
    COMMIT_FAILED,
    PUSH_REJECTED,
    PUSH_FAILED,
    PULL_REQUEST_FAILED,
    CANCELLED
  }

  /**
   * An action stopped.
   *
   * @see #detail
   */
  public static final class ActionException extends Exception {
    private final Failure failure;
    private final Result done;

    public ActionException(Failure failure, String detail, Result done) {
      super(detail);
      this.failure = failure;
      this.done = done;
    }

    public Failure failure() {
      return this.failure;
    }

    /** The output of the command that failed, or what the failure names. May be empty. */
    public String detail() {
      return String.valueOf(this.getMessage());
    }

    /** What finished before it stopped. */
    public Result done() {
      return this.done;
    }
  }
}
