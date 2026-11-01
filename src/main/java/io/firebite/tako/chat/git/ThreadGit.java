package io.firebite.tako.chat.git;

import io.firebite.tako.agent.AgentException;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitHubCli;
import io.firebite.tako.ide.project.GitInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
  /** How long a logged-in {@code gh} is trusted without asking again. */
  private static final long GH_READY_MILLIS = 300_000;
  private static final Map<String, Long> GH_READY_AT = new ConcurrentHashMap<>();

  private final Supplier<Path> folder;
  private final Map<String, String> environment;
  private final Function<String, Busy> busy;
  private final GitHubCli gitHub;

  /**
   * @param folder the folder the thread works in, null when it has none
   * @param busy   who works in the repository with the given top folder now, null when nobody does
   */
  public ThreadGit(Supplier<Path> folder, Map<String, String> environment,
    Function<String, Busy> busy, GitHubCli gitHub) {
    this.folder = folder;
    this.environment = environment;
    this.busy = busy;
    this.gitHub = gitHub;
  }

  /**
   * The checkout's state and the actions possible on it. Runs git and may ask GitHub, so call off the EDT.
   *
   * @param stored the pull request the thread knows for its branch
   */
  public Menu menu(@Nullable GitInfo.PullRequest stored) {
    Path directory = this.folder.get();
    GitCommands.Status status = directory == null
      ? GitCommands.Status.NONE
      : GitCommands.status(directory, this.environment);
    String topLevel = status.topLevel();
    Busy working = topLevel == null ? null : this.busy.apply(topLevel);
    GitCommands.GitHubRepo repository = status.gitHub();
    GitHubCli.State gh = repository == null ? null : this.ghState(repository.host());
    boolean running = topLevel != null && RUNNING.contains(topLevel);
    return new Menu(status, options(status, gh, running, working == null ? null : working.blocker(), stored),
      working == null ? null : working.name());
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
    if (!RUNNING.add(topLevel)) {
      throw new ActionException(Failure.ALREADY_RUNNING, "", Result.NONE);
    }
    try {
      return this.runSteps(option, status, draft, directory, progress, cancelled);
    } finally {
      RUNNING.remove(topLevel);
    }
  }

  private Result runSteps(Option option, GitCommands.Status status, Draft draft, Path directory,
    Consumer<Step> progress, BooleanSupplier cancelled) throws ActionException {
    String branch = GitCommands.branch(directory, this.environment);
    if (branch == null && GitCommands.topLevel(directory, this.environment) == null) {
      throw new ActionException(Failure.NOT_REPOSITORY, "", Result.NONE);
    }
    Busy working = this.busy.apply(Objects.requireNonNull(status.topLevel()));
    if (working != null) {
      throw new ActionException(Failure.BUSY, working.name(), Result.NONE);
    }
    if (branch == null || !branch.equals(status.branch())) {
      throw new ActionException(Failure.BRANCH_CHANGED, branch == null ? "" : branch, Result.NONE);
    }
    GitCommands.GitHubRepo repository = status.gitHub();
    String base = null;
    if (option.action().createsPullRequest()) {
      if (repository == null) {
        throw new ActionException(Failure.PULL_REQUEST_FAILED, "", Result.NONE);
      }
      base = this.step(() -> this.gitHub.defaultBranch(directory, this.environment, repository, cancelled),
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
      pullRequest = this.step(() -> this.gitHub.create(directory, this.environment, repository, head, into,
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
        case PUSH_REJECTED -> Failure.PUSH_REJECTED;
        case FAILED, TIMED_OUT -> failure;
      };
      throw new ActionException(kind, String.valueOf(e.getMessage()), done);
    }
  }

  private GitHubCli.State ghState(String host) {
    Long readyAt = GH_READY_AT.get(host);
    if (readyAt != null && System.currentTimeMillis() - readyAt < GH_READY_MILLIS) {
      return GitHubCli.State.READY;
    }
    GitHubCli.State state = this.gitHub.state(host, this.environment);
    if (state == GitHubCli.State.READY) {
      GH_READY_AT.put(host, System.currentTimeMillis());
    } else {
      GH_READY_AT.remove(host);
    }
    return state;
  }

  /**
   * One entry per action, in menu order, each with what blocks it.
   *
   * @param gh      the GitHub CLI's state, null when the push remote is not on GitHub
   * @param running a git action runs in the repository
   * @param busy    why an agent blocks every action, if one does
   * @param stored  the pull request the thread knows for its branch
   */
  static List<Option> options(GitCommands.Status status, @Nullable GitHubCli.State gh, boolean running,
    @Nullable Blocker busy, @Nullable GitInfo.PullRequest stored) {
    List<Option> options = new ArrayList<>();
    for (GitAction action : GitAction.values()) {
      options.add(new Option(action, status.hasChanges(), blocker(action, status, gh, running, busy, stored)));
    }
    return options;
  }

  private static @Nullable Blocker blocker(GitAction action, GitCommands.Status status, @Nullable GitHubCli.State gh,
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
    if (status.gitHub() == null) {
      return Blocker.NOT_GITHUB;
    }
    if (gh != GitHubCli.State.READY) {
      return gh == GitHubCli.State.LOGGED_OUT ? Blocker.GH_LOGGED_OUT : Blocker.GH_MISSING;
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
