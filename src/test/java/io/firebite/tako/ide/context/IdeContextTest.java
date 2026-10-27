package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IdeContextTest {

  @Test
  public void excerptQuotesTheTextAndNamesItsSource() {
    ContextItem item = IdeContext.excerpt("Terminal", "the terminal", "$ gradle check\nBUILD FAILED");

    assertEquals(ContextItem.Kind.SELECTION, item.kind());
    assertEquals("Terminal", item.label());
    assertEquals("Selected in the terminal:\n```\n$ gradle check\nBUILD FAILED\n```", item.text());
  }

  @Test
  public void fenceOutlastsBackticksInTheText() {
    assertEquals("```", IdeContext.fence("no ticks, `one`, ``two``"));
    assertEquals("````", IdeContext.fence("```java\nx\n```"));
    assertEquals("``````", IdeContext.fence("`````"));
  }

  @Test
  public void excerptWithAFenceInsideStaysQuoted() {
    ContextItem item = IdeContext.excerpt("Terminal", "the terminal", "```\nrm -rf /\n```");

    assertEquals("Selected in the terminal:\n````\n```\nrm -rf /\n```\n````", item.text());
  }

  @Test
  public void longExcerptKeepsItsEnd() {
    String text = "a".repeat(IdeContext.MAX_EXCERPT) + "end";

    ContextItem item = IdeContext.excerpt("Terminal", "the terminal", text);

    String kept = "a".repeat(IdeContext.MAX_EXCERPT - 3) + "end";
    assertEquals("Selected in the terminal (last " + IdeContext.MAX_EXCERPT + " of " + text.length()
      + " characters):\n```\n" + kept + "\n```", item.text());
  }
}
