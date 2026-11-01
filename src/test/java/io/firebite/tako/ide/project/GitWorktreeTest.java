package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GitWorktreeTest {

  @Test
  public void slugKeepsLettersAndDigits() {
    assertEquals("skeletal-animation-gltf-2", GitWorktree.slug("  Skeletal animation (glTF 2)!"));
    assertEquals("thread", GitWorktree.slug("???"));
    assertEquals(40, GitWorktree.slug("a".repeat(60)).length());
  }

  @Test
  public void createsWorktreesNextToTheRepositoryOnNewBranches() throws Exception {
    Path root = Files.createTempDirectory("tako-worktree");
    Path repository = root.resolve("game");
    Files.createDirectories(repository);
    Map<String, String> environment = System.getenv();
    git(repository, environment, "init", "-q");
    git(repository, environment, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m",
      "start");

    GitWorktree.Worktree first = GitWorktree.create(repository, "Physics", environment);
    GitWorktree.Worktree second = GitWorktree.create(repository, "Physics", environment);

    assertNotNull(first);
    assertEquals(root.resolve("game.worktrees").resolve("physics").toRealPath(), first.folder().toRealPath());
    assertEquals("physics", first.branch());
    assertNotNull(second);
    assertEquals("physics-2", second.branch());
    assertTrue(Files.isDirectory(second.folder()));
    assertEquals(repository.toRealPath(), first.repository().toRealPath());

    GitWorktree.Worktree nested = GitWorktree.create(first.folder(), "Nested", environment);
    assertNotNull(nested);
    assertEquals("a worktree's worktree still belongs to the main checkout", repository.toRealPath(),
      nested.repository().toRealPath());
  }

  @Test
  public void noWorktreeOutsideARepository() throws Exception {
    assertNull(GitWorktree.create(Files.createTempDirectory("tako-no-git"), "x", System.getenv()));
  }

  @Test
  public void copiesTheIgnoredFilesWorktreeIncludeLists() throws Exception {
    Path repository = Files.createTempDirectory("tako-worktree").resolve("game");
    Files.createDirectories(repository);
    Map<String, String> environment = System.getenv();
    git(repository, environment, "init", "-q");
    git(repository, environment, "config", "core.autocrlf", "false");
    Files.writeString(repository.resolve(".gitignore"), "*.env\n*.log\n.claude/\n");
    Files.writeString(repository.resolve("tracked.env"), "committed");
    git(repository, environment, "add", ".");
    git(repository, environment, "add", "-f", "tracked.env");
    git(repository, environment, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    Files.writeString(repository.resolve(".worktreeinclude"), "# local config\n*.env\n.claude/settings.local.json\n");
    Files.writeString(repository.resolve(".env"), "secret");
    Files.writeString(repository.resolve("tracked.env"), "changed");
    Files.writeString(repository.resolve("debug.log"), "log");
    Files.createDirectories(repository.resolve(".claude"));
    Files.writeString(repository.resolve(".claude/settings.local.json"), "{}");
    Files.writeString(repository.resolve(".claude/other.json"), "{}");

    GitWorktree.Worktree worktree = GitWorktree.create(repository, "Include", environment);

    assertNotNull(worktree);
    assertEquals("secret", Files.readString(worktree.folder().resolve(".env")));
    assertEquals("committed", Files.readString(worktree.folder().resolve("tracked.env")));
    assertFalse(Files.exists(worktree.folder().resolve("debug.log")));
    assertEquals("{}", Files.readString(worktree.folder().resolve(".claude/settings.local.json")));
    assertFalse(Files.exists(worktree.folder().resolve(".claude/other.json")));
  }

  @Test
  public void copiesNothingWithoutWorktreeInclude() throws Exception {
    Path repository = Files.createTempDirectory("tako-worktree").resolve("game");
    Files.createDirectories(repository);
    Map<String, String> environment = System.getenv();
    git(repository, environment, "init", "-q");
    Files.writeString(repository.resolve(".gitignore"), "*.env\n");
    git(repository, environment, "add", ".");
    git(repository, environment, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    Files.writeString(repository.resolve(".env"), "secret");

    GitWorktree.Worktree worktree = GitWorktree.create(repository, "Plain", environment);

    assertNotNull(worktree);
    assertFalse(Files.exists(worktree.folder().resolve(".env")));
  }

  private static void git(Path directory, Map<String, String> environment, String... arguments) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    assertNotNull(GitInfo.run(directory, environment, command, 30));
  }
}
