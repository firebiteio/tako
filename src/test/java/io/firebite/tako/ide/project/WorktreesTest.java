package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.EditPreview;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WorktreesTest {
  private static final Map<String, String> ENVIRONMENT = System.getenv();

  @Test
  public void slugKeepsLettersAndDigits() {
    assertEquals("fix-the-login-bug-42", Worktrees.slug("Fix the login bug #42!"));
    assertEquals("run", Worktrees.slug("äöü ???"));
    assertEquals("a-very-long-prompt-that", Worktrees.slug("A very long prompt that goes on and on"));
  }

  @Test
  public void benchFolderSitsBesideTheRepository() {
    assertEquals(Path.of("/code/app-tako/fix-1a2b"), Worktrees.benchFolder(Path.of("/code/app"), "fix-1a2b"));
  }

  @Test
  public void numstatCountsFilesAndLines() {
    Worktrees.DiffStat stat = Worktrees.parseNumstat("3\t1\tsrc/A.java\n-\t-\timage.png\n10\t0\tB.java\n");

    assertEquals(new Worktrees.DiffStat(3, 13, 1), stat);
  }

  @Test
  public void runChangesAreReadAndAppliedToTheCheckout() throws Exception {
    Path repository = Files.createTempDirectory("tako-repo").toRealPath();
    git(repository, "init", "-q");
    git(repository, "config", "user.email", "test@example.com");
    git(repository, "config", "user.name", "Test");
    git(repository, "config", "core.autocrlf", "false");
    Files.writeString(repository.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "base");
    String commit = Worktrees.head(repository, ENVIRONMENT);
    Path worktree = Worktrees.benchFolder(repository, "test").resolve("1");

    Worktrees.add(repository, worktree, "test-1", commit, ENVIRONMENT);
    Files.writeString(worktree.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
    Files.writeString(worktree.resolve("b.txt"), "new\n", StandardCharsets.UTF_8);

    assertEquals(new Worktrees.DiffStat(2, 2, 0), Worktrees.diffStat(worktree, commit, ENVIRONMENT));
    List<EditPreview> changes = Worktrees.changes(worktree, commit, ENVIRONMENT);
    assertEquals(List.of(new EditPreview("a.txt", "one\n", "one\ntwo\n"), new EditPreview("b.txt", "", "new\n")),
      changes);
    assertFalse(Worktrees.isDirty(repository, ENVIRONMENT));
    assertTrue(git(worktree, "status", "--porcelain").contains("?? b.txt"));

    Worktrees.applyTo(repository, worktree, commit, ENVIRONMENT);
    assertEquals("one\ntwo\n", Files.readString(repository.resolve("a.txt"), StandardCharsets.UTF_8));
    assertEquals("new\n", Files.readString(repository.resolve("b.txt"), StandardCharsets.UTF_8));

    Worktrees.remove(repository, worktree, "test-1", ENVIRONMENT);
    assertFalse(Files.exists(worktree));
    assertTrue(git(repository, "branch", "--list", "test-1").isBlank());
  }

  @Test
  public void worktreeIncludeCopiesListedIgnoredFiles() throws Exception {
    Path repository = repository();
    write(repository, ".gitignore", "*.env\n*.log\nbuild/\nsecrets/\nlocal.cfg\n");
    write(repository, "tracked.env", "committed");
    write(repository, "local.cfg", "committed");
    write(repository, "data", "committed");
    write(repository, "config/settings.json", "{}");
    git(repository, "add", ".");
    git(repository, "add", "-f", "tracked.env", "local.cfg");
    git(repository, "commit", "-q", "-m", "base");
    git(repository, "rm", "-q", "--cached", "local.cfg");
    String commit = Worktrees.head(repository, ENVIRONMENT);
    write(repository, ".worktreeinclude", "*.env\nsecrets/\nlocal.cfg\n");
    write(repository, ".env", "secret");
    write(repository, "tracked.env", "changed");
    write(repository, "debug.log", "log");
    write(repository, "build/out.txt", "out");
    write(repository, "config/local.env", "local");
    write(repository, "secrets/deep/key.txt", "key");
    write(repository, "local.cfg", "mine");
    Files.delete(repository.resolve("data"));
    write(repository, "data/x.env", "blocked by the committed file data");
    Path worktree = Worktrees.benchFolder(repository, "include").resolve("1");

    Worktrees.add(repository, worktree, "include-1", commit, ENVIRONMENT);

    assertEquals("secret", Files.readString(worktree.resolve(".env")));
    assertEquals("committed", Files.readString(worktree.resolve("tracked.env")));
    assertFalse(Files.exists(worktree.resolve("debug.log")));
    assertFalse(Files.exists(worktree.resolve("build")));
    assertEquals("local", Files.readString(worktree.resolve("config/local.env")));
    assertEquals("key", Files.readString(worktree.resolve("secrets/deep/key.txt")));
    assertEquals("committed", Files.readString(worktree.resolve("local.cfg")));
    assertTrue(Files.isRegularFile(worktree.resolve("data")));
    Worktrees.remove(repository, worktree, "include-1", ENVIRONMENT);
  }

  @Test
  public void noWorktreeIncludeCopiesNothing() throws Exception {
    Path repository = repository();
    write(repository, ".gitignore", "*.env\n");
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "base");
    write(repository, ".env", "secret");
    Path worktree = Worktrees.benchFolder(repository, "plain").resolve("1");

    Worktrees.add(repository, worktree, "plain-1", Worktrees.head(repository, ENVIRONMENT), ENVIRONMENT);

    assertTrue(Files.isRegularFile(worktree.resolve(".gitignore")));
    assertFalse(Files.exists(worktree.resolve(".env")));
    Worktrees.remove(repository, worktree, "plain-1", ENVIRONMENT);
  }

  private static Path repository() throws Exception {
    Path repository = Files.createTempDirectory("tako-repo").toRealPath();
    git(repository, "init", "-q");
    git(repository, "config", "user.email", "test@example.com");
    git(repository, "config", "user.name", "Test");
    git(repository, "config", "core.autocrlf", "false");
    return repository;
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
