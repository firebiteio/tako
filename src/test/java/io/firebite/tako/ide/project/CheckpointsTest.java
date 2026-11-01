package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CheckpointsTest {
  private static final Map<String, String> ENVIRONMENT = System.getenv();
  private static final String THREAD = "3f2a6c1e-0000-4000-8000-000000000001";

  @Test
  public void restorePutsBackChangedDeletedAndUntrackedFilesAndRemovesNewOnes() throws Exception {
    Path repository = repository();
    write(repository, ".gitignore", "*.log\n");
    write(repository, "kept.txt", "same");
    write(repository, "edited.txt", "before");
    write(repository, "deleted.txt", "gone later");
    commit(repository);
    write(repository, "untracked.txt", "not committed");
    write(repository, "debug.log", "ignored before");
    String staged = git(repository, "diff", "--cached", "--name-only");

    Checkpoints.capture(repository, THREAD, 0, "change things", ENVIRONMENT);
    write(repository, "edited.txt", "after");
    Files.delete(repository.resolve("deleted.txt"));
    Files.delete(repository.resolve("untracked.txt"));
    write(repository, "src/new/Added.java", "class Added {}");
    write(repository, "debug.log", "ignored after");
    String commit = Checkpoints.find(repository, THREAD, 0, "change things", ENVIRONMENT);
    assertNotNull(commit);

    List<Path> changed = Checkpoints.restore(repository, THREAD, commit, ENVIRONMENT);

    assertEquals("before", read(repository, "edited.txt"));
    assertEquals("gone later", read(repository, "deleted.txt"));
    assertEquals("not committed", read(repository, "untracked.txt"));
    assertEquals("same", read(repository, "kept.txt"));
    assertFalse(Files.exists(repository.resolve("src")));
    assertEquals("ignored files are left alone", "ignored after", read(repository, "debug.log"));
    assertEquals(4, changed.size());
    assertTrue(changed.contains(repository.resolve("src/new/Added.java")));
    assertEquals("the real index is untouched", staged, git(repository, "diff", "--cached", "--name-only"));
    assertFalse(git(repository, "rev-parse", "--verify", "refs/tako/backup/" + THREAD).isBlank());
  }

  @Test
  public void aFileThatOnlyLookedAddedBecauseTheIgnoreRulesChangedStays() throws Exception {
    Path repository = repository();
    write(repository, ".gitignore", ".env\n");
    commit(repository);
    write(repository, ".env", "SECRET=1");
    Checkpoints.capture(repository, THREAD, 0, "p", ENVIRONMENT);
    write(repository, ".gitignore", "");
    write(repository, "made.txt", "by the agent");

    Checkpoints.restore(repository, THREAD, Checkpoints.find(repository, THREAD, 0, "p", ENVIRONMENT), ENVIRONMENT);

    assertEquals(".env\n", read(repository, ".gitignore"));
    assertEquals("SECRET=1", read(repository, ".env"));
    assertFalse(Files.exists(repository.resolve("made.txt")));
  }

  @Test
  public void restoreWithNothingChangedTouchesNothing() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "a");
    commit(repository);
    Checkpoints.capture(repository, THREAD, 0, "p", ENVIRONMENT);

    String commit = Checkpoints.find(repository, THREAD, 0, "p", ENVIRONMENT);
    assertEquals(List.of(), Checkpoints.restore(repository, THREAD, commit, ENVIRONMENT));
    assertEquals("a", read(repository, "a.txt"));
  }

  @Test
  public void oddFileNamesSurviveTheRoundTrip() throws Exception {
    Path repository = repository();
    write(repository, "größe ü.txt", "eins");
    write(repository, "with space/[x].txt", "zwei");
    write(repository, "日本語.md", "drei");
    commit(repository);
    Checkpoints.capture(repository, THREAD, 2, "ünïcödé 🐙", ENVIRONMENT);
    write(repository, "größe ü.txt", "changed");
    Files.delete(repository.resolve("with space/[x].txt"));
    write(repository, "日本語.md", "changed");
    write(repository, "_neu_.txt", "added");

    String commit = Checkpoints.find(repository, THREAD, 2, "ünïcödé 🐙", ENVIRONMENT);
    Checkpoints.restore(repository, THREAD, commit, ENVIRONMENT);

    assertEquals("eins", read(repository, "größe ü.txt"));
    assertEquals("zwei", read(repository, "with space/[x].txt"));
    assertEquals("drei", read(repository, "日本語.md"));
    assertFalse(Files.exists(repository.resolve("_neu_.txt")));
  }

  @Test
  public void aSubfolderRestoresOnlyItself() throws Exception {
    Path repository = repository();
    write(repository, "app/a.txt", "a");
    write(repository, "other/b.txt", "b");
    commit(repository);
    Path app = repository.resolve("app");
    Checkpoints.capture(app, THREAD, 0, "p", ENVIRONMENT);
    write(repository, "app/a.txt", "changed");
    write(repository, "other/b.txt", "changed");

    Checkpoints.restore(app, THREAD, Checkpoints.find(app, THREAD, 0, "p", ENVIRONMENT), ENVIRONMENT);

    assertEquals("a", read(repository, "app/a.txt"));
    assertEquals("changed", read(repository, "other/b.txt"));
  }

  @Test
  public void aRepositoryWithoutCommitsOrIndexWorks() throws Exception {
    Path repository = repository();
    assertFalse(Files.exists(repository.resolve(".git/index")));
    write(repository, "first.txt", "1");

    Checkpoints.capture(repository, THREAD, 0, "start", ENVIRONMENT);
    write(repository, "first.txt", "2");
    write(repository, "second.txt", "new");
    Checkpoints.restore(repository, THREAD, Checkpoints.find(repository, THREAD, 0, "start", ENVIRONMENT),
      ENVIRONMENT);

    assertEquals("1", read(repository, "first.txt"));
    assertFalse(Files.exists(repository.resolve("second.txt")));
    assertFalse("no index appeared", Files.exists(repository.resolve(".git/index")));
  }

  @Test
  public void findMatchesOnlyTheSamePrompt() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "a");
    commit(repository);
    Checkpoints.capture(repository, THREAD, 3, "Fix  the\n bug ", ENVIRONMENT);

    assertNotNull(Checkpoints.find(repository, THREAD, 3, "Fix the bug", ENVIRONMENT));
    assertNull(Checkpoints.find(repository, THREAD, 3, "Fix the other bug", ENVIRONMENT));
    assertNull(Checkpoints.find(repository, THREAD, 4, "Fix the bug", ENVIRONMENT));
    assertNull(Checkpoints.find(repository, "another-thread", 3, "Fix the bug", ENVIRONMENT));

    Checkpoints.capture(repository, THREAD, 3, "", ENVIRONMENT);
    assertNull("a newer capture replaces the number", Checkpoints.find(repository, THREAD, 3, "Fix the bug",
      ENVIRONMENT));
    assertNotNull("an image-only prompt has empty text", Checkpoints.find(repository, THREAD, 3, "", ENVIRONMENT));
  }

  @Test
  public void withoutARepositoryThereAreNoCheckpoints() throws Exception {
    Path folder = Files.createTempDirectory("tako-plain").toRealPath();
    write(folder, "a.txt", "a");

    assertThrows(Worktrees.GitException.class, () -> Checkpoints.capture(folder, THREAD, 0, "p", ENVIRONMENT));
    assertNull(Checkpoints.find(folder, THREAD, 0, "p", ENVIRONMENT));
    Checkpoints.forget(folder, THREAD, 0, ENVIRONMENT);
    Checkpoints.forget(folder.resolve("missing"), THREAD, 0, ENVIRONMENT);
  }

  @Test
  public void forgetDeletesFromTheGivenNumberOn() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "a");
    commit(repository);
    for (int i = 0; i < 12; i++) {
      Checkpoints.capture(repository, THREAD, i, "p" + i, ENVIRONMENT);
    }
    Checkpoints.capture(repository, "other", 5, "p5", ENVIRONMENT);

    Checkpoints.forget(repository, THREAD, 2, ENVIRONMENT);

    assertNotNull(Checkpoints.find(repository, THREAD, 0, "p0", ENVIRONMENT));
    assertNotNull(Checkpoints.find(repository, THREAD, 1, "p1", ENVIRONMENT));
    assertNull(Checkpoints.find(repository, THREAD, 2, "p2", ENVIRONMENT));
    assertNull(Checkpoints.find(repository, THREAD, 11, "p11", ENVIRONMENT));
    assertNotNull("other threads keep theirs", Checkpoints.find(repository, "other", 5, "p5", ENVIRONMENT));

    Checkpoints.restore(repository, THREAD, Checkpoints.find(repository, THREAD, 0, "p0", ENVIRONMENT), ENVIRONMENT);
    Checkpoints.forget(repository, THREAD, 0, ENVIRONMENT);
    assertTrue(git(repository, "for-each-ref", "refs/tako/checkpoints/" + THREAD + "/").isBlank());
    assertTrue(git(repository, "for-each-ref", "refs/tako/backup/" + THREAD).isBlank());
  }

  private static Path repository() throws Exception {
    Path repository = Files.createTempDirectory("tako-checkpoints").toRealPath();
    git(repository, "init", "-q");
    git(repository, "config", "user.email", "test@example.com");
    git(repository, "config", "user.name", "Test");
    git(repository, "config", "core.autocrlf", "false");
    return repository;
  }

  private static void commit(Path repository) throws Exception {
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "base");
  }

  private static void write(Path folder, String path, String content) throws Exception {
    Path file = folder.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static String read(Path folder, String path) throws Exception {
    return Files.readString(folder.resolve(path), StandardCharsets.UTF_8);
  }

  private static String git(Path directory, String... arguments) throws Exception {
    return Worktrees.git(directory, ENVIRONMENT, arguments);
  }
}
