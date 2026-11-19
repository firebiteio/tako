package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;

import io.firebite.tako.agent.AgentEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TreeSnapshotTest {
  private static final Map<String, String> ENVIRONMENT = System.getenv();

  @Test
  public void findsWhatChangedSinceWithoutIgnoredFilesOrTouchingTheIndex() throws Exception {
    Path repository = Files.createTempDirectory("tako-snapshot").toRealPath();
    git(repository, "init", "-q");
    git(repository, "config", "user.email", "test@example.com");
    git(repository, "config", "user.name", "Test");
    git(repository, "config", "core.autocrlf", "false");
    Files.writeString(repository.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
    Files.writeString(repository.resolve("gone.txt"), "x\ny\n", StandardCharsets.UTF_8);
    Files.writeString(repository.resolve(".gitignore"), "build/\n", StandardCharsets.UTF_8);
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "base");
    Files.writeString(repository.resolve("untracked.txt"), "before\n", StandardCharsets.UTF_8);

    try (TreeSnapshot snapshot = TreeSnapshot.take(repository, ENVIRONMENT)) {
      assertEquals(List.of(), snapshot.changes());
      Files.writeString(repository.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
      Files.delete(repository.resolve("gone.txt"));
      Files.writeString(repository.resolve("untracked.txt"), "after\n", StandardCharsets.UTF_8);
      Files.createDirectories(repository.resolve("sub"));
      Files.writeString(repository.resolve("sub/new.txt"), "new\n", StandardCharsets.UTF_8);
      Files.createDirectories(repository.resolve("build"));
      Files.writeString(repository.resolve("build/out.txt"), "ignored\n", StandardCharsets.UTF_8);

      assertEquals(List.of(new AgentEvent.Change(repository.resolve("a.txt").toString(), 1, 0, false),
        new AgentEvent.Change(repository.resolve("gone.txt").toString(), 0, 2, false),
        new AgentEvent.Change(repository.resolve("sub/new.txt").toString(), 1, 0, true),
        new AgentEvent.Change(repository.resolve("untracked.txt").toString(), 1, 1, false)), snapshot.changes());
      assertEquals("before\n", snapshot.original(repository.resolve("untracked.txt")));
      assertEquals("x\ny\n", snapshot.original(repository.resolve("gone.txt")));
    }
    assertEquals(" M a.txt\n D gone.txt\n?? sub/\n?? untracked.txt\n", git(repository, "status", "--porcelain"));
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
