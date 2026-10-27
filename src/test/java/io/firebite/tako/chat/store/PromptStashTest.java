package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class PromptStashTest {
  private static final ImageAttachment IMAGE = new ImageAttachment("image/png", "iVBORw0KGgo=", "screenshot.png");

  private static ChatMessage.Submit prompt(String text) {
    return new ChatMessage.Submit(text, List.of(), List.of());
  }

  private static Path newFile() throws IOException {
    return Files.createTempDirectory("stash").resolve("stash.json");
  }

  @Test
  public void draftsSurviveAReloadNewestFirst() throws Exception {
    Path file = newFile();
    PromptStash stash = new PromptStash(file);
    ContextItem selection = ContextItem.create(ContextItem.Kind.SELECTION, "Parser.java:10-12", "int x;");
    stash.push(new ChatMessage.Submit("  indented()\nthen the tests", List.of(selection), List.of(IMAGE)));
    stash.push(prompt("Second idea"));

    List<PromptStash.Entry> entries = new PromptStash(file).entries();

    assertEquals(2, entries.size());
    assertEquals("Second idea", entries.get(0).prompt.text());
    ChatMessage.Submit first = entries.get(1).prompt;
    assertEquals("  indented()\nthen the tests", first.text());
    assertEquals(List.of(selection), first.context());
    assertEquals(List.of(IMAGE), first.images());
  }

  @Test
  public void takenDraftIsGoneAfterReload() throws Exception {
    Path file = newFile();
    PromptStash stash = new PromptStash(file);
    PromptStash.Entry entry = stash.push(prompt("Later"));

    assertEquals("Later", stash.take(entry.id).prompt.text());
    assertNull(stash.take(entry.id));
    assertTrue(new PromptStash(file).entries().isEmpty());
  }

  @Test
  public void pushingPastTheLimitDropsTheOldest() throws Exception {
    PromptStash stash = new PromptStash(newFile());
    for (int i = 0; i <= PromptStash.MAX_ENTRIES; i++) {
      stash.push(prompt("Draft " + i));
    }

    List<PromptStash.Entry> entries = stash.entries();

    assertEquals(PromptStash.MAX_ENTRIES, entries.size());
    assertEquals("Draft " + PromptStash.MAX_ENTRIES, entries.getFirst().prompt.text());
    assertEquals("Draft 1", entries.getLast().prompt.text());
  }

  @Test
  public void failedSaveLeavesTheStashAsBefore() throws Exception {
    Path blocker = Files.createTempFile("stash", ".blocker");
    PromptStash stash = new PromptStash(blocker.resolve("stash.json"));

    assertThrows(IOException.class, () -> stash.push(prompt("Lost?")));
    assertTrue(stash.entries().isEmpty());
  }

  @Test
  public void unreadableFileIsKeptAsideAndNotOverwritten() throws Exception {
    Path file = newFile();
    Files.writeString(file, "{not json", StandardCharsets.UTF_8);
    PromptStash stash = new PromptStash(file);

    Path setAside = stash.takeSetAside();

    assertNotNull(setAside);
    assertEquals("{not json", Files.readString(setAside, StandardCharsets.UTF_8));
    assertNull(stash.takeSetAside());
    assertTrue(stash.entries().isEmpty());
    stash.push(prompt("New"));
    assertEquals("{not json", Files.readString(setAside, StandardCharsets.UTF_8));
    assertEquals(1, new PromptStash(file).entries().size());
  }

  @Test
  public void entriesWithoutIdOrPromptAreDropped() throws Exception {
    Path file = newFile();
    Files.writeString(file, """
      {"entries": [null, {"id": null, "prompt": {"text": "x"}}, {"id": "b", "prompt": null},
        {"id": "a", "prompt": {"text": null, "context": [null], "images": null}}]}
      """, StandardCharsets.UTF_8);
    PromptStash stash = new PromptStash(file);

    List<PromptStash.Entry> entries = stash.entries();

    assertEquals(1, entries.size());
    assertEquals("", entries.getFirst().label());
    assertTrue(entries.getFirst().prompt.context().isEmpty());
    assertNull(stash.takeSetAside());
    assertNull(stash.take("missing"));
    assertFalse(stash.entries().isEmpty());
  }

  @Test
  public void labelIsTheFirstLineOrElseAnAttachment() throws Exception {
    PromptStash stash = new PromptStash(newFile());

    assertEquals("Fix the parser", stash.push(prompt("\n  Fix the parser  \nmore")).label());
    assertEquals("screenshot.png", stash.push(new ChatMessage.Submit("", List.of(), List.of(IMAGE))).label());
  }
}
