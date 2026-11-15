package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.context.ReviewComment;
import io.firebite.tako.ide.context.ReviewTarget;
import io.firebite.tako.ui.bridge.message.thread.Submit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PromptStashTest {
  private static final ImageAttachment IMAGE = new ImageAttachment("image/png", "iVBORw0KGgo=", "screenshot.png");

  private static Submit prompt(String text) {
    return new Submit(text, List.of(), List.of());
  }

  private static Path newFile() throws IOException {
    return Files.createTempDirectory("stash").resolve("stash.json");
  }

  private static PromptStash open(Path file) {
    return new PromptStash(file, file.resolveSibling("pasted"));
  }

  @Test
  public void pastedTextComesBackWhenItsFileWasDeletedOrReused() throws Exception {
    Path file = newFile();
    Path folder = file.resolveSibling("pasted");
    ContextItem deleted = IdeContext.file(PastedText.write(folder, "first paste"));
    ContextItem reused = IdeContext.file(PastedText.write(folder, "second paste"));
    ContextItem kept = IdeContext.file(PastedText.write(folder, "third paste"));
    PromptStash stash = open(file);
    PromptStash.Entry entry = stash.push(new Submit("Look", List.of(deleted, reused, kept), List.of()));
    Files.delete(PastedText.fileOf(deleted, folder));
    Files.writeString(PastedText.fileOf(reused, folder), "a newer paste", StandardCharsets.UTF_8);

    List<ContextItem> context = open(file).take(entry.id).prompt.context();

    assertEquals("first paste", Files.readString(PastedText.fileOf(context.get(0), folder), StandardCharsets.UTF_8));
    assertEquals("second paste", Files.readString(PastedText.fileOf(context.get(1), folder), StandardCharsets.UTF_8));
    assertEquals("a newer paste", Files.readString(PastedText.fileOf(reused, folder), StandardCharsets.UTF_8));
    assertEquals(kept, context.get(2));
  }

  @Test
  public void reviewCommentsComeBackFromTheStashWithTheirKind() throws Exception {
    Path file = newFile();
    ContextItem review = ReviewComment.item(ReviewTarget.turn("thread-1", "/repo/Ünï.java"),
      List.of(new ReviewComment.Line(0, 3, "int x = 1; // 😀")), "rename x", ReviewComment.note("a", false, null));
    assertNotNull(review);
    PromptStash.Entry entry = open(file).push(new Submit("", List.of(review), List.of()));
    String saved = Files.readString(file, StandardCharsets.UTF_8);

    List<ContextItem> context = open(file).take(entry.id).prompt.context();

    assertTrue(saved.contains("\"review\""));
    assertEquals(List.of(review), context);
    assertEquals(ContextItem.Kind.REVIEW, context.get(0).kind());
  }

  @Test
  public void pushAllKeepsTheLastAsNewestAndTellsHowManyFit() throws Exception {
    Path file = newFile();
    PromptStash stash = open(file);
    stash.push(prompt("older draft"));
    assertEquals(2, stash.pushAll(List.of(prompt("queued first"), prompt("queued second"))));
    List<PromptStash.Entry> entries = open(file).entries();
    assertEquals("queued second", entries.get(0).prompt.text());
    assertEquals("queued first", entries.get(1).prompt.text());
    assertEquals("older draft", entries.get(2).prompt.text());

    List<Submit> many = new ArrayList<>();
    for (int i = 0; i < PromptStash.MAX_ENTRIES + 5; i++) {
      many.add(prompt("queued " + i));
    }
    assertEquals(PromptStash.MAX_ENTRIES, stash.pushAll(many));
    assertEquals(PromptStash.MAX_ENTRIES, stash.entries().size());
    assertEquals("queued " + (PromptStash.MAX_ENTRIES + 4), stash.entries().getFirst().prompt.text());
    assertEquals(0, stash.pushAll(List.of()));
  }

  @Test
  public void removingADraftDeletesItsPastedFiles() throws Exception {
    Path file = newFile();
    Path folder = file.resolveSibling("pasted");
    ContextItem paste = IdeContext.file(PastedText.write(folder, "pasted"));
    ContextItem other = IdeContext.file(Files.writeString(file.resolveSibling("Parser.java"), "class Parser"));
    PromptStash stash = open(file);
    PromptStash.Entry entry = stash.push(new Submit("", List.of(paste, other), List.of()));

    stash.remove(entry.id);

    assertFalse(Files.exists(PastedText.fileOf(paste, folder)));
    assertTrue(Files.exists(file.resolveSibling("Parser.java")));
    assertTrue(open(file).entries().isEmpty());
  }

  @Test
  public void draftsSurviveAReloadNewestFirst() throws Exception {
    Path file = newFile();
    PromptStash stash = open(file);
    ContextItem selection = ContextItem.create(ContextItem.Kind.SELECTION, "Parser.java:10-12", "int x;");
    stash.push(new Submit("  indented()\nthen the tests", List.of(selection), List.of(IMAGE)));
    stash.push(prompt("Second idea"));

    List<PromptStash.Entry> entries = open(file).entries();

    assertEquals(2, entries.size());
    assertEquals("Second idea", entries.get(0).prompt.text());
    Submit first = entries.get(1).prompt;
    assertEquals("  indented()\nthen the tests", first.text());
    assertEquals(List.of(selection), first.context());
    assertEquals(List.of(IMAGE), first.images());
  }

  @Test
  public void takenDraftIsGoneAfterReload() throws Exception {
    Path file = newFile();
    PromptStash stash = open(file);
    PromptStash.Entry entry = stash.push(prompt("Later"));

    assertEquals("Later", stash.take(entry.id).prompt.text());
    assertNull(stash.take(entry.id));
    assertTrue(open(file).entries().isEmpty());
  }

  @Test
  public void pushingPastTheLimitDropsTheOldest() throws Exception {
    PromptStash stash = open(newFile());
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
    PromptStash stash = open(blocker.resolve("stash.json"));

    assertThrows(IOException.class, () -> stash.push(prompt("Lost?")));
    assertTrue(stash.entries().isEmpty());
  }

  @Test
  public void unreadableFileIsKeptAsideAndNotOverwritten() throws Exception {
    Path file = newFile();
    Files.writeString(file, "{not json", StandardCharsets.UTF_8);
    PromptStash stash = open(file);

    Path setAside = stash.takeSetAside();

    assertNotNull(setAside);
    assertEquals("{not json", Files.readString(setAside, StandardCharsets.UTF_8));
    assertNull(stash.takeSetAside());
    assertTrue(stash.entries().isEmpty());
    stash.push(prompt("New"));
    assertEquals("{not json", Files.readString(setAside, StandardCharsets.UTF_8));
    assertEquals(1, open(file).entries().size());
  }

  @Test
  public void entriesWithoutIdOrPromptAreDropped() throws Exception {
    Path file = newFile();
    Files.writeString(file, """
      {"entries": [null, {"id": null, "prompt": {"text": "x"}}, {"id": "b", "prompt": null},
        {"id": "a", "prompt": {"text": null, "context": [null], "images": null}}]}
      """, StandardCharsets.UTF_8);
    PromptStash stash = open(file);

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
    PromptStash stash = open(newFile());

    assertEquals("Fix the parser", stash.push(prompt("\n  Fix the parser  \nmore")).label());
    assertEquals("screenshot.png", stash.push(new Submit("", List.of(), List.of(IMAGE))).label());
  }
}
