package io.firebite.tako.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class PromptTextTest {

  @Test
  public void whitespaceAndSlashCommandArgumentsDoNotCount() {
    assertEquals("fix the bug", PromptText.normalize("  fix\tthe\n\n bug \n"));
    assertEquals("/review", PromptText.normalize("/review  src/Main.java"));
    assertEquals("", PromptText.normalize(" \n "));
    assertEquals("ünï 🐙 日本", PromptText.normalize("ünï  🐙\n日本"));
  }

  @Test
  public void prefixesCompareInOrder() {
    List<String> session = List.of("yes", "go on", "yes");

    assertTrue(PromptText.startsWith(session, List.of("yes", "go  on")));
    assertTrue(PromptText.startsWith(session, List.of()));
    assertFalse(PromptText.startsWith(session, List.of("go on", "yes")));
    assertFalse(PromptText.startsWith(session, List.of("yes", "go on", "yes", "more")));
  }

  @Test
  public void hashesFollowTheNormalizedText() {
    assertEquals(PromptText.hash("a  b"), PromptText.hash("a b\n"));
    assertNotEquals(PromptText.hash("a b"), PromptText.hash("a c"));
    assertEquals(64, PromptText.hash("").length());
  }
}
