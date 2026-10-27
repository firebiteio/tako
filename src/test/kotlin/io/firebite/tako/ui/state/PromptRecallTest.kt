package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRecallTest {
  private val prompts = listOf("first", "second", "third")

  private val recall = PromptRecall()

  private fun prompt(text: String): Prompt = Prompt(text, emptyList(), emptyList())

  @Test
  fun backWalksToTheOldestAndForwardClearsPastTheNewest() {
    assertTrue(this.recall.takes(""))
    assertEquals("third", this.recall.back(this.prompts))
    assertEquals("second", this.recall.back(this.prompts))
    assertEquals("first", this.recall.back(this.prompts))
    assertNull(this.recall.back(this.prompts))
    assertEquals("second", this.recall.forward(this.prompts))
    assertEquals("third", this.recall.forward(this.prompts))
    assertEquals("", this.recall.forward(this.prompts))
    assertNull(this.recall.forward(this.prompts))
  }

  @Test
  fun aDraftKeepsTheArrows() {
    assertFalse(this.recall.takes("draft"))
  }

  @Test
  fun anEditedPromptBecomesADraft() {
    this.recall.back(this.prompts)

    assertTrue(this.recall.takes("third"))
    assertFalse(this.recall.takes("third!"))
    assertFalse(this.recall.shows("third"))
    assertTrue(this.recall.takes(""))
    assertEquals("third", this.recall.back(this.prompts))
  }

  @Test
  fun aPromptSentWhileBrowsingKeepsThePlace() {
    this.recall.back(this.prompts)
    this.recall.back(this.prompts)

    assertEquals("first", this.recall.back(this.prompts + "fourth"))
  }

  @Test
  fun promptsDroppedWhileBrowsingResumeFromTheNewestLeft() {
    this.recall.back(this.prompts)

    assertEquals("second", this.recall.back(listOf("first", "second")))
    assertEquals("", this.recall.forward(listOf("first", "second")))
  }

  @Test
  fun anEmptyThreadHasNothingToRecall() {
    assertNull(this.recall.back(emptyList()))
  }

  @Test
  fun sentPromptsListFollowUpsInTheirTurnThenQueuedOnes() {
    val first = Turn(1, this.prompt("first"), false)
    first.items.add(TurnItem.FollowUp(this.prompt("follow-up")))
    val imageOnly = Turn(2, this.prompt(" "), false)
    val noPrompt = Turn(2, null, false)
    val queued = listOf(QueuedPrompt(3, this.prompt("queued")))

    assertEquals(listOf("first", "follow-up", "queued"), sentPrompts(listOf(first, imageOnly, noPrompt), queued))
  }
}
