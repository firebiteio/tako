package io.firebite.tako.chat.git;

import static io.firebite.tako.ide.project.TestRepos.ENVIRONMENT;
import static io.firebite.tako.ide.project.TestRepos.git;
import static io.firebite.tako.ide.project.TestRepos.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.firebite.tako.agent.AgentException;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitHubCli;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ide.project.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public class ThreadGitTest {
  private static final BooleanSupplier NEVER = GitCommands.NEVER_CANCELLED;
  private static final GitCommands.GitHubRepo REPO = new GitCommands.GitHubRepo("github.com", "o", "r");
  private static final GitInfo.PullRequest OPEN = new GitInfo.PullRequest("#7", "Old", "https://x/7", "open");
  private static final GitInfo.PullRequest MERGED = new GitInfo.PullRequest("#6", "Old", "https://x/6", "merged");

  private final AtomicReference<ThreadGit.Busy> busy = new AtomicReference<>();
  private final FakeGitHub gitHub = new FakeGitHub();

  @Test
  public void cleanPushedBranchOffersOnlyAPullRequest() {
    List<ThreadGit.Option> options = ThreadGit.options(status(0, true, 0), GitHubCli.State.READY, false, null, null);

    assertEquals(List.of(
      new ThreadGit.Option(GitAction.COMMIT, false, Blocker.NOTHING_TO_COMMIT),
      new ThreadGit.Option(GitAction.COMMIT_AND_PUSH, false, Blocker.NOTHING_TO_PUSH),
      new ThreadGit.Option(GitAction.COMMIT_AND_PULL_REQUEST, false, null)), options);
  }

  @Test
  public void changesOfferEverything() {
    List<ThreadGit.Option> options = ThreadGit.options(status(2, false, 0), GitHubCli.State.READY, false, null,
      MERGED);

    for (ThreadGit.Option option : options) {
      assertTrue(option.commits());
      assertNull(option.action() + " is blocked", option.blocker());
    }
  }

  @Test
  public void unpushedCommitsCanBePushed() {
    assertNull(blocker(GitAction.COMMIT_AND_PUSH, status(0, true, 2), GitHubCli.State.READY, null));
    assertNull(blocker(GitAction.COMMIT_AND_PUSH, status(0, false, 0), GitHubCli.State.READY, null));
  }

  @Test
  public void commonBlockersComeFirst() {
    GitCommands.Status changed = status(1, false, 0);
    assertEquals(Blocker.NOT_REPOSITORY, blocker(GitAction.COMMIT, GitCommands.Status.NONE, null, null));
    for (GitAction action : GitAction.values()) {
      assertEquals(Blocker.RUNNING, ThreadGit.options(changed, null, true, Blocker.AGENT_BUSY, null)
        .get(action.ordinal()).blocker());
      assertEquals(Blocker.AGENT_BUSY, ThreadGit.options(changed, null, false, Blocker.AGENT_BUSY, null)
        .get(action.ordinal()).blocker());
      assertEquals(Blocker.OTHER_THREAD_BUSY, ThreadGit.options(changed, null, false, Blocker.OTHER_THREAD_BUSY, null)
        .get(action.ordinal()).blocker());
      assertEquals(Blocker.DETACHED, blocker(action, with(changed, null, "origin", REPO, false, false), null, null));
      assertEquals(Blocker.CONFLICTS, blocker(action, with(changed, "feat", "origin", REPO, true, false), null, null));
    }
  }

  @Test
  public void pushAndPullRequestNeedARemote() {
    GitCommands.Status local = with(status(1, false, 0), "feat", null, null, false, false);

    assertNull(blocker(GitAction.COMMIT, local, null, null));
    assertEquals(Blocker.NO_REMOTE, blocker(GitAction.COMMIT_AND_PUSH, local, null, null));
    assertEquals(Blocker.NO_REMOTE, blocker(GitAction.COMMIT_AND_PULL_REQUEST, local, null, null));
  }

  @Test
  public void cleanBranchWithoutCommitsHasNothingToPush() {
    GitCommands.Status unborn = with(status(0, false, 0), "feat", "origin", REPO, false, false);

    assertEquals(Blocker.NOTHING_TO_PUSH, blocker(GitAction.COMMIT_AND_PUSH, unborn, GitHubCli.State.READY, null));
    assertEquals(Blocker.NOTHING_TO_PUSH, blocker(GitAction.COMMIT_AND_PULL_REQUEST, unborn, GitHubCli.State.READY,
      null));
  }

  @Test
  public void pullRequestBlockersAreSpecific() {
    GitCommands.Status changed = status(1, false, 0);
    GitAction pr = GitAction.COMMIT_AND_PULL_REQUEST;

    assertEquals(Blocker.NOT_GITHUB, blocker(pr, with(changed, "feat", "origin", null, false, true), null, null));
    assertEquals(Blocker.GH_MISSING, blocker(pr, changed, GitHubCli.State.MISSING, null));
    assertEquals(Blocker.GH_CHECKING, blocker(pr, changed, null, null));
    assertEquals(Blocker.GH_LOGGED_OUT, blocker(pr, changed, GitHubCli.State.LOGGED_OUT, null));
    assertEquals(Blocker.PULL_REQUEST_OPEN, blocker(pr, changed, GitHubCli.State.READY, OPEN));
    assertNull("push still updates an open pull request",
      blocker(GitAction.COMMIT_AND_PUSH, changed, GitHubCli.State.READY, OPEN));
    GitCommands.Status onMain = new GitCommands.Status(true, "/r", "main", 1, false, false, true, "origin", false, 0,
      "main", REPO);
    assertEquals(Blocker.DEFAULT_BRANCH, blocker(pr, onMain, GitHubCli.State.READY, null));
    assertNull(blocker(GitAction.COMMIT_AND_PUSH, onMain, GitHubCli.State.READY, null));
  }

  @Test
  public void mergeWithoutChangesCanBeCommitted() {
    GitCommands.Status merging = new GitCommands.Status(true, "/r", "feat", 0, false, true, true, "origin", true, 0,
      null, REPO);

    assertNull(blocker(GitAction.COMMIT, merging, null, null));
  }

  @Test
  public void commitPushAndPullRequestRunInOrder() throws Exception {
    Path repository = this.githubRepository();
    git(repository, "checkout", "-q", "-b", "feat");
    write(repository, "new ü.txt", "neu\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    ThreadGit.Option option = menu.options().get(GitAction.COMMIT_AND_PULL_REQUEST.ordinal());
    assertNull(option.blocker());
    List<ThreadGit.Step> steps = new ArrayList<>();

    ThreadGit.Result result = git.run(option, menu.status(), new Draft("feat: add file", " Add file ", "Body\n"),
      steps::add, NEVER);

    assertEquals(List.of(ThreadGit.Step.COMMIT, ThreadGit.Step.PUSH, ThreadGit.Step.PULL_REQUEST), steps);
    assertEquals(git(repository, "rev-parse", "--short", "HEAD").strip(), result.commit());
    assertEquals("origin/feat", result.pushedTo());
    assertEquals("#1", result.pullRequest().number());
    assertEquals(List.of("feat->main: Add file / Body"), this.gitHub.created);
    assertEquals("feat: add file", git(repository, "log", "-1", "--format=%s").strip());
    assertEquals("refs/heads/feat\n", git(repository, "config", "branch.feat.merge"));
  }

  @Test
  public void pushWithoutChangesSkipsTheCommit() throws Exception {
    Path repository = this.githubRepository();
    git(repository, "checkout", "-q", "-b", "feat");
    git(repository, "commit", "-q", "--allow-empty", "-m", "work");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    ThreadGit.Option push = menu.options().get(GitAction.COMMIT_AND_PUSH.ordinal());

    ThreadGit.Result result = git.run(push, menu.status(), Draft.EMPTY, step -> {
    }, NEVER);

    assertEquals(new ThreadGit.Result(null, "origin/feat", null), result);
    assertEquals(Blocker.NOTHING_TO_PUSH, menu(git).options().get(GitAction.COMMIT_AND_PUSH.ordinal())
      .blocker());
  }

  @Test
  public void defaultBranchIsCheckedBeforeAnythingRuns() throws Exception {
    Path repository = this.githubRepository();
    String head = git(repository, "rev-parse", "HEAD");
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);

    ThreadGit.ActionException error = expectFailure(() -> git.run(
      menu.options().get(GitAction.COMMIT_AND_PULL_REQUEST.ordinal()), menu.status(), new Draft("m", "t", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.ON_DEFAULT_BRANCH, error.failure());
    assertEquals("main", error.detail());
    assertEquals(head, git(repository, "rev-parse", "HEAD"));
    assertEquals(ThreadGit.Result.NONE, error.done());
  }

  @Test
  public void failedPullRequestKeepsTheCommitAndThePush() throws Exception {
    Path repository = this.githubRepository();
    git(repository, "checkout", "-q", "-b", "feat");
    write(repository, "a.txt", "changed\n");
    this.gitHub.createError = "A pull request already exists for o:feat.";
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);

    ThreadGit.ActionException error = expectFailure(() -> git.run(
      menu.options().get(GitAction.COMMIT_AND_PULL_REQUEST.ordinal()), menu.status(), new Draft("m", "t", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.PULL_REQUEST_FAILED, error.failure());
    assertEquals("A pull request already exists for o:feat.", error.detail());
    assertNotNull(error.done().commit());
    assertEquals("origin/feat", error.done().pushedTo());
  }

  @Test
  public void rejectedPushKeepsTheCommit() throws Exception {
    Path repository = this.githubRepository();
    git(repository, "push", "-q", "-u", "origin", "main");
    Path bare = Path.of(git(repository, "remote", "get-url", "--push", "origin").strip());
    Path other = Files.createTempDirectory("tako-other").toRealPath();
    git(other, "clone", "-q", bare.toString(), ".");
    git(other, "-c", "user.email=o@example.com", "-c", "user.name=O", "commit", "-q", "--allow-empty", "-m", "o");
    git(other, "push", "-q");
    write(repository, "a.txt", "mine\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);

    ThreadGit.ActionException error = expectFailure(() -> git.run(
      menu.options().get(GitAction.COMMIT_AND_PUSH.ordinal()), menu.status(), new Draft("mine", "", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.PUSH_REJECTED, error.failure());
    assertEquals(git(repository, "rev-parse", "--short", "HEAD").strip(), error.done().commit());
    assertNull(error.done().pushedTo());
  }

  @Test
  public void failingHookStopsBeforeThePush() throws Exception {
    Path repository = this.githubRepository();
    git(repository, "checkout", "-q", "-b", "feat");
    TestRepos.hook(repository, "commit-msg", "echo 'subject too long' >&2\nexit 1");
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);

    ThreadGit.ActionException error = expectFailure(() -> git.run(
      menu.options().get(GitAction.COMMIT_AND_PUSH.ordinal()), menu.status(), new Draft("m", "", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.COMMIT_FAILED, error.failure());
    assertTrue(error.detail(), error.detail().contains("subject too long"));
    assertEquals(ThreadGit.Result.NONE, error.done());
    Path bare = Path.of(git(repository, "remote", "get-url", "--push", "origin").strip());
    assertTrue(git(bare, "branch", "--list", "feat").isBlank());
  }

  @Test
  public void agentStartingMeanwhileStopsTheRun() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    this.busy.set(new ThreadGit.Busy(Blocker.AGENT_BUSY, "Claude Code"));

    ThreadGit.ActionException error = expectFailure(() -> git.run(menu.options().getFirst(), menu.status(),
      new Draft("m", "", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.BUSY, error.failure());
    assertEquals("Claude Code", error.detail());
    assertEquals(Blocker.AGENT_BUSY, menu(git).options().getFirst().blocker());
    assertEquals("Claude Code", menu(git).busyName());
    assertTrue(git(repository, "status", "--porcelain").contains("a.txt"));
  }

  @Test
  public void branchSwitchedOutsideTheIdeStopsTheRun() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    git(repository, "checkout", "-q", "-b", "elsewhere");

    ThreadGit.ActionException error = expectFailure(() -> git.run(menu.options().getFirst(), menu.status(),
      new Draft("m", "", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.BRANCH_CHANGED, error.failure());
    assertEquals("elsewhere", error.detail());
  }

  @Test
  public void changesRevertedMeanwhileLeaveNothingToCommit() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    write(repository, "a.txt", "one\n");

    ThreadGit.ActionException error = expectFailure(() -> git.run(menu.options().getFirst(), menu.status(),
      new Draft("m", "", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.NOTHING_TO_COMMIT, error.failure());
  }

  @Test
  public void deletedFolderIsNoRepository() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    Path gone = repository.resolve("gone");
    Files.createDirectories(gone);
    ThreadGit git = this.threadGit(gone);
    ThreadGit.Menu menu = menu(git);
    Files.delete(gone);

    assertEquals(Blocker.NOT_REPOSITORY, menu(git).options().getFirst().blocker());
    ThreadGit.ActionException error = expectFailure(() -> git.run(menu.options().getFirst(), menu.status(),
      new Draft("m", "", ""), s -> {
      }, NEVER));
    assertEquals(ThreadGit.Failure.NOT_REPOSITORY, error.failure());
    assertEquals(ThreadGit.Failure.NOT_REPOSITORY, expectFailure(() -> git.run(menu.options().getFirst(),
      GitCommands.Status.NONE, Draft.EMPTY, s -> {
      }, NEVER)).failure());
  }

  @Test
  public void secondRunInTheSameRepositoryIsRejectedWhileTheFirstRuns() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.hook(repository, "pre-commit", "sleep 3");
    write(repository, "a.txt", "changed\n");
    ThreadGit first = this.threadGit(repository);
    ThreadGit second = this.threadGit(repository);
    ThreadGit.Menu menu = menu(first);
    CompletableFuture<ThreadGit.Result> running = CompletableFuture.supplyAsync(() -> {
      try {
        return first.run(menu.options().getFirst(), menu.status(), new Draft("slow", "", ""), s -> {
        }, NEVER);
      } catch (ThreadGit.ActionException e) {
        throw new IllegalStateException(e);
      }
    });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (menu(second).options().getFirst().blocker() != Blocker.RUNNING) {
      if (System.nanoTime() > deadline) {
        fail("The first run never showed as running");
      }
      Thread.sleep(50);
    }

    ThreadGit.ActionException error = expectFailure(() -> second.run(menu.options().getFirst(), menu.status(),
      new Draft("again", "", ""), s -> {
      }, NEVER));

    assertEquals(ThreadGit.Failure.ALREADY_RUNNING, error.failure());
    assertNotNull(running.get(30, TimeUnit.SECONDS).commit());
    assertEquals(Blocker.NOTHING_TO_COMMIT, menu(second).options().getFirst().blocker());
  }

  @Test
  public void cancelledRunReportsWhatItDid() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.hook(repository, "pre-commit", "sleep 60");
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    long start = System.nanoTime();

    ThreadGit.ActionException error = expectFailure(() -> git.run(menu.options().getFirst(), menu.status(),
      new Draft("m", "", ""), s -> {
      }, () -> System.nanoTime() - start > 500_000_000L));

    assertEquals(ThreadGit.Failure.CANCELLED, error.failure());
    assertEquals(ThreadGit.Result.NONE, error.done());
    assertNull("the guard is released", menu(git).options().getFirst().blocker());
  }

  @Test
  public void menuAsksGitHubNothingUntilChecked() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    git(repository, "remote", "add", "origin", "https://github.logged-out-" + System.nanoTime() + ".com/o/r.git");
    ThreadGit git = this.threadGit(repository);
    this.gitHub.state = GitHubCli.State.LOGGED_OUT;

    ThreadGit.Menu local = git.menu(null, null);
    assertEquals(Blocker.GH_CHECKING, local.options().getLast().blocker());
    assertTrue(local.waitsForGitHub());
    assertNull("commit and push do not wait", local.options().get(GitAction.COMMIT_AND_PUSH.ordinal()).blocker());
    assertEquals(0, this.gitHub.stateCalls.get());

    git.checkGitHub(local);
    assertEquals(Blocker.GH_LOGGED_OUT, git.menu(null, null).options().getLast().blocker());
    this.gitHub.state = GitHubCli.State.READY;
    git.checkGitHub(git.menu(null, null));
    assertEquals("a recent answer is reused", 1, this.gitHub.stateCalls.get());
  }

  @Test
  public void readyGitHubCliIsAskedOnce() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    git(repository, "remote", "add", "origin", "https://github.ready-" + System.nanoTime() + ".com/o/r.git");
    ThreadGit git = this.threadGit(repository);

    assertNull(menu(git).options().getLast().blocker());
    assertNull(menu(git).options().getLast().blocker());
    assertFalse(git.menu(null, null).waitsForGitHub());
    assertEquals(1, this.gitHub.stateCalls.get());

    ThreadGit.Menu plain = this.threadGit(TestRepos.committed()).menu(null, null);
    assertFalse(plain.waitsForGitHub());
    this.threadGit(TestRepos.committed()).checkGitHub(plain);
    assertEquals(1, this.gitHub.stateCalls.get());
  }

  @Test
  public void runActsOnTheCheckoutAsItIsNotAsTheMenuShowedIt() throws Exception {
    Path repository = TestRepos.committed();
    git(repository, "commit", "-q", "--allow-empty", "-m", "work");
    Path bare = TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "origin", "main");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu shown = menu(git);
    git(repository, "remote", "remove", "origin");
    Path other = TestRepos.addRemote(repository, "fork");
    git(repository, "commit", "-q", "--allow-empty", "-m", "more");
    ThreadGit.Option push = new ThreadGit.Option(GitAction.COMMIT_AND_PUSH, false, null);

    ThreadGit.Result result = git.run(push, shown.status(), Draft.EMPTY, step -> {
    }, NEVER);

    assertEquals("fork/main", result.pushedTo());
    assertEquals(git(repository, "rev-parse", "HEAD"), git(other, "rev-parse", "refs/heads/main"));
    assertTrue(Files.exists(bare));
  }

  @Test
  public void parallelChecksAskTheGitHubCliOnce() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    git(repository, "remote", "add", "origin", "https://github.parallel-" + System.nanoTime() + ".com/o/r.git");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu local = git.menu(null, null);
    this.gitHub.delayMillis = 500;

    List<CompletableFuture<Void>> checks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      checks.add(CompletableFuture.runAsync(() -> git.checkGitHub(local)));
    }
    for (CompletableFuture<Void> check : checks) {
      check.get(10, TimeUnit.SECONDS);
    }

    assertEquals(1, this.gitHub.stateCalls.get());
    assertNull(git.menu(null, null).options().getLast().blocker());
  }

  @Test
  public void anotherBranchsPullRequestDoesNotBlock() throws Exception {
    Path repository = this.githubRepository();
    git(repository, "checkout", "-q", "-b", "feat");
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    git.checkGitHub(git.menu(null, null));

    assertEquals(Blocker.PULL_REQUEST_OPEN, git.menu(OPEN, "feat").options().getLast().blocker());
    assertNull(git.menu(OPEN, "main").options().getLast().blocker());
    assertNull(git.menu(OPEN, null).options().getLast().blocker());
  }

  @Test
  public void draftAsksWithTheChangesAndParsesTheAnswer() throws Exception {
    Path repository = TestRepos.committed();
    git(repository, "commit", "-q", "--allow-empty", "-m", "chore(build): bump gradle");
    write(repository, "login.txt", "secret sauce\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);
    AtomicReference<String> asked = new AtomicReference<>();

    Draft draft = git.draft(menu.options().getFirst(), menu.status(), "Person: add login", (directory, prompt) -> {
      asked.set(prompt);
      return "<commit>\nfeat(login): add login\n</commit>";
    });

    assertEquals("feat(login): add login", draft.commitMessage());
    assertTrue(asked.get().contains("+secret sauce"));
    assertTrue(asked.get().contains("- chore(build): bump gradle"));
    assertTrue(asked.get().contains("Person: add login"));
  }

  @Test
  public void failingAgentFailsTheDraft() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "changed\n");
    ThreadGit git = this.threadGit(repository);
    ThreadGit.Menu menu = menu(git);

    try {
      git.draft(menu.options().getFirst(), menu.status(), "", (directory, prompt) -> {
        throw new AgentException("No answer after 60 s");
      });
      fail("Expected the agent's failure");
    } catch (AgentException e) {
      assertEquals("No answer after 60 s", e.getMessage());
    }
  }

  /** A repository on main with one commit whose origin reads as GitHub but pushes to a local bare repository. */
  private Path githubRepository() throws Exception {
    Path repository = TestRepos.committed();
    Path bare = TestRepos.addRemote(repository, "origin");
    git(repository, "remote", "set-url", "origin", "https://github.com/o/r.git");
    git(repository, "remote", "set-url", "--push", "origin", bare.toString());
    return repository;
  }

  /** The menu as the header ends up showing it: after the GitHub CLI was asked, when it had to be. */
  private static ThreadGit.Menu menu(ThreadGit git) {
    ThreadGit.Menu menu = git.menu(null, null);
    if (!menu.waitsForGitHub()) {
      return menu;
    }
    git.checkGitHub(menu);
    return git.menu(null, null);
  }

  private ThreadGit threadGit(Path folder) {
    return new ThreadGit(() -> folder, ENVIRONMENT, topLevel -> this.busy.get(), this.gitHub);
  }

  private static Blocker blocker(GitAction action, GitCommands.Status status, GitHubCli.State gh,
    GitInfo.PullRequest stored) {
    return ThreadGit.options(status, gh, false, null, stored).get(action.ordinal()).blocker();
  }

  /** A checkout on {@code feat} with origin on GitHub. */
  private static GitCommands.Status status(int changedFiles, boolean tracksBranch, int ahead) {
    return new GitCommands.Status(true, "/r", "feat", changedFiles, false, false, true, "origin", tracksBranch, ahead,
      "main", REPO);
  }

  private static GitCommands.Status with(GitCommands.Status status, String branch, String remote,
    GitCommands.GitHubRepo gitHub, boolean hasConflicts, boolean hasCommits) {
    return new GitCommands.Status(true, status.topLevel(), branch, status.changedFiles(), hasConflicts,
      status.isMerging(), hasCommits, remote, status.tracksBranch(),
      status.ahead(), status.defaultBranch(), gitHub);
  }

  private static ThreadGit.ActionException expectFailure(Run run) throws Exception {
    try {
      run.run();
    } catch (ThreadGit.ActionException e) {
      return e;
    }
    throw new AssertionError("Expected a failure");
  }

  @FunctionalInterface
  private interface Run {
    void run() throws Exception;
  }

  private static final class FakeGitHub implements GitHubCli {
    private final List<String> created = new ArrayList<>();
    private final AtomicInteger stateCalls = new AtomicInteger();
    private volatile State state = State.READY;
    private volatile String createError;
    private volatile long delayMillis;

    @Override
    public State state(String host, Map<String, String> environment) {
      this.stateCalls.incrementAndGet();
      try {
        Thread.sleep(this.delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return this.state;
    }

    @Override
    public String defaultBranch(Path directory, Map<String, String> environment, GitCommands.GitHubRepo repository,
      BooleanSupplier cancelled) {
      return "main";
    }

    @Override
    public GitInfo.PullRequest create(Path directory, Map<String, String> environment,
      GitCommands.GitHubRepo repository, String head, String base, String title, String body,
      BooleanSupplier cancelled) throws GitCommands.CommandException {
      if (this.createError != null) {
        throw new GitCommands.CommandException(GitCommands.CommandException.Kind.FAILED, this.createError);
      }
      this.created.add(head + "->" + base + ": " + title + " / " + body);
      return new GitInfo.PullRequest("#" + this.created.size(), title, "https://github.com/o/r/pull/1", "open");
    }
  }
}
