package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.ide.project.GitInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class ThreadStoreTest {

  @Test
  public void threadsSurviveAReload() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record first = store.create(null, null, null, "claude");
    ThreadStore.Record second = store.create("6564ee31-b699-463d-b7d1-0d23e4ac251d", "Resumed", null, "codex");
    first.title = "Refactor the parser";
    first.archived = true;
    first.effort = "high";
    first.costUsd = 1.25;
    first.pullRequest = new GitInfo.PullRequest("#42", "Add threads", "https://github.com/o/r/pull/42", "open");
    store.setActiveId(second.id);
    store.save();

    ThreadStore reloaded = new ThreadStore(file);

    assertEquals(2, reloaded.all().size());
    assertEquals(second.id, reloaded.activeId());
    ThreadStore.Record restored = reloaded.find(first.id);
    assertEquals("Refactor the parser", restored.title);
    assertTrue(restored.archived);
    assertEquals("high", restored.effort);
    assertEquals(1.25, restored.costUsd, 1e-9);
    assertEquals("#42", restored.pullRequest.number());
    assertEquals(second.id, reloaded.findBySession("6564ee31-b699-463d-b7d1-0d23e4ac251d").id);
  }

  @Test
  public void removedThreadIsGoneAfterReload() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record record = store.create(null, null, null, "claude");
    store.remove(record.id);

    assertNull(new ThreadStore(file).find(record.id));
  }

  @Test
  public void ideProjectIsAlwaysTheFirstFolder() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    store.addProject("D:/shared-lib", "shared-lib");

    ThreadStore reloaded = new ThreadStore(file);
    java.util.List<ThreadStore.ProjectFolder> projects = reloaded.projects("D:/app", "app");

    assertEquals(2, projects.size());
    assertEquals("app", projects.get(0).name);
    assertEquals("shared-lib", projects.get(1).name);
    assertTrue(ThreadStore.samePath("D:\\shared-lib", reloaded.findProject("d:/shared-lib").path));
  }

  @Test
  public void benchesAndRunsSurviveAReload() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record record = store.create(null, "#1 · Fix login", null, "claude");
    record.bench = "b1";
    record.runIndex = 1;
    record.workDirectory = "D:/code/app-tako/fix-login-b1/1";
    ThreadStore.Bench bench = new ThreadStore.Bench();
    bench.id = "b1";
    bench.title = "Fix login";
    bench.baseCommit = "0123456789abcdef";
    ThreadStore.Run run = new ThreadStore.Run();
    run.thread = record.id;
    run.worktree = record.workDirectory;
    run.branch = "tako/fix-login-b1-1";
    bench.runs.add(run);
    store.addBench(bench);

    ThreadStore reloaded = new ThreadStore(file);

    ThreadStore.Bench restored = reloaded.findBench("b1");
    assertEquals("tako/fix-login-b1-1", restored.runs.getFirst().branch);
    assertEquals("0123456", restored.toItem().baseCommit());
    assertEquals("b1", reloaded.find(record.id).bench);
    assertEquals("D:/code/app-tako/fix-login-b1/1", reloaded.find(record.id).workDirectory);
    reloaded.removeBench("b1");
    assertNull(new ThreadStore(file).findBench("b1"));
  }

  @Test
  public void corruptFileStartsEmpty() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    Files.writeString(file, "{not json");

    assertTrue(new ThreadStore(file).all().isEmpty());
  }
}
