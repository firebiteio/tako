package io.firebite.tako.ui.state

import com.google.gson.JsonObject
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DeclineTest {
  private val shell = PermissionPrompt(PermissionPrompt.Kind.TOOL, "Shell", ToolKind.COMMAND,
    JsonObject().apply { this.addProperty("command", "rm -rf build") }, "call-1", null, null, false, emptyList(),
    emptyList(), null)

  @Test
  fun anAgentThatReadsTheReasonGetsTheFeedbackAsTheDeclineMessage() {
    assertEquals(Decline("use gradle clean", false, null, false),
      decline(this.shell, "use gradle clean", "Declined.", false, true))
  }

  @Test
  fun anAgentThatReadsTheReasonStopsWithTheFeedback() {
    assertEquals(Decline("not now", true, null, false), decline(this.shell, "not now", "Stopped.", true, true))
  }

  @Test
  fun anAgentWithoutAReasonStopsItsTurnAndGetsTheFeedbackNamingTheDeclinedCallAsTheNextPrompt() {
    assertEquals(Decline("Declined.", true, "I declined Shell: rm -rf build\n\nuse gradle clean", false),
      decline(this.shell, "use gradle clean", "Declined.", false, false))
  }

  @Test
  fun declineAndStopSendsNoPromptToAnAgentWithoutAReasonAndKeepsTheFeedback() {
    assertEquals(Decline("Stopped.", true, null, true), decline(this.shell, "not now", "Stopped.", true, false))
  }

  @Test
  fun aDeclineWithoutFeedbackSendsNoPrompt() {
    assertEquals(Decline("Declined.", false, null, false), decline(this.shell, "", "Declined.", false, false))
  }

  @Test
  fun theDeclinedCallIsNamedByItsLabelWithoutATarget() {
    val prompt = PermissionPrompt(PermissionPrompt.Kind.TOOL, "fetch", ToolKind.OTHER, JsonObject(), null,
      "Fetch docs", null, false, emptyList(), emptyList(), null)

    assertEquals("I declined Fetch docs.\n\nskip it", decline(prompt, "skip it", "Declined.", false, false).followUp)
  }
}
