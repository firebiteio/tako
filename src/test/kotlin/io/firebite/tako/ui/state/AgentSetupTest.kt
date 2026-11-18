package io.firebite.tako.ui.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.SetupProblem
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ui.bridge.message.workspace.AgentOption
import kotlinx.coroutines.cancel

class AgentSetupTest : BasePlatformTestCase() {
  private lateinit var workspace: Workspace

  override fun setUp() {
    super.setUp()
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
    } finally {
      super.tearDown()
    }
  }

  private fun thread(agent: String): ThreadState {
    val record = ThreadStore.getInstance(this.project).create(null, null, null, agent)
    val thread = ThreadState(this.workspace, record.toItem())
    this.workspace.threads[record.id] = thread
    return thread
  }

  fun testAnEmptyThreadAsksToSetUpItsAgent() {
    this.workspace.agents = listOf(AgentOption("claude", "Claude Code", SetupProblem.NO_API_KEY),
      AgentOption("codex", "Codex", SetupProblem.NOT_INSTALLED), AgentOption("acp:Gemini", "Gemini"))

    val thread = this.thread("claude")

    assertEquals(SetupProblem.NO_API_KEY, thread.agentToSetUp?.problem)
    assertEquals(listOf("acp:Gemini"), thread.readyAgents.map { it.id })
  }

  fun testAReadyAgentNeedsNoSetup() {
    this.workspace.agents = listOf(AgentOption("claude", "Claude Code"), AgentOption("codex", "Codex",
      SetupProblem.NOT_INSTALLED))

    assertNull(this.thread("claude").agentToSetUp)
  }

  fun testAnUnknownAgentFallsBackToTheDefault() {
    this.workspace.agents = listOf(AgentOption("claude", "Claude Code", SetupProblem.NOT_INSTALLED))

    assertEquals("claude", this.thread("acp:Removed").agentToSetUp?.id)
  }

  fun testAFailedStartReplacesTheEarlierStartError() {
    val thread = this.thread("claude")

    thread.onError("CLI not found", true)
    thread.onError("Could not start", true)

    assertEquals(listOf("Could not start"), thread.alerts.map { it.text })
  }

  fun testAThreadWithTurnsShowsNoSetup() {
    this.workspace.agents = listOf(AgentOption("claude", "Claude Code", SetupProblem.NO_API_KEY))
    val thread = this.thread("claude")

    thread.startPrompt("hi", emptyList(), emptyList())
    thread.handleEvent(AgentEvent.TurnEnded(10, null, null))

    assertNull(thread.agentToSetUp)
  }
}
