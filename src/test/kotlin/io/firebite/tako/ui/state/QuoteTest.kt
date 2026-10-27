package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteTest {
  @Test
  fun quotesEachLineAndLeavesAnEmptyLineForTheReply() {
    assertEquals("> first\n> second\n\n", withQuote("", "first\nsecond"))
  }

  @Test
  fun blankLinesStayInsideTheQuote() {
    assertEquals("> a\n>\n> b\n\n", withQuote("", "a\n\nb"))
  }

  @Test
  fun dropsSurroundingLineBreaksAndWindowsLineEnds() {
    assertEquals("> a\n> b\n\n", withQuote("", "\r\na\r\nb\r\n"))
  }

  @Test
  fun goesAfterTypedTextWithABlankLineBetween() {
    assertEquals("note\n\n> a\n\n", withQuote("note\n", "a"))
  }

  @Test
  fun followsAnEarlierQuote() {
    assertEquals("> a\n\n> b\n\n", withQuote(withQuote("", "a"), "b"))
  }
}
