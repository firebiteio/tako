package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
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
    assertEquals("tako/physics", first.branch());
    assertNotNull(second);
    assertEquals("tako/physics-2", second.branch());
    assertTrue(Files.isDirectory(second.folder()));
  }

  @Test
  public void noWorktreeOutsideARepository() throws Exception {
    assertNull(GitWorktree.create(Files.createTempDirectory("tako-no-git"), "x", System.getenv()));
  }

  private static void git(Path directory, Map<String, String> environment, String... arguments) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    assertNotNull(GitInfo.run(directory, environment, command, 30));
  }
}
