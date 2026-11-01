package io.firebite.tako.ide.project;

import static io.firebite.tako.ide.project.TestRepos.ENVIRONMENT;
import static io.firebite.tako.ide.project.TestRepos.git;
import static io.firebite.tako.ide.project.TestRepos.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public class GitCommandsTest {
  private static final BooleanSupplier NEVER = GitCommands.NEVER_CANCELLED;

  @Test
  public void missingFolderIsNoRepository() {
    assertEquals(GitCommands.Status.NONE, GitCommands.status(Path.of("does-not-exist-" + System.nanoTime()),
      ENVIRONMENT));
    assertNull(GitCommands.topLevel(Path.of("does-not-exist-" + System.nanoTime()), ENVIRONMENT));
  }

  @Test
  public void folderOutsideGitIsNoRepository() throws Exception {
    Path folder = Files.createTempDirectory("tako-plain").toRealPath();

    assertFalse(GitCommands.status(folder, ENVIRONMENT).isRepository());
  }

  @Test
  public void unbornRepositoryCountsEveryUntrackedFileEvenWhenStatusHidesThem() throws Exception {
    Path repository = TestRepos.repository();
    git(repository, "config", "status.showUntrackedFiles", "no");
    write(repository, "ü 文件.txt", "neu\n");
    write(repository, "folder/one.txt", "1\n");
    write(repository, "folder/two.txt", "2\n");

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    assertTrue(status.isRepository());
    assertEquals("main", status.branch());
    assertEquals(3, status.changedFiles());
    assertFalse(status.hasCommits());
    assertTrue(status.hasChanges());
    assertNull(status.remote());
    assertEquals(repository.toAbsolutePath().normalize().toString(), status.topLevel());
    assertEquals(List.of(), GitCommands.recentSubjects(repository, ENVIRONMENT, 10));
    assertEquals(List.of(), GitCommands.branchSubjects(repository, ENVIRONMENT, "origin", "main", 10));
  }

  @Test
  public void subfolderReportsTheRepository() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "sub/file.txt", "x\n");

    GitCommands.Status status = GitCommands.status(repository.resolve("sub"), ENVIRONMENT);

    assertEquals(repository.toAbsolutePath().normalize().toString(), status.topLevel());
    assertEquals(1, status.changedFiles());
  }

  @Test
  public void detachedHeadHasNoBranch() throws Exception {
    Path repository = TestRepos.committed();
    git(repository, "checkout", "-q", "--detach");

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    assertTrue(status.isRepository());
    assertNull(status.branch());
    assertNull(status.remote());
  }

  @Test
  public void mergeConflictsAreFound() throws Exception {
    Path repository = TestRepos.committed();
    git(repository, "checkout", "-q", "-b", "other");
    write(repository, "a.txt", "other\n");
    git(repository, "commit", "-q", "-am", "other");
    git(repository, "checkout", "-q", "main");
    write(repository, "a.txt", "main\n");
    git(repository, "commit", "-q", "-am", "main");
    try {
      git(repository, "merge", "-q", "other");
      fail("The merge should conflict");
    } catch (AssertionError expected) {
    }

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    assertTrue(status.hasConflicts());
    assertTrue(status.isMerging());
  }

  @Test
  public void commitRefusesConflictsThatAppearedAfterTheMenu() throws Exception {
    Path repository = TestRepos.committed();
    git(repository, "checkout", "-q", "-b", "other");
    write(repository, "a.txt", "other\n");
    git(repository, "commit", "-q", "-am", "other");
    git(repository, "checkout", "-q", "main");
    write(repository, "a.txt", "main\n");
    git(repository, "commit", "-q", "-am", "main");
    try {
      git(repository, "merge", "-q", "other");
      fail("The merge should conflict");
    } catch (AssertionError expected) {
    }

    GitCommands.CommandException error = expectFailure(() -> GitCommands.commit(repository, ENVIRONMENT, "x", NEVER));

    assertEquals(GitCommands.CommandException.Kind.CONFLICTS, error.kind());
    assertTrue(git(repository, "status", "--porcelain").contains("UU a.txt"));
  }

  @Test
  public void pushToAForkKeepsTheUpstreamOnTheOtherRemote() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.addRemote(repository, "upstream");
    Path fork = TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "upstream", "main");
    git(repository, "config", "remote.pushDefault", "origin");
    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);
    assertEquals("origin", status.remote());
    assertFalse(status.tracksBranch());

    assertEquals("origin/main", GitCommands.push(repository, ENVIRONMENT, status, NEVER));

    assertEquals("upstream\n", git(repository, "config", "branch.main.remote"));
    assertEquals(git(repository, "rev-parse", "HEAD"), git(fork, "rev-parse", "refs/heads/main"));
  }

  @Test
  public void remoteWithoutUpstreamIsPushedWithTracking() throws Exception {
    Path repository = TestRepos.committed();
    Path bare = TestRepos.addRemote(repository, "origin");

    GitCommands.Status before = GitCommands.status(repository, ENVIRONMENT);
    assertEquals("origin", before.remote());
    assertFalse(before.tracksBranch());
    assertNull(before.gitHub());

    assertEquals("origin/main", GitCommands.push(repository, ENVIRONMENT, before, NEVER));

    GitCommands.Status after = GitCommands.status(repository, ENVIRONMENT);
    assertTrue(after.tracksBranch());
    assertEquals(0, after.ahead());
    assertEquals(git(repository, "rev-parse", "HEAD"), git(bare, "rev-parse", "refs/heads/main"));
    write(repository, "b.txt", "b\n");
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "second");
    assertEquals(1, GitCommands.status(repository, ENVIRONMENT).ahead());
  }

  @Test
  public void upstreamOfAnotherNameCountsAsNoneAndIsNotPushedTo() throws Exception {
    Path repository = TestRepos.committed();
    Path bare = TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "origin", "main");
    git(repository, "checkout", "-q", "-b", "feature", "origin/main");
    write(repository, "f.txt", "f\n");
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "feature");
    String mainBefore = git(bare, "rev-parse", "refs/heads/main");

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);
    assertFalse(status.tracksBranch());
    assertEquals("origin/feature", GitCommands.push(repository, ENVIRONMENT, status, NEVER));

    assertEquals(mainBefore, git(bare, "rev-parse", "refs/heads/main"));
    assertEquals(git(repository, "rev-parse", "HEAD"), git(bare, "rev-parse", "refs/heads/feature"));
    assertEquals("refs/heads/feature\n", git(repository, "config", "branch.feature.merge"));
  }

  @Test
  public void prunedUpstreamCountsAsNotTracking() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "origin", "main");
    git(repository, "update-ref", "-d", "refs/remotes/origin/main");

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    assertFalse(status.tracksBranch());
    assertEquals(0, status.ahead());
  }

  @Test
  public void pushRemoteFollowsGitsOrder() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.addRemote(repository, "first");
    TestRepos.addRemote(repository, "second");
    assertEquals("first", GitCommands.status(repository, ENVIRONMENT).remote());

    TestRepos.addRemote(repository, "origin");
    assertEquals("origin", GitCommands.status(repository, ENVIRONMENT).remote());

    git(repository, "config", "remote.pushDefault", "second");
    assertEquals("second", GitCommands.status(repository, ENVIRONMENT).remote());

    git(repository, "config", "remote.pushDefault", "gone");
    assertEquals("origin", GitCommands.status(repository, ENVIRONMENT).remote());
  }

  @Test
  public void defaultBranchComesFromTheRemotesHead() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "origin", "main");
    assertNull(GitCommands.status(repository, ENVIRONMENT).defaultBranch());

    git(repository, "remote", "set-head", "origin", "main");

    assertEquals("main", GitCommands.status(repository, ENVIRONMENT).defaultBranch());
  }

  @Test
  public void githubOriginIsRecognizedEvenWhenPushingElsewhere() throws Exception {
    Path repository = TestRepos.committed();
    Path bare = TestRepos.addRemote(repository, "origin");
    git(repository, "remote", "set-url", "origin", "https://github.com/firebite/tako.git");
    git(repository, "remote", "set-url", "--push", "origin", bare.toString());

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    assertEquals(new GitCommands.GitHubRepo("github.com", "firebite", "tako"), status.gitHub());
    assertEquals("repos/firebite/tako", status.gitHub().apiPath());
  }

  @Test
  public void githubIsTheRepositoryOfThePushRemoteWhateverItsName() throws Exception {
    Path repository = TestRepos.committed();
    git(repository, "config", "url.https://github.com/.insteadOf", "gh:");
    git(repository, "remote", "add", "origin", "https://gitlab.com/team/app.git");
    git(repository, "remote", "add", "fork", "gh:me/app.git");
    git(repository, "config", "remote.pushDefault", "fork");

    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    assertEquals("fork", status.remote());
    assertEquals(new GitCommands.GitHubRepo("github.com", "me", "app"), status.gitHub());
  }

  @Test
  public void remotesAreReadWithTheirFetchUrls() {
    Map<String, String> remotes = GitCommands.parseRemotes("origin\thttps://github.com/o/r.git (fetch)\n"
      + "origin\t../bare (push)\nmy.fork\tgit@github.com:me/r.git (fetch)\nmy.fork\tgit@github.com:me/r.git (push)\n");

    assertEquals(Map.of("origin", "https://github.com/o/r.git", "my.fork", "git@github.com:me/r.git"), remotes);
    assertEquals(List.of("origin", "my.fork"), List.copyOf(remotes.keySet()));
    assertEquals(Map.of(), GitCommands.parseRemotes(""));
  }

  @Test
  public void configKeepsTheLastValueAndTheFirstOrder() {
    Map<String, String> config = GitCommands.parseConfig("remote.fork.url\nhttps://a\0core.bare\nfalse\0"
      + "remote.my.dotted.url\n../x\0remote.fork.url\nhttps://b\0core.flag\0");

    assertEquals(List.of("remote.fork.url", "core.bare", "remote.my.dotted.url", "core.flag"),
      List.copyOf(config.keySet()));
    assertEquals("https://b", config.get("remote.fork.url"));
    assertEquals("true", config.get("core.flag"));
  }

  @Test
  public void statusIsReadFromGitsAnswers() {
    Map<String, String> config = GitCommands.parseConfig("remote.pushdefault\nmy.fork\0branch.Feat.remote\nmy.fork\0"
      + "branch.Feat.merge\nrefs/heads/Feat\0");
    Map<String, String> remotes = GitCommands.parseRemotes("upstream\thttps://github.com/up/r (fetch)\n"
      + "my.fork\thttps://github.com/me/r (fetch)\n");
    String porcelain = "# branch.oid 1234\n# branch.head Feat\n# branch.upstream my.fork/Feat\n# branch.ab +3 -1\n"
      + "1 .M N... 100644 100644 100644 a a a.txt\n? new file.txt\n";

    GitCommands.Status status = GitCommands.parseStatus("/r", false, porcelain, false, config,
      "refs/remotes/my.fork/HEAD refs/remotes/my.fork/develop\nrefs/remotes/upstream/HEAD refs/remotes/upstream/main\n",
      remotes);

    assertEquals("Feat", status.branch());
    assertEquals(2, status.changedFiles());
    assertTrue(status.hasCommits());
    assertEquals("my.fork", status.remote());
    assertTrue(status.tracksBranch());
    assertEquals(3, status.ahead());
    assertEquals("develop", status.defaultBranch());
    assertEquals(new GitCommands.GitHubRepo("github.com", "me", "r"), status.gitHub());
  }

  @Test
  public void statusBeforeTheFirstCommitAndDetached() {
    GitCommands.Status unborn = GitCommands.parseStatus("/r", false, "# branch.oid (initial)\n# branch.head main\n",
      false, Map.of(), "", Map.of());
    GitCommands.Status detached = GitCommands.parseStatus("/r", false, "# branch.oid 12\n# branch.head (detached)\n",
      false, Map.of(), "", Map.of("origin", "https://github.com/o/r"));

    assertEquals("main", unborn.branch());
    assertFalse(unborn.hasCommits());
    assertNull(unborn.remote());
    assertNull(detached.branch());
    assertNull(detached.remote());
    assertNull(detached.gitHub());
  }

  @Test
  public void githubUrlsAreParsed() {
    GitCommands.GitHubRepo tako = new GitCommands.GitHubRepo("github.com", "firebite", "tako");
    assertEquals(tako, GitCommands.parseGitHub("git@github.com:firebite/tako.git"));
    assertEquals(tako, GitCommands.parseGitHub("git@github.com:firebite/tako"));
    assertEquals(tako, GitCommands.parseGitHub("https://github.com/firebite/tako.git\n"));
    assertEquals(tako, GitCommands.parseGitHub("https://user:token@github.com/firebite/tako/"));
    assertEquals(tako, GitCommands.parseGitHub("ssh://git@github.com:22/firebite/tako.git"));
    assertEquals(new GitCommands.GitHubRepo("github.example.com", "team", "app"),
      GitCommands.parseGitHub("https://GitHub.Example.com/team/app"));
    assertNull(GitCommands.parseGitHub("git@gitlab.com:firebite/tako.git"));
    assertNull(GitCommands.parseGitHub("https://example.com/github/tako.git"));
    assertNull(GitCommands.parseGitHub("C:/repos/github/tako"));
    assertNull(GitCommands.parseGitHub("https://github.com/firebite"));
    assertNull(GitCommands.parseGitHub("https://github.com/a/b/c"));
    assertNull(GitCommands.parseGitHub(""));
    assertNull(GitCommands.parseGitHub(null));
  }

  @Test
  public void pendingDiffShowsNewFilesWithoutTouchingTheIndex() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "one\ntwo\n");
    write(repository, "new file ü.txt", "brand new\n");

    String diff = GitCommands.pendingDiff(repository, ENVIRONMENT, true, 10_000);

    assertTrue(diff, diff.contains("+two"));
    assertTrue(diff, diff.contains("new file ü.txt"));
    assertTrue(diff, diff.contains("+brand new"));
    assertTrue(git(repository, "status", "--porcelain").contains("?? \"new file ü.txt\"")
      || git(repository, "status", "--porcelain").contains("?? new file ü.txt"));
  }

  @Test
  public void pendingDiffWorksBeforeTheFirstCommitAndIsCut() throws Exception {
    Path repository = TestRepos.repository();
    write(repository, "big.txt", "line\n".repeat(10_000));

    String diff = GitCommands.pendingDiff(repository, ENVIRONMENT, false, 500);

    assertTrue(diff, diff.contains("big.txt"));
    assertTrue(diff, diff.endsWith("[cut]"));
    assertTrue(diff.length() < 520);
    assertFalse(Files.exists(repository.resolve(".git/index")));
  }

  @Test
  public void commitRecordsEverythingWithAUnicodeMessage() throws Exception {
    Path repository = TestRepos.repository();
    write(repository, "a.txt", "a\n");
    write(repository, "dir/ä.txt", "ä\n");
    String message = "feat: grüße \"quoted\" `code` 🎉\n\nSecond line\nThird line";

    String commit = GitCommands.commit(repository, ENVIRONMENT, message, NEVER);

    assertEquals(git(repository, "rev-parse", "--short", "HEAD").strip(), commit);
    assertEquals(message, git(repository, "log", "-1", "--format=%B").strip());
    assertTrue(git(repository, "status", "--porcelain").isBlank());
    assertEquals(List.of("feat: grüße \"quoted\" `code` 🎉"), GitCommands.recentSubjects(repository, ENVIRONMENT,
      10));
  }

  @Test
  public void nothingToCommitIsReported() throws Exception {
    Path repository = TestRepos.committed();

    GitCommands.CommandException error = expectFailure(() -> GitCommands.commit(repository, ENVIRONMENT, "x", NEVER));

    assertEquals(GitCommands.CommandException.Kind.NOTHING_TO_COMMIT, error.kind());
  }

  @Test
  public void failingHookShowsItsOutputAndLeavesNoCommit() throws Exception {
    Path repository = TestRepos.committed();
    String head = git(repository, "rev-parse", "HEAD");
    TestRepos.hook(repository, "pre-commit", "echo 'lint: 3 problems' >&2\nexit 1");
    write(repository, "a.txt", "changed\n");

    GitCommands.CommandException error = expectFailure(() -> GitCommands.commit(repository, ENVIRONMENT, "x", NEVER));

    assertEquals(GitCommands.CommandException.Kind.FAILED, error.kind());
    assertTrue(error.getMessage(), error.getMessage().contains("lint: 3 problems"));
    assertEquals(head, git(repository, "rev-parse", "HEAD"));
  }

  @Test
  public void failedCommitKeepsWhatThePersonHadStaged() throws Exception {
    Path repository = TestRepos.committed();
    write(repository, "a.txt", "staged\n");
    write(repository, "b.txt", "unstaged new file\n");
    git(repository, "add", "a.txt");
    String before = git(repository, "status", "--porcelain");
    TestRepos.hook(repository, "pre-commit", "exit 1");

    expectFailure(() -> GitCommands.commit(repository, ENVIRONMENT, "x", NEVER));

    assertEquals(before, git(repository, "status", "--porcelain"));
  }

  @Test
  public void failedFirstCommitLeavesNoIndex() throws Exception {
    Path repository = TestRepos.repository();
    write(repository, "a.txt", "a\n");
    TestRepos.hook(repository, "pre-commit", "exit 1");

    expectFailure(() -> GitCommands.commit(repository, ENVIRONMENT, "x", NEVER));

    assertFalse(Files.exists(repository.resolve(".git/index")));
    assertEquals("?? a.txt\n", git(repository, "status", "--porcelain"));
  }

  @Test
  public void cancelledCommitReleasesTheIndex() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.hook(repository, "pre-commit", "sleep 60");
    write(repository, "a.txt", "changed\n");
    long start = System.nanoTime();

    expectFailure(
      () -> GitCommands.commit(repository, ENVIRONMENT, "x", () -> System.nanoTime() - start > 1_000_000_000L));

    assertFalse(Files.exists(repository.resolve(".git/index.lock")));
    assertEquals(" M a.txt\n", git(repository, "status", "--porcelain"));
    TestRepos.hook(repository, "pre-commit", "exit 0");
    GitCommands.commit(repository, ENVIRONMENT, "after cancel", NEVER);
  }

  @Test
  public void cancellingStopsASlowHook() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.hook(repository, "pre-commit", "sleep 60");
    write(repository, "a.txt", "changed\n");
    long start = System.nanoTime();
    BooleanSupplier cancelled = () -> System.nanoTime() - start > 1_000_000_000L;

    GitCommands.CommandException error = expectFailure(() -> GitCommands.commit(repository, ENVIRONMENT, "x",
      cancelled));

    assertEquals(GitCommands.CommandException.Kind.CANCELLED, error.kind());
    assertTrue(System.nanoTime() - start < 30_000_000_000L);
  }

  @Test
  public void pushRejectedWhenTheRemoteMovedOn() throws Exception {
    Path repository = TestRepos.committed();
    Path bare = TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "origin", "main");
    Path other = Files.createTempDirectory("tako-other").toRealPath();
    git(other, "clone", "-q", bare.toString(), ".");
    git(other, "config", "user.email", "o@example.com");
    git(other, "config", "user.name", "Other");
    write(other, "o.txt", "o\n");
    git(other, "add", ".");
    git(other, "commit", "-q", "-m", "other");
    git(other, "push", "-q");
    write(repository, "a.txt", "mine\n");
    GitCommands.commit(repository, ENVIRONMENT, "mine", NEVER);
    GitCommands.Status status = GitCommands.status(repository, ENVIRONMENT);

    GitCommands.CommandException error = expectFailure(() -> GitCommands.push(repository, ENVIRONMENT, status,
      NEVER));

    assertEquals(GitCommands.CommandException.Kind.PUSH_REJECTED, error.kind());
    assertTrue(error.getMessage(), error.getMessage().contains("rejected"));
  }

  @Test
  public void branchSubjectsKeepCommitsAlreadyPushedToTheBranch() throws Exception {
    Path repository = TestRepos.committed();
    TestRepos.addRemote(repository, "origin");
    git(repository, "push", "-q", "-u", "origin", "main");
    git(repository, "checkout", "-q", "-b", "feat");
    git(repository, "commit", "-q", "--allow-empty", "-m", "feat one");
    git(repository, "push", "-q", "-u", "origin", "feat");
    git(repository, "commit", "-q", "--allow-empty", "-m", "feat two");

    assertEquals(List.of("feat two", "feat one"), GitCommands.branchSubjects(repository, ENVIRONMENT, "origin",
      "feat", 30));
  }

  @Test
  public void rejectionIsToldFromOtherFailures() {
    assertTrue(GitCommands.isRejected(" ! [rejected]        main -> main (fetch first)"));
    assertTrue(GitCommands.isRejected("error: failed to push some refs\nhint: Updates were rejected because the tip "
      + "of your current branch is behind (non-fast-forward)"));
    assertFalse(GitCommands.isRejected("fatal: Could not read from remote repository."));
  }

  private static GitCommands.CommandException expectFailure(Action action) throws Exception {
    try {
      action.run();
    } catch (GitCommands.CommandException e) {
      return e;
    }
    throw new AssertionError("Expected a failure");
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }
}
