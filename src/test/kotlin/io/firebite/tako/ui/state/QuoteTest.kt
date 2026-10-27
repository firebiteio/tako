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

  @Test
  fun closesACodeFenceLeftOpen() {
    assertEquals("```kotlin\nval a = 1\n```\n\n> b\n\n", withQuote("```kotlin\nval a = 1\n", "b"))
  }

  @Test
  fun closesWithTheOpeningFencesMarkAndLength() {
    assertEquals("~~~~\n```\n~~~~\n\n> b\n\n", withQuote("~~~~\n```", "b"))
  }

  @Test
  fun leavesClosedFencesAlone() {
    assertEquals("```\na\n```\n\n> b\n\n", withQuote("```\na\n```", "b"))
  }

  @Test
  fun inlineCodeOpensNoFence() {
    assertEquals("``` a ``` b\n\n> c\n\n", withQuote("``` a ``` b", "c"))
  }
}
