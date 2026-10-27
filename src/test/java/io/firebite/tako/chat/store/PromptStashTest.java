package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.context.ContextItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class PromptStashTest {

  @Test
  public void draftsSurviveAReloadNewestFirst() throws Exception {
    Path file = Files.createTempDirectory("stash").resolve("stash.json");
    PromptStash stash = new PromptStash(file);
    ContextItem selection = ContextItem.create(ContextItem.Kind.SELECTION, "Parser.java:10-12", "int x;");
    ImageAttachment image = new ImageAttachment("image/png", "iVBORw0KGgo=", "screenshot.png");
    stash.push("Fix the parser\nthen the tests", List.of(selection), List.of(image));
    stash.push("Second idea", List.of(), List.of());

    List<PromptStash.Entry> entries = new PromptStash(file).entries();

    assertEquals(2, entries.size());
    assertEquals("Second idea", entries.get(0).text);
    PromptStash.Entry first = entries.get(1);
    assertEquals("Fix the parser\nthen the tests", first.text);
    assertEquals(List.of(selection), first.context);
    assertEquals(List.of(image), first.images);
  }

  @Test
  public void takenDraftIsGoneAfterReload() throws Exception {
    Path file = Files.createTempDirectory("stash").resolve("stash.json");
    PromptStash stash = new PromptStash(file);
    PromptStash.Entry entry = stash.push("Later", List.of(), List.of());

    assertEquals("Later", stash.take(entry.id).text);
    assertNull(stash.take(entry.id));
    assertTrue(new PromptStash(file).entries().isEmpty());
  }

  @Test
  public void labelIsTheFirstLineOrElseAnAttachment() throws Exception {
    PromptStash stash = new PromptStash(Files.createTempDirectory("stash").resolve("stash.json"));
    ImageAttachment image = new ImageAttachment("image/png", "iVBORw0KGgo=", "screenshot.png");

    assertEquals("Fix the parser", stash.push("\n  Fix the parser  \nmore", List.of(), List.of()).label());
    assertEquals("screenshot.png", stash.push("", List.of(), List.of(image)).label());
  }
}
