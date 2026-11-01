package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  public void pendingSetupSurvivesAReloadUntilCleared() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record record = store.create(null, null, null, "claude");
    ThreadStore.Record other = store.create(null, null, null, "claude");
    Path repository = Path.of("repo ü");
    Path worktree = Path.of("repo-tako", "fix", "1");
    record.pendingSetup = ThreadStore.PendingSetup.of(repository, worktree);
    store.save();

    ThreadStore reloaded = new ThreadStore(file);

    ThreadStore.PendingSetup setup = reloaded.find(record.id).pendingSetup;
    assertEquals(repository.toString(), setup.repository);
    assertEquals(worktree.toString(), setup.worktree);
    assertNull(reloaded.find(other.id).pendingSetup);
    reloaded.find(record.id).pendingSetup = null;
    reloaded.save();
    assertNull(new ThreadStore(file).find(record.id).pendingSetup);
  }

  @Test
  public void aScheduledResumeSurvivesARestart() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record record = store.create(null, null, null, "claude");
    ThreadStore.Record plain = store.create(null, null, null, "codex");
    ThreadStore.LimitStop stop = new ThreadStore.LimitStop();
    stop.label = "7d Opus";
    stop.resetsAt = 1_900_000_000_000L;
    stop.hitAt = 1_899_990_000_000L;
    stop.scheduled = true;
    stop.prompt = "Weiter 🐙";
    record.limit = stop;
    store.save();

    ThreadStore reloaded = new ThreadStore(file);

    ThreadStore.LimitStop restored = reloaded.find(record.id).limit;
    assertEquals("7d Opus", restored.label);
    assertEquals(1_900_000_000_000L, restored.resetsAt);
    assertEquals(1_899_990_000_000L, restored.hitAt);
    assertTrue(restored.scheduled);
    assertEquals("Weiter 🐙", restored.prompt);
    assertNull(reloaded.find(plain.id).limit);
    assertEquals(new ChatMessage.LimitItem("7d Opus", 1_900_000_000_000L, true, "Weiter 🐙",
      1_900_000_000_000L + ThreadStore.LimitStop.GRACE_MILLIS), restored.item("Continue"));
    restored.prompt = null;
    assertEquals("Continue", restored.item("Continue").prompt());
  }

  @Test
  public void threadsFromBeforeLimitsLoadWithoutOne() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    Files.writeString(file, "{\"threads\":[{\"id\":\"a\",\"title\":\"Old\",\"agent\":\"claude\"}]}");

    assertNull(new ThreadStore(file).find("a").limit);
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
  public void subThreadKeepsItsParentForTheList() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record parent = store.create(null, null, null, "claude");
    ThreadStore.Record child = store.create(null, "Spawned", null, "claude");
    child.parentId = parent.id;
    store.save();

    ThreadStore reloaded = new ThreadStore(file);

    assertEquals(parent.id, reloaded.find(child.id).toItem().parentId());
    assertNull(reloaded.find(parent.id).toItem().parentId());
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
    ThreadStore.Record record = store.create(null, "Fix login", null, "claude");
    record.workPath = "D:/code/app-tako/fix-login-b1/1";
    ThreadStore.Bench bench = new ThreadStore.Bench();
    bench.id = "b1";
    bench.title = "Fix login";
    bench.baseCommit = "0123456789abcdef";
    ThreadStore.Run run = new ThreadStore.Run();
    run.thread = record.id;
    run.worktree = record.workPath;
    run.branch = "fix-login-b1-1";
    bench.runs.add(run);
    store.addBench(bench);

    ThreadStore reloaded = new ThreadStore(file);

    ThreadStore.Bench restored = reloaded.findBench("b1");
    assertEquals("fix-login-b1-1", restored.runs.getFirst().branch);
    assertEquals("0123456", restored.toItem().baseCommit());
    assertEquals(List.of(record.id), restored.toItem().threads());
    assertEquals("D:/code/app-tako/fix-login-b1/1", reloaded.find(record.id).workPath);
    reloaded.removeBench("b1");
    assertNull(new ThreadStore(file).findBench("b1"));
  }

  @Test
  public void listStateSurvivesAReload() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    ThreadStore store = new ThreadStore(file);
    ThreadStore.Record record = store.create(null, "Pinned", null, "claude");
    record.pinned = true;
    record.unread = true;
    record.snoozedUntil = 1_800_000_000_000L;
    record.seenAt = 1_700_000_000_000L;
    record.settledPullRequest = "https://github.com/o/r/pull/7";
    store.save();

    ThreadStore.Record restored = new ThreadStore(file).find(record.id);

    assertTrue(restored.pinned);
    assertTrue(restored.unread);
    assertEquals(1_800_000_000_000L, restored.snoozedUntil);
    assertEquals(1_700_000_000_000L, restored.seenAt);
    assertEquals("https://github.com/o/r/pull/7", restored.settledPullRequest);
    ChatMessage.ThreadItem item = restored.toItem();
    assertTrue(item.pinned());
    assertTrue(item.unread());
    assertEquals(1_800_000_000_000L, item.snoozedUntil());
  }

  @Test
  public void fileFromBeforeListStateLoadsWithDefaults() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    Files.writeString(file, "{\"threads\":[{\"id\":\"a\",\"title\":\"Old\",\"updatedAt\":5}]}");

    ThreadStore.Record record = new ThreadStore(file).find("a");

    assertFalse(record.pinned);
    assertFalse(record.unread);
    assertEquals(0, record.snoozedUntil);
    assertEquals(0, record.seenAt);
    assertNull(record.settledPullRequest);
  }

  @Test
  public void snoozedParentHidesItsSubThreadsAtAnyDepth() throws Exception {
    ThreadStore store = new ThreadStore(Files.createTempDirectory("threads").resolve("threads.json"));
    ThreadStore.Record parent = store.create(null, "Parent", null, "claude");
    ThreadStore.Record child = store.create(null, "Child", null, "claude");
    ThreadStore.Record grandchild = store.create(null, "Grandchild", null, "claude");
    child.parentId = parent.id;
    grandchild.parentId = child.id;
    parent.snoozedUntil = 100;

    assertTrue(store.isSnoozeHidden(parent));
    assertTrue(store.isSnoozeHidden(grandchild));
    parent.archived = true;
    assertFalse(store.isSnoozeHidden(grandchild));
    assertFalse(store.isSnoozeHidden(parent));
  }

  @Test
  public void parentCycleEndsTheWalk() throws Exception {
    ThreadStore store = new ThreadStore(Files.createTempDirectory("threads").resolve("threads.json"));
    ThreadStore.Record first = store.create(null, "First", null, "claude");
    ThreadStore.Record second = store.create(null, "Second", null, "claude");
    first.parentId = second.id;
    second.parentId = first.id;

    assertFalse(store.isSnoozeHidden(first));
    assertTrue(store.wakeWithAncestors(first).isEmpty());
    second.snoozedUntil = 100;
    assertTrue(store.isSnoozeHidden(first));
    assertEquals(List.of(second), store.wakeWithAncestors(first));
    assertEquals(0, second.snoozedUntil);
  }

  @Test
  public void wakingASubThreadWakesItsSnoozedParentsOnly() throws Exception {
    ThreadStore store = new ThreadStore(Files.createTempDirectory("threads").resolve("threads.json"));
    ThreadStore.Record grandparent = store.create(null, "Grandparent", null, "claude");
    ThreadStore.Record parent = store.create(null, "Parent", null, "claude");
    ThreadStore.Record child = store.create(null, "Child", null, "claude");
    ThreadStore.Record sibling = store.create(null, "Sibling", null, "claude");
    parent.parentId = grandparent.id;
    child.parentId = parent.id;
    sibling.parentId = parent.id;
    grandparent.snoozedUntil = 100;
    sibling.snoozedUntil = 200;

    assertEquals(List.of(grandparent), store.wakeWithAncestors(child));

    assertFalse(store.isSnoozeHidden(child));
    assertEquals(200, sibling.snoozedUntil);
  }

  @Test
  public void benchRunsAreKnown() throws Exception {
    ThreadStore store = new ThreadStore(Files.createTempDirectory("threads").resolve("threads.json"));
    ThreadStore.Record record = store.create(null, "Run", null, "claude");
    ThreadStore.Bench bench = new ThreadStore.Bench();
    bench.id = "b";
    ThreadStore.Run run = new ThreadStore.Run();
    run.thread = record.id;
    bench.runs.add(run);
    store.addBench(bench);

    assertTrue(store.isRun(record.id));
    assertFalse(store.isRun("other"));
  }

  @Test
  public void corruptFileStartsEmpty() throws Exception {
    Path file = Files.createTempDirectory("threads").resolve("threads.json");
    Files.writeString(file, "{not json");

    assertTrue(new ThreadStore(file).all().isEmpty());
  }
}
