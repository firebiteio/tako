package io.firebite.tako.ide.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.ide.project.GitDiffs.BranchState;
import io.firebite.tako.ide.project.GitDiffs.Change;
import io.firebite.tako.ide.project.GitDiffs.Changes;
import io.firebite.tako.ide.project.GitDiffs.Commit;
import io.firebite.tako.ide.project.GitDiffs.Sources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GitDiffsTest {
  private static final Map<String, String> ENVIRONMENT = System.getenv();
  private static final GitDiffs.Source UNCOMMITTED = new GitDiffs.Uncommitted();
  private static final GitDiffs.Source BRANCH = new GitDiffs.Branch();

  @Test
  public void aFolderOutsideGitOrAGoneFolderCannotBeRead() throws Exception {
    Path plain = Files.createTempDirectory("tako-plain").toRealPath();

    assertThrows(Worktrees.GitException.class, () -> GitDiffs.sources(plain, ENVIRONMENT));
    assertThrows(Worktrees.GitException.class, () -> GitDiffs.sources(plain.resolve("gone"), ENVIRONMENT));
    assertThrows(Worktrees.GitException.class, () -> GitDiffs.changes(plain, UNCOMMITTED, ENVIRONMENT));
  }

  @Test
  public void aFreshRepositoryShowsItsUntrackedFilesAgainstNothing() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    assertFalse(Files.exists(repository.resolve(".git/index")));

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);
    Changes changes = GitDiffs.changes(repository, UNCOMMITTED, ENVIRONMENT);

    assertEquals("main", sources.headLabel());
    assertNull(sources.head());
    assertEquals(BranchState.UNBORN, sources.branchState());
    assertEquals(1, sources.uncommitted());
    assertTrue(sources.commits().isEmpty());
    assertNull(changes.beforeRevision());
    assertNull(changes.afterRevision());
    assertEquals(List.of(new Change('A', "a.txt", null)), changes.files());
    assertFalse(Files.exists(repository.resolve(".git/index")));
    assertThrows(Worktrees.GitException.class, () -> GitDiffs.changes(repository, BRANCH, ENVIRONMENT));
  }

  @Test
  public void uncommittedChangesCoverEveryKindWithoutTouchingTheIndex() throws Exception {
    Path repository = repository();
    write(repository, "edit.txt", "one\n");
    write(repository, "staged.txt", "one\n");
    write(repository, "gone.txt", "one\n");
    write(repository, "moved.txt", "a longer text that rename detection can match\n");
    write(repository, "renamed.txt", "another longer text that rename detection can match\n");
    write(repository, "reverted.txt", "one\n");
    commit(repository, "base");
    write(repository, "edit.txt", "two\n");
    write(repository, "staged.txt", "two\n");
    git(repository, "add", "staged.txt");
    Files.delete(repository.resolve("gone.txt"));
    git(repository, "mv", "renamed.txt", "renamed-staged.txt");
    Files.move(repository.resolve("moved.txt"), repository.resolve("moved here.txt"));
    write(repository, "ünïcödé fïlé.txt", "new\n");
    write(repository, "reverted.txt", "two\n");
    git(repository, "add", "reverted.txt");
    write(repository, "reverted.txt", "one\n");
    Path index = repository.resolve(".git/index");
    Files.setLastModifiedTime(index, FileTime.fromMillis(1_000_000_000_000L));
    byte[] indexBefore = Files.readAllBytes(index);

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);
    Changes changes = GitDiffs.changes(repository, UNCOMMITTED, ENVIRONMENT);

    List<Change> expected = List.of(new Change('M', "edit.txt", null), new Change('D', "gone.txt", null),
      new Change('R', "moved here.txt", "moved.txt"), new Change('R', "renamed-staged.txt", "renamed.txt"),
      new Change('M', "staged.txt", null), new Change('A', "ünïcödé fïlé.txt", null));
    assertEquals(expected, changes.files());
    assertEquals(6, sources.uncommitted());
    assertEquals(sources.head(), changes.beforeRevision());
    assertEquals("HEAD", changes.beforeName());
    assertNull(changes.afterRevision());
    assertArrayEquals(indexBefore, Files.readAllBytes(index));
    assertEquals(1_000_000_000_000L, Files.getLastModifiedTime(index).toMillis());
  }

  @Test
  public void aCleanCheckoutHasNothingUncommitted() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "base");

    assertEquals(0, GitDiffs.sources(repository, ENVIRONMENT).uncommitted());
    assertTrue(GitDiffs.changes(repository, UNCOMMITTED, ENVIRONMENT).files().isEmpty());
  }

  @Test
  public void theBranchIsComparedWithWhatOriginHeadPointsTo() throws Exception {
    Path clone = cloneWithMain();
    git(clone, "checkout", "-q", "-b", "feature");
    write(clone, "feature.txt", "one\n");
    commit(clone, "add feature");
    write(clone, "dirty.txt", "not committed\n");

    Sources sources = GitDiffs.sources(clone, ENVIRONMENT);
    Changes changes = GitDiffs.changes(clone, BRANCH, ENVIRONMENT);

    assertEquals("origin/main", sources.base());
    assertEquals(BranchState.OK, sources.branchState());
    assertEquals(1, sources.branchCommits());
    assertEquals(List.of("add feature"), subjects(sources.commits()));
    assertEquals(List.of(new Change('A', "feature.txt", null)), changes.files());
    assertTrue(changes.beforeName().startsWith("origin/main ("));
    assertTrue(changes.afterName().startsWith("feature ("));
    assertEquals(sources.head(), changes.afterRevision());
  }

  @Test
  public void baseCommitsMadeAfterBranchingAreLeftOut() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "base");
    git(repository, "checkout", "-q", "-b", "feature");
    write(repository, "feature.txt", "one\n");
    commit(repository, "feature");
    git(repository, "checkout", "-q", "main");
    write(repository, "later.txt", "one\n");
    commit(repository, "later on main");
    git(repository, "checkout", "-q", "feature");

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);

    assertEquals("main", sources.base());
    assertEquals(1, sources.branchCommits());
    assertEquals(List.of(new Change('A', "feature.txt", null)),
      GitDiffs.changes(repository, BRANCH, ENVIRONMENT).files());
  }

  @Test
  public void originMasterIsTheBaseWithoutOriginHead() throws Exception {
    Path origin = Files.createTempDirectory("tako-origin").toRealPath();
    git(origin, "init", "-q", "--bare", "-b", "master");
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "base");
    git(repository, "remote", "add", "origin", origin.toString());
    git(repository, "push", "-q", "origin", "main:master");
    git(repository, "fetch", "-q", "origin");
    git(repository, "checkout", "-q", "-b", "feature");
    commit(repository, "feature");

    assertEquals("origin/master", GitDiffs.sources(repository, ENVIRONMENT).base());
  }

  @Test
  public void aDanglingOriginHeadFallsBackToOriginMain() throws Exception {
    Path clone = cloneWithMain();
    git(clone, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/gone");
    git(clone, "checkout", "-q", "-b", "feature");
    commit(clone, "feature");

    assertEquals("origin/main", GitDiffs.sources(clone, ENVIRONMENT).base());
  }

  @Test
  public void aTagNamedMainDoesNotStandInForTheBranch() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "base");
    git(repository, "checkout", "-q", "-b", "feature");
    write(repository, "b.txt", "one\n");
    commit(repository, "feature");
    git(repository, "tag", "main");

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);

    assertEquals("main", sources.base());
    assertEquals(1, sources.branchCommits());
  }

  @Test
  public void withoutAnyDefaultBranchTheNewestCommitsAreListed() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "first");
    commit(repository, "second");

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);

    assertEquals(BranchState.NO_BASE, sources.branchState());
    assertNull(sources.base());
    assertEquals(List.of("second", "first"), subjects(sources.commits()));
    assertThrows(Worktrees.GitException.class, () -> GitDiffs.changes(repository, BRANCH, ENVIRONMENT));
  }

  @Test
  public void unrelatedHistoryHasNoBase() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "main root");
    git(repository, "checkout", "-q", "--orphan", "other");
    commit(repository, "other root");

    assertEquals(BranchState.NO_BASE, GitDiffs.sources(repository, ENVIRONMENT).branchState());
  }

  @Test
  public void theDefaultBranchInSyncWithOriginHasNoOwnCommits() throws Exception {
    Path clone = cloneWithMain();

    Sources sources = GitDiffs.sources(clone, ENVIRONMENT);

    assertEquals(BranchState.NO_OWN_COMMITS, sources.branchState());
    assertEquals("origin/main", sources.base());
    assertTrue(sources.commits().isEmpty());
    assertTrue(GitDiffs.changes(clone, BRANCH, ENVIRONMENT).files().isEmpty());

    commit(clone, "unpushed");
    Sources ahead = GitDiffs.sources(clone, ENVIRONMENT);
    assertEquals(BranchState.OK, ahead.branchState());
    assertEquals(List.of("unpushed"), subjects(ahead.commits()));
  }

  @Test
  public void aDetachedHeadIsNamedByItsCommit() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "base");
    git(repository, "checkout", "-q", "-b", "feature");
    write(repository, "b.txt", "one\n");
    commit(repository, "feature");
    git(repository, "checkout", "-q", "--detach");

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);
    Changes changes = GitDiffs.changes(repository, BRANCH, ENVIRONMENT);

    String shortHead = git(repository, "rev-parse", "--short", "HEAD").trim();
    assertEquals(shortHead, sources.headLabel());
    assertEquals(BranchState.OK, sources.branchState());
    assertEquals(shortHead, changes.afterName());
    assertEquals(List.of(new Change('A', "b.txt", null)), changes.files());
  }

  @Test
  public void commitsAreNewestFirstAndCapped() throws Exception {
    Path repository = repository();
    commit(repository, "base");
    git(repository, "checkout", "-q", "-b", "feature");
    for (int i = 1; i <= GitDiffs.COMMIT_LIMIT + 5; i++) {
      commit(repository, "commit " + i);
    }
    commit(repository, "");
    commit(repository, "ünïcödé | pipe");

    Sources sources = GitDiffs.sources(repository, ENVIRONMENT);

    assertEquals(GitDiffs.COMMIT_LIMIT + 7, sources.branchCommits());
    assertEquals(GitDiffs.COMMIT_LIMIT, sources.commits().size());
    assertEquals("ünïcödé | pipe", sources.commits().get(0).subject());
    assertEquals("", sources.commits().get(1).subject());
    assertEquals("commit " + (GitDiffs.COMMIT_LIMIT + 5), sources.commits().get(2).subject());
    assertTrue(sources.commits().get(0).timeMillis() > 1_000_000_000_000L);
  }

  @Test
  public void aMergeCommitIsComparedWithItsFirstParent() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "base");
    git(repository, "checkout", "-q", "-b", "side");
    write(repository, "side.txt", "one\n");
    commit(repository, "side");
    git(repository, "checkout", "-q", "main");
    git(repository, "checkout", "-q", "-b", "feature");
    write(repository, "feature.txt", "one\n");
    commit(repository, "feature");
    git(repository, "merge", "-q", "--no-ff", "-m", "merge side", "side");

    Commit merge = GitDiffs.sources(repository, ENVIRONMENT).commits().get(0);
    Changes changes = GitDiffs.changes(repository, merge, ENVIRONMENT);

    assertEquals("merge side", merge.subject());
    assertEquals(List.of(new Change('A', "side.txt", null)), changes.files());
    assertEquals(merge.shortSha() + "^", changes.beforeName());
    assertEquals(merge.shortSha(), changes.afterName());
  }

  @Test
  public void theRootCommitIsComparedWithNothing() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    commit(repository, "root");

    Commit root = GitDiffs.sources(repository, ENVIRONMENT).commits().get(0);
    Changes changes = GitDiffs.changes(repository, root, ENVIRONMENT);

    assertNull(changes.beforeRevision());
    assertNull(changes.beforeName());
    assertEquals(List.of(new Change('A', "a.txt", null)), changes.files());
  }

  @Test
  public void aCommitThatIsGoneCannotBeRead() throws Exception {
    Path repository = repository();
    commit(repository, "root");
    Commit missing = new Commit("0123456789012345678901234567890123456789", "0123456", "gone", 0);

    assertThrows(Worktrees.GitException.class, () -> GitDiffs.changes(repository, missing, ENVIRONMENT));
  }

  @Test
  public void contentKeepsBytesAndSkipsLargeFiles() throws Exception {
    Path repository = repository();
    byte[] binary = {0, 1, 2, (byte) 0xff, 10, 13, 0};
    Files.write(repository.resolve("image.bin"), binary);
    write(repository, "big.txt", "0123456789");
    commit(repository, "files");

    GitDiffs.Content small = GitDiffs.content(repository, "HEAD", "image.bin", GitDiffs.MAX_FILE_BYTES, ENVIRONMENT);
    GitDiffs.Content big = GitDiffs.content(repository, "HEAD", "big.txt", 5, ENVIRONMENT);

    assertArrayEquals(binary, small.bytes());
    assertEquals(binary.length, small.size());
    assertNull(big.bytes());
    assertEquals(10, big.size());
    assertThrows(Worktrees.GitException.class, () -> GitDiffs.content(repository, "HEAD", "none.txt", 5,
      ENVIRONMENT));
  }

  @Test
  public void nameStatusReadsRenamesCopiesAndUnknownLetters() {
    String output = "M\0a.txt\0R087\0old name.txt\0new name.txt\0C100\0src.txt\0copy.txt\0U\0conflict.txt\0"
      + "A\0ünï.txt\0";

    assertEquals(List.of(new Change('M', "a.txt", null), new Change('R', "new name.txt", "old name.txt"),
      new Change('C', "copy.txt", "src.txt"), new Change('U', "conflict.txt", null),
      new Change('A', "ünï.txt", null)), GitDiffs.parseNameStatus(output));
    assertEquals(List.of(), GitDiffs.parseNameStatus(""));
    assertEquals(List.of(), GitDiffs.parseNameStatus("M\0"));
  }

  @Test
  public void logReadsFieldsAndSkipsJunk() {
    String output = "abc\u001fa\u001f1700000000\u001fFirst | one\u001e\n"
      + "def\u001fd\u001f1600000000\u001f\u001e\n"
      + "broken\u001e\n";

    assertEquals(List.of(new Commit("abc", "a", "First | one", 1_700_000_000_000L),
      new Commit("def", "d", "", 1_600_000_000_000L)), GitDiffs.parseLog(output));
    assertEquals(List.of(), GitDiffs.parseLog(""));
  }

  private static List<String> subjects(List<Commit> commits) {
    List<String> subjects = new ArrayList<>();
    for (Commit commit : commits) {
      subjects.add(commit.subject());
    }
    return subjects;
  }

  /** A clone of a repository with one commit on {@code main}, with {@code origin/HEAD} set. */
  private static Path cloneWithMain() throws Exception {
    Path source = repository();
    write(source, "a.txt", "one\n");
    commit(source, "base");
    Path origin = Files.createTempDirectory("tako-origin").toRealPath();
    git(origin, "clone", "-q", "--bare", source.toString(), "repo.git");
    Path parent = Files.createTempDirectory("tako-clone").toRealPath();
    git(parent, "clone", "-q", origin.resolve("repo.git").toString(), "clone");
    Path clone = parent.resolve("clone");
    configure(clone);
    return clone;
  }

  private static Path repository() throws Exception {
    Path repository = Files.createTempDirectory("tako-repo").toRealPath();
    git(repository, "init", "-q", "-b", "main");
    configure(repository);
    return repository;
  }

  private static void configure(Path repository) throws Exception {
    git(repository, "config", "user.email", "test@example.com");
    git(repository, "config", "user.name", "Test");
    git(repository, "config", "core.autocrlf", "false");
    git(repository, "config", "commit.gpgsign", "false");
  }

  private static void commit(Path repository, String message) throws Exception {
    git(repository, "add", "--all");
    git(repository, "commit", "-q", "--allow-empty", "--allow-empty-message", "-m", message);
  }

  private static void write(Path repository, String path, String text) throws Exception {
    Path file = repository.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private static String git(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(output, 0, process.waitFor());
    return output;
  }
}
