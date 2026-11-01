package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.t
import kotlinx.coroutines.cancel

class TakoCommandsTest : BasePlatformTestCase() {
  private lateinit var store: ThreadStore
  private lateinit var workspace: Workspace
  private lateinit var thread: ThreadState

  private val models = listOf(
    AgentInfo.Model("opus", "Opus", "Most capable", null, listOf("low", "high")),
    AgentInfo.Model("sonnet", "Sonnet", null, null, emptyList()),
    AgentInfo.Model("sonnet[1m]", "Sonnet 1M", null, null, emptyList()),
    AgentInfo.Model("haiku", "Haiku", null, null, emptyList()))
  private val modes = listOf(AgentInfo.Mode("default", "Ask before edits", ""),
    AgentInfo.Mode("acceptEdits", "Accept edits", ""), AgentInfo.Mode("plan", "Plan", ""))

  override fun setUp() {
    super.setUp()
    this.store = ThreadStore.getInstance(this.project)
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
    this.thread = this.newThread()
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
    } finally {
      super.tearDown()
    }
  }

  private fun newThread(): ThreadState {
    val record = this.store.create(null, null, null, "claude")
    val thread = ThreadState(this.workspace, record.toItem())
    this.workspace.threads[record.id] = thread
    return thread
  }

  private fun start(fork: Boolean = true, resume: Boolean = true,
    commands: List<AgentInfo.Command> = emptyList()) {
    this.thread.onInit(AgentInfo("claude", "Claude Code", "", AgentInfo.Capabilities(true, true, fork, true, resume),
      this.models, "opus", this.modes, "default", null, commands), null)
  }

  private fun resolve(text: String): TakoAction = this.thread.resolve(parseTakoCommand(text)!!)

  private fun fail(text: String): String = (this.resolve(text) as TakoAction.Fail).message

  private fun type(text: String) {
    this.thread.input.setTextAndPlaceCursorAtEnd(text)
  }

  private fun answeredTurn(prompt: String) {
    val turn = this.thread.startTurn(Prompt(prompt, emptyList(), emptyList()), false)
    turn.items.add(TurnItem.Text(AssistantText("done", true)))
    turn.done = true
  }

  private fun approval(): Approval = Approval("r1", PermissionPrompt(PermissionPrompt.Kind.TOOL, "Shell",
    ToolKind.COMMAND, JsonObject(), null, null, null, false, emptyList(), emptyList(), null))

  fun testAModelCommandSwitchesTheModelWithoutSendingAnything() {
    this.start()
    this.type("/model sonnet")

    this.thread.submit()

    assertEquals("sonnet", this.thread.model)
    assertEquals("", this.thread.input.text.toString())
    assertTrue(this.thread.turns.isEmpty())
    assertFalse(this.thread.busy)
    assertTrue(this.thread.alerts.isEmpty())
  }

  fun testAModeCommandTakesTheLabelAndPlan() {
    this.start()
    assertEquals(TakoAction.Select(TakoCommand.MODE, "acceptEdits"), this.resolve("/mode Accept edits"))
    assertEquals(TakoAction.Select(TakoCommand.MODE, "plan"), this.resolve("/mode plan"))
  }

  fun testAnEffortCommandPicksFromTheModelsLevels() {
    this.start()
    assertEquals(TakoAction.Select(TakoCommand.EFFORT, "high"), this.resolve("/effort High"))
    this.thread.model = "sonnet"
    assertEquals(t("slash.error.no.choices", "effort", "Claude Code"), this.fail("/effort high"))
  }

  fun testAFailingCommandKeepsTheTextAndSaysWhy() {
    this.start()
    this.type("/model gpt")

    this.thread.submit()

    assertEquals("/model gpt", this.thread.input.text.toString())
    assertEquals("opus", this.thread.model)
    assertTrue(this.thread.turns.isEmpty())
    assertEquals(t("slash.error.choice.unknown", "model", "gpt", "opus, sonnet, sonnet[1m], haiku"),
      this.thread.alerts.single().text)
  }

  fun testAMissingOrAmbiguousArgumentListsTheChoices() {
    this.start()
    assertEquals(t("slash.error.choice.missing", "model", "opus, sonnet, sonnet[1m], haiku"), this.fail("/model"))
    assertEquals(t("slash.error.choice.missing", "model", "opus, sonnet, sonnet[1m], haiku"), this.fail("/model   "))
    assertEquals(t("slash.error.choice.ambiguous", "model", "son", "sonnet, sonnet[1m]"), this.fail("/model son"))
  }

  fun testAHugeArgumentIsCutInTheAlert() {
    this.start()
    val message = this.fail("/model " + "x".repeat(100_000))
    assertTrue(message.length < 200)
    assertTrue(message.contains("…"))
  }

  fun testChoicesBeforeTheAgentStartedSayItHasNotStarted() {
    val notStarted = t("slash.error.not.started", this.thread.agentName)
    assertEquals(notStarted, this.fail("/model opus"))
    assertEquals(notStarted, this.fail("/effort high"))
    assertEquals(notStarted, this.fail("/mode plan"))
    assertEquals(notStarted, this.fail("/fork"))
    assertEquals(notStarted, this.fail("/restart"))
  }

  fun testACommandWithoutArgumentRejectsOne() {
    assertEquals(t("slash.error.argument", "archive"), this.fail("/archive now"))
    assertEquals(t("slash.error.argument", "usage"), this.fail("/usage   x"))
  }

  fun testTheAgentCanOnlyChangeBeforeTheFirstPromptAndWithAnotherAgent() {
    assertEquals(t("slash.error.agent.single"), this.fail("/agent codex"))
    this.workspace.agents = listOf(ChatMessage.AgentOption("claude", "Claude Code"),
      ChatMessage.AgentOption("codex", "Codex"))
    assertEquals(TakoAction.Select(TakoCommand.AGENT, "codex"), this.resolve("/agent codex"))
    this.answeredTurn("hi")
    assertEquals(t("slash.error.agent.locked"), this.fail("/agent codex"))
  }

  fun testPickingTheSameAgentChangesNothing() {
    this.workspace.agents = listOf(ChatMessage.AgentOption("claude", "Claude Code"),
      ChatMessage.AgentOption("codex", "Codex"))
    this.type("/agent claude code")

    this.thread.submit()

    assertEquals("claude", this.thread.agent)
    assertEquals("", this.thread.input.text.toString())
  }

  fun testNewTakesTheRestAsItsPrompt() {
    assertEquals(TakoAction.NewThread(""), this.resolve("/new"))
    assertEquals(TakoAction.NewThread("idea: rewrite the parser"), this.resolve("/new idea: rewrite the parser"))
  }

  fun testForkNeedsTheCapabilityAndAFinishedAnswer() {
    this.start(fork = false)
    assertEquals(t("slash.error.fork.unsupported", "Claude Code"), this.fail("/fork"))
    this.start()
    assertEquals(t("slash.error.fork.empty"), this.fail("/fork"))
    this.answeredTurn("one")
    assertEquals(t("slash.error.fork.empty"), this.fail("/fork"))
    this.thread.hasSession = true
    assertEquals(TakoAction.Fork(1), this.resolve("/fork"))
  }

  fun testForkKeepsThePromptsUpToTheLastFinishedAnswer() {
    assertNull(this.thread.forkPrompts())
    this.answeredTurn("one")
    this.answeredTurn("two")
    assertEquals(2, this.thread.forkPrompts())
    this.thread.startTurn(Prompt("three", emptyList(), emptyList()), false)
    assertEquals(2, this.thread.forkPrompts())
    val unanswered = this.thread.startTurn(Prompt("four", emptyList(), emptyList()), false)
    unanswered.done = true
    assertEquals(2, this.thread.forkPrompts())
  }

  fun testRestartOnlyWhileIdleAndRunning() {
    this.start(resume = false)
    assertEquals(t("slash.error.restart.unsupported", "Claude Code"), this.fail("/restart"))
    this.start()
    assertEquals(TakoAction.Restart, this.resolve("/restart"))
    this.thread.busy = true
    assertEquals(t("slash.error.restart.busy", "Claude Code"), this.fail("/restart"))
    this.thread.busy = false
    this.thread.approvals.add(this.approval())
    assertEquals(t("slash.error.restart.busy", "Claude Code"), this.fail("/restart"))
    this.thread.approvals.clear()
    this.thread.onExit(1, "")
    assertEquals(t("slash.error.restart.stopped", "Claude Code"), this.fail("/restart"))
  }

  fun testTitleNeedsAConversationAndNoTitleBeingWritten() {
    assertEquals(t("slash.error.title.empty"), this.fail("/title"))
    this.answeredTurn("hi")
    assertEquals(TakoAction.Title, this.resolve("/title"))
    this.thread.titling = true
    assertEquals(t("sidebar.regenerate.title.running"), this.fail("/title"))
  }

  fun testASecondTitleIsTurnedDownWhileTheFirstIsChecked() {
    this.answeredTurn("hi")
    this.workspace.scope.cancel()

    this.thread.perform(TakoAction.Title)

    assertTrue(this.thread.titling)
    assertEquals(t("sidebar.regenerate.title.running"), this.fail("/title"))
  }

  fun testArchiveArchivesTheThread() {
    this.type("/archive")

    this.thread.submit()

    assertTrue(this.store.find(this.thread.id)!!.archived)
    assertEquals("", this.thread.input.text.toString())
  }

  fun testCommandsRunWhileTheAgentWorks() {
    this.start()
    this.thread.busy = true
    this.type("/model haiku")

    this.thread.submit()

    assertEquals("haiku", this.thread.model)
    assertTrue(this.thread.queued.isEmpty())
    assertTrue(this.thread.busy)
  }

  fun testCommandsRunWhileAnApprovalWaitsAndLeaveItWaiting() {
    this.start()
    this.thread.approvals.add(this.approval())
    this.type("/mode acceptEdits")

    this.thread.submit()

    assertEquals("acceptEdits", this.thread.mode)
    assertEquals(1, this.thread.approvals.size)
    assertEquals("", this.thread.input.text.toString())
  }

  fun testCommandsRunAfterTheAgentDied() {
    this.start()
    this.thread.onExit(1, "crashed")
    this.type("/model sonnet")

    this.thread.submit()

    assertEquals("sonnet", this.thread.model)
  }

  fun testASecondSendOfTheSameCommandDoesNothing() {
    this.start()
    this.type("/model sonnet")

    this.thread.submit()
    this.thread.submit()

    assertEquals("sonnet", this.thread.model)
    assertTrue(this.thread.turns.isEmpty())
    assertTrue(this.thread.alerts.isEmpty())
  }

  fun testDoubleSlashSendsTheCommandToTheAgent() {
    this.start()
    this.type("//model sonnet")

    this.thread.submit()

    assertEquals("/model sonnet", this.thread.turns.single().prompt!!.text)
    assertEquals("opus", this.thread.model)
  }

  fun testDoubleSlashDeclinesWithTheCommandAsFeedback() {
    this.start()
    this.thread.approvals.add(this.approval())
    this.type("//model sonnet")

    this.thread.submit()

    assertTrue(this.thread.approvals.isEmpty())
    assertEquals("opus", this.thread.model)
    assertEquals("", this.thread.input.text.toString())
  }

  fun testDoubleSlashAnswersAQuestionWithOneSlashLess() {
    val question = PermissionPrompt.Question("Which command?", null, false, emptyList())
    val approval = Approval("q1", PermissionPrompt(PermissionPrompt.Kind.QUESTION, "AskUserQuestion",
      ToolKind.QUESTION, JsonObject(), null, null, null, false, emptyList(), listOf(question), null))
    this.thread.approvals.add(approval)
    val state = this.thread.questionFor(approval)
    this.type("//model sonnet")

    this.thread.submit()

    assertTrue(this.thread.approvals.isEmpty())
    assertEquals("/model sonnet", state.answers["Which command?"])
  }

  fun testOtherSlashTextStillGoesToTheAgent() {
    for (text in listOf("/compact", "/foo bar", "/", "// note")) {
      this.thread.turns.clear()
      this.thread.busy = false
      this.type(text)

      this.thread.submit()

      assertEquals(text, this.thread.turns.single().prompt!!.text)
    }
  }

  fun testSendAndBranchOnlyRunsATakoCommand() {
    this.start()
    val threads = this.store.all().size
    this.type("/model sonnet")

    this.thread.submitAndBranch()

    assertEquals("sonnet", this.thread.model)
    assertEquals(threads, this.store.all().size)
  }

  fun testTheMenuListsTakosCommandsWithoutAgentCommands() {
    val names = this.thread.slashMenu("/").items.map { it.name }
    assertEquals(listOf("model", "effort", "mode", "new", "archive", "title", "usage"), names)
    assertTrue(this.thread.slashMenu("/").items.all { it.tako })
  }

  fun testTheMenuMixesInTheAgentsCommandsAndHidesClashingOnes() {
    this.start(commands = listOf(AgentInfo.Command("model", "agent model", ""),
      AgentInfo.Command("compact", "Compact", ""), AgentInfo.Command("Archive", "", "")))

    val items = this.thread.slashMenu("/").items

    assertEquals(1, items.count { it.name.equals("model", true) })
    assertTrue(items.single { it.name == "model" }.tako)
    assertTrue(items.none { it.name == "Archive" })
    val compact = items.single { it.name == "compact" }
    assertFalse(compact.tako)
    assertEquals("/compact ", compact.text)
    assertTrue(items.any { it.name == "fork" })
    assertTrue(items.any { it.name == "restart" })
  }

  fun testAnExactNameRanksFirst() {
    assertEquals("mode", this.thread.slashMenu("/mode").items.first().name)
    assertEquals("model", this.thread.slashMenu("/mod").items.first().name)
    assertEquals(listOf("model", "mode"), this.thread.slashMenu("/MOD").items.map { it.name })
  }

  fun testACommandWithoutArgumentRunsFromTheMenuOnceItsWholeNameIsTyped() {
    val partial = this.thread.slashMenu("/arch").items.single()
    assertEquals("/archive", partial.text)
    assertFalse(partial.runs)
    assertTrue(this.thread.slashMenu("/archive").items.single().runs)
    val model = this.thread.slashMenu("/model").items.first()
    assertEquals("/model ", model.text)
    assertFalse(model.runs)
  }

  fun testTheMenuListsAnArgumentsValuesStartingOnTheCurrentOne() {
    this.start()
    this.thread.model = "sonnet"

    val menu = this.thread.slashMenu("/model ")

    assertEquals(listOf("Opus", "Sonnet", "Sonnet 1M", "Haiku"), menu.items.map { it.name })
    assertEquals(1, menu.selected)
    assertTrue(menu.items.all { it.runs && it.argument && it.tako })
    assertEquals("/model sonnet[1m]", menu.items[2].text)
    assertEquals("Most capable", menu.items[0].description)
    assertEquals("haiku", menu.items[3].description)
  }

  fun testTheValuesAreFilteredBestFitFirst() {
    this.start()
    val menu = this.thread.slashMenu("/model  sonnet ")
    assertEquals(listOf("Sonnet", "Sonnet 1M"), menu.items.map { it.name })
    assertEquals(0, menu.selected)
    assertEquals(listOf("Haiku", "Opus"), this.thread.slashMenu("/model ku").items.map { it.name } +
      this.thread.slashMenu("/model op").items.map { it.name })
  }

  fun testLeadingBlanksStillShowTheMenu() {
    assertTrue(this.thread.slashMenu("  /archive").items.single().runs)
    assertTrue(this.thread.slashMenu("\n/arch").items.single().tako)
  }

  fun testNoMenuForOtherText() {
    this.start()
    for (text in listOf("", "//", "hello /model", "/model so\nmore", "/new ", "/compact x", "/agent ", "/model gpt")) {
      assertEquals(text, emptyList<SlashItem>(), this.thread.slashMenu(text).items)
    }
  }

  fun testHiddenCommandsWithoutTheCapability() {
    this.start(fork = false, resume = false)
    val names = this.thread.slashMenu("/").items.map { it.name }
    assertFalse("fork" in names)
    assertFalse("restart" in names)
  }
}
