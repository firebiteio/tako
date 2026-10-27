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
}
