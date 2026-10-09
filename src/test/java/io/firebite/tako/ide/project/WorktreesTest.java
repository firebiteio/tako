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
