package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakoCommandParseTest {
  private val models = listOf(Choice("opus", "Opus", null), Choice("sonnet", "Sonnet", null),
    Choice("sonnet[1m]", "Sonnet 1M", null), Choice("haiku", "Haiku", null))

  @Test
  fun aTakoNameStartsACommandInAnyCase() {
    assertEquals(TakoInvocation(TakoCommand.MODEL, "opus"), parseTakoCommand("/model opus"))
    assertEquals(TakoInvocation(TakoCommand.MODEL, "opus"), parseTakoCommand("/MODEL opus"))
    assertEquals(TakoInvocation(TakoCommand.ARCHIVE, ""), parseTakoCommand("/archive"))
  }

  @Test
  fun blanksAroundTheArgumentAreDropped() {
    assertEquals(TakoInvocation(TakoCommand.MODEL, "opus"), parseTakoCommand("/model    opus  "))
    assertEquals(TakoInvocation(TakoCommand.MODEL, "opus"), parseTakoCommand("/model\topus"))
    assertEquals(TakoInvocation(TakoCommand.MODEL, "opus"), parseTakoCommand("/model\nopus"))
    assertEquals(TakoInvocation(TakoCommand.NEW, "fix the\nparser"), parseTakoCommand("/new   fix the\nparser"))
  }

  @Test
  fun otherTextIsNoTakoCommand() {
    for (text in listOf("", "/", "//", "/ model", "/modelx", "/compact", "model opus", "hi /model", "//model x",
      "/mödel")) {
      assertNull(text, parseTakoCommand(text))
    }
  }

  @Test
  fun unicodeAndHugeArgumentsPassThrough() {
    assertEquals("🐙 ünï", parseTakoCommand("/new 🐙 ünï")!!.argument)
    val huge = "x".repeat(100_000)
    assertEquals(huge, parseTakoCommand("/new $huge")!!.argument)
  }

  @Test
  fun doubleSlashBeforeATakoCommandSendsItWithOneSlashLess() {
    assertEquals("/model x", escapedTakoCommand("//model x"))
    assertEquals("/archive", escapedTakoCommand("//archive"))
    assertNull(escapedTakoCommand("//"))
    assertNull(escapedTakoCommand("// comment"))
    assertNull(escapedTakoCommand("//foo"))
    assertNull(escapedTakoCommand("/model x"))
    assertNull(escapedTakoCommand("///model x"))
  }

  @Test
  fun anExactValueBeatsAPrefix() {
    assertEquals(listOf("sonnet"), matchChoices(this.models, "sonnet").map { it.value })
    assertEquals(listOf("opus"), matchChoices(this.models, "OPUS").map { it.value })
  }

  @Test
  fun anExactLabelMatches() {
    assertEquals(listOf("sonnet[1m]"), matchChoices(this.models, "sonnet 1m").map { it.value })
  }

  @Test
  fun aUniquePrefixBeatsContains() {
    assertEquals(listOf("opus"), matchChoices(this.models, "o").map { it.value })
    assertEquals(listOf("haiku"), matchChoices(this.models, "ku").map { it.value })
  }

  @Test
  fun severalMatchesOnTheBestTierAreAmbiguous() {
    assertEquals(listOf("sonnet", "sonnet[1m]"), matchChoices(this.models, "son").map { it.value })
    assertEquals(listOf("opus", "haiku"), matchChoices(this.models, "u").map { it.value })
  }

  @Test
  fun nothingMatchesUnknownText() {
    assertEquals(emptyList<Choice>(), matchChoices(this.models, "gpt"))
    assertEquals(emptyList<Choice>(), matchChoices(emptyList(), "opus"))
  }
}
