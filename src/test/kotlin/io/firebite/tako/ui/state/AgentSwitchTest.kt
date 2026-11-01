package io.firebite.tako.ui.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.ContextUsage
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import kotlinx.coroutines.cancel

class AgentSwitchTest : BasePlatformTestCase() {
  private lateinit var store: ThreadStore
  private lateinit var workspace: Workspace

  override fun setUp() {
    super.setUp()
    this.store = ThreadStore.getInstance(this.project)
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
    } finally {
      super.tearDown()
    }
  }

  private fun thread(): Pair<ThreadStore.Record, ThreadState> {
    val record = this.store.create(null, null, null, "claude")
    val thread = ThreadState(this.workspace, record.toItem())
    this.workspace.threads[record.id] = thread
    return record to thread
  }

  private fun info(agent: String): AgentInfo = AgentInfo(agent, agent, "", AgentInfo.Capabilities(true, false, true,
    false, true, true, false), listOf(AgentInfo.Model("m-$agent", "M", null, null, listOf("high"))), "m-$agent", emptyList(), null,
    "high", emptyList())

  private fun divider(turn: Turn): TurnItem.Divider? = turn.items.singleOrNull() as? TurnItem.Divider

  fun testTheDividerFollowsTheLastTurnAndStaysVisible() {
    val (_, thread) = this.thread()
    thread.startPrompt("first", emptyList(), emptyList())
    thread.handleEvent(AgentEvent.Text(null, "answer", null))
    thread.handleEvent(AgentEvent.TurnEnded(10, null, null))

    thread.handleEvent(AgentEvent.AgentSwitched("Claude Code", "Codex"))

    assertEquals(2, thread.turns.size)
    val turn = thread.turns.last()
    assertTrue(turn.done)
    assertTrue(turn.folds.isEmpty())
    assertTrue(divider(turn)!!.text.contains("Codex"))
    assertTrue(thread.hasConversation)
  }

  fun testAPromptSentDuringTheSwitchStaysBelowTheDivider() {
    val (_, thread) = this.thread()
    thread.startPrompt("first", emptyList(), emptyList())
    thread.handleEvent(AgentEvent.TurnEnded(10, null, null))
    thread.startPrompt("typed while switching", emptyList(), emptyList())

    thread.handleEvent(AgentEvent.AgentSwitched("Claude Code", "Codex"))
    thread.handleEvent(AgentEvent.Text(null, "new agent answers", null))

    assertEquals(3, thread.turns.size)
    assertNotNull(divider(thread.turns[1]))
    val live = thread.turns[2]
    assertEquals("typed while switching", live.prompt?.text)
    assertFalse(live.done)
    assertEquals(1, live.items.size)
  }

  fun testReplayedSwitchesShowAsDividersBetweenTheAgentsTurns() {
    val (_, thread) = this.thread()

    thread.replay(listOf(AgentEvent.Prompt("earlier", false, emptyList()), AgentEvent.Text(null, "done", null),
      AgentEvent.AgentSwitched("Codex", "Claude Code"), AgentEvent.Prompt("now", false, emptyList())))

    assertEquals(3, thread.turns.size)
    assertNotNull(divider(thread.turns[1]))
    assertEquals("now", thread.turns[2].prompt?.text)
  }

  fun testSwitchingForgetsTheOldSessionButKeepsWhatTheNewAgentAlreadySent() {
    val (record, thread) = this.thread()
    thread.onInit(info("claude"), null)
    thread.contextUsage = ContextUsage(1, 2, null, null, emptyList(), emptyList(), emptyList(), null)
    thread.cache = PromptCache(0, 0, 10)
    thread.scratchpad = "/tmp/pad"
    thread.costUsd = 3.0
    record.costUsd = 3.0
    record.sessionId = "claude-session"
    record.endSession()

    thread.handleEvent(AgentEvent.AgentSwitched("Claude Code", "Codex"))
    assertNull(thread.contextUsage)
    assertNull(thread.cache)
    assertNull(thread.scratchpad)
    thread.onInit(info("codex"), null)
    thread.handleEvent(AgentEvent.SessionStarted("c1", null, null, null, "/codex/pad"))
    record.agent = "codex"
    thread.update(record.toItem())

    assertEquals("codex", thread.agent)
    assertEquals("codex", thread.info?.agent)
    assertEquals("m-codex", thread.model)
    assertEquals("/codex/pad", thread.scratchpad)
    assertEquals(3.0, thread.costUsd)

    record.agent = "gemini"
    thread.update(record.toItem())
    assertNull(thread.info)
    assertNull(thread.model)
    assertEquals("default", thread.mode)
  }
}
