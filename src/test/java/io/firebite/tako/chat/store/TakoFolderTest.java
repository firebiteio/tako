package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TakoFolderTest {

  @Test
  public void previousFolderIsTheNewestEarlierVersionOfTheSameProduct() throws Exception {
    Path root = Files.createTempDirectory("jetbrains");
    for (String name : List.of("IntelliJIdea2025.3", "IntelliJIdea2026.1", "IntelliJIdea2026.2", "IntelliJIdea2026.4",
      "Rider2026.2")) {
      Files.createDirectories(root.resolve(name).resolve("tako"));
    }
    Files.createDirectories(root.resolve("IntelliJIdea2026.10"));

    assertEquals(root.resolve("IntelliJIdea2026.2").resolve("tako"),
      TakoFolder.previousFolder(root.resolve("IntelliJIdea2026.3")));
    assertEquals(root.resolve("IntelliJIdea2026.4").resolve("tako"),
      TakoFolder.previousFolder(root.resolve("IntelliJIdea2026.10")));
    assertNull(TakoFolder.previousFolder(root.resolve("IntelliJIdea2025.3")));
    assertNull(TakoFolder.previousFolder(root.resolve("system")));
  }

  @Test
  public void importMergesThreadsTheNewVersionAlreadyHas() throws Exception {
    Path root = Files.createTempDirectory("jetbrains");
    Path oldFolder = root.resolve("IntelliJIdea2026.2").resolve("tako");
    Path newFolder = root.resolve("IntelliJIdea2026.3").resolve("tako");
    ThreadStore old = new ThreadStore(oldFolder.resolve("threads-1.json"));
    ThreadStore.Record oldThread = old.create(null, "Old", null, "claude");
    old.create(null, "Older", null, "claude");
    old.projects("D:/project", "project");
    old.addProject("D:/other", "other");
    Files.createDirectories(oldFolder.resolve("past"));
    Files.writeString(oldFolder.resolve("past").resolve(oldThread.id + ".json"), "[]", StandardCharsets.UTF_8);
    new ThreadStore(oldFolder.resolve("threads-2.json")).create(null, "Other project", null, "claude");

    ThreadStore current = new ThreadStore(newFolder.resolve("threads-1.json"));
    ThreadStore.Record newThread = current.create(null, "New", null, "claude");
    current.projects("d:\\project", "project");
    current.setActiveId(newThread.id);

    TakoFolder.prepare(newFolder);

    ThreadStore merged = new ThreadStore(newFolder.resolve("threads-1.json"));
    List<String> titles = new ArrayList<>();
    for (ThreadStore.Record record : merged.all()) {
      titles.add(record.title);
    }
    assertEquals(List.of("New", "Older", "Old"), titles);
    assertEquals(newThread.id, merged.activeId());
    assertEquals(2, merged.projects("D:/project", "project").size());
    assertEquals("Other project", new ThreadStore(newFolder.resolve("threads-2.json")).all().getFirst().title);
    assertTrue(Files.isRegularFile(newFolder.resolve("past").resolve(oldThread.id + ".json")));
    assertTrue(Files.isRegularFile(newFolder.resolve(TakoFolder.MARKER)));  }

  @Test
  public void importRunsOncePerVersion() throws Exception {
    Path root = Files.createTempDirectory("jetbrains");
    Path oldFolder = root.resolve("IntelliJIdea2026.2").resolve("tako");
    Path newFolder = root.resolve("IntelliJIdea2026.3").resolve("tako");
    new ThreadStore(oldFolder.resolve("threads-1.json")).create(null, "Old", null, "claude");
    TakoFolder.prepare(newFolder);
    ThreadStore current = new ThreadStore(newFolder.resolve("threads-1.json"));
    current.remove(current.all().getFirst().id);

    TakoFolder.prepare(newFolder);

    assertEquals(0, new ThreadStore(newFolder.resolve("threads-1.json")).all().size());
  }
}
