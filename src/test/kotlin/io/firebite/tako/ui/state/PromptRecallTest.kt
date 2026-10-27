package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptRecallTest {
  private val prompts = listOf("first", "second\nline", "third")

  private val recall = PromptRecall()

  private fun up(text: String, caret: Int = text.length): String? = this.recall.step(this.prompts, text, caret, true)

  private fun down(text: String, caret: Int = text.length): String? = this.recall.step(this.prompts, text, caret, false)

  @Test
  fun upWalksBackAndDownComesForwardToAnEmptyComposer() {
    assertEquals("third", this.up(""))
    assertEquals("second\nline", this.up("third", 0))
    assertEquals("first", this.up("second\nline", 0))
    assertNull(this.up("first", 0))
    assertEquals("second\nline", this.down("first"))
    assertEquals("third", this.down("second\nline"))
    assertEquals("", this.down("third"))
    assertNull(this.down(""))
  }

  @Test
  fun aDraftKeepsTheArrows() {
    assertNull(this.up("draft"))
    assertNull(this.down("draft"))
  }

  @Test
  fun anEditedPromptBecomesADraft() {
    assertEquals("third", this.up(""))

    assertNull(this.up("third!"))
    assertNull(this.down("third!"))
  }

  @Test
  fun arrowsMoveTheCaretAwayFromTheEdgeLines() {
    assertEquals("third", this.up(""))
    assertEquals("second\nline", this.up("third"))

    assertNull(this.up("second\nline"))
    assertNull(this.down("second\nline", 0))
    assertEquals("first", this.up("second\nline", 3))
  }

  @Test
  fun anEmptyThreadHasNothingToRecall() {
    assertNull(PromptRecall().step(emptyList(), "", 0, true))
  }
}
