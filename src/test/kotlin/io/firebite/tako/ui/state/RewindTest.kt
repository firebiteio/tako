package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.google.gson.JsonObject
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.PromptStash
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ui.bridge.ChatMessage
import kotlinx.coroutines.cancel

class RewindTest : BasePlatformTestCase() {
  private lateinit var workspace: Workspace
  private lateinit var thread: ThreadState
  private lateinit var stash: PromptStash

  override fun setUp() {
    super.setUp()
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
    this.thread = ThreadState(this.workspace, ChatMessage.ThreadItem("thread", "Chat", false, 0, false, 0.0, 0, null,
      null, null, null, "claude", 0, null))
    this.thread.info = info(true)
    this.stash = PromptStash.getInstance(this.project)
    this.clearStash()
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
      this.clearStash()
    } finally {
      super.tearDown()
    }
  }

  fun testPromptsCountFollowUpsAndReplayedTurnsInOrder() {
    this.thread.replay(listOf(prompt("one"), AgentEvent.TurnEnded(null, null, null), prompt("two"),
      AgentEvent.TurnEnded(null, null, null)))
    val live = this.thread.startTurn(Prompt("three", emptyList(), emptyList()), false)
    live.items.add(TurnItem.FollowUp(Prompt("three and a half", emptyList(), emptyList())))
    this.thread.finishTurn(null)
    val last = this.thread.startTurn(Prompt("four 🐙", emptyList(), emptyList()), false)
    this.thread.queued.add(QueuedPrompt(9, Prompt("queued", emptyList(), emptyList())))

    assertEquals(listOf("one"), this.thread.promptsUpTo(this.thread.turns[0]))
    assertEquals(listOf("one", "two", "three", "three and a half", "four 🐙"), this.thread.promptsUpTo(last))
    assertEquals(6, this.thread.promptCount())
  }

  fun testOnlyADoneTurnOfAnIdleThreadWhoseAgentCanRewindOffersIt() {
    val turn = this.doneTurn("fix it")
    assertTrue(this.thread.canRewind(turn))

    this.thread.busy = true
    assertFalse(this.thread.canRewind(turn))
    this.thread.busy = false

    this.thread.addPermission("r1", PermissionPrompt(PermissionPrompt.Kind.TOOL, "Write", ToolKind.EDIT, JsonObject(),
      null, null, null, false, emptyList(), emptyList(), null))
    assertFalse(this.thread.canRewind(turn))
    this.thread.cancelAllApprovals()

    this.thread.queued.add(QueuedPrompt(5, Prompt("later", emptyList(), emptyList())))
    assertFalse(this.thread.canRewind(turn))
    this.thread.queued.clear()

    this.thread.rewinding = turn.prompt
    assertFalse(this.thread.canRewind(turn))
    this.thread.rewinding = null

    this.thread.info = info(false)
    assertFalse("ACP agents cannot rewind", this.thread.canRewind(turn))
    this.thread.info = null
    assertFalse("an agent that has not started cannot", this.thread.canRewind(turn))
    this.thread.info = info(true)

    val running = this.thread.startTurn(Prompt("running", emptyList(), emptyList()), false)
    assertFalse(this.thread.canRewind(running))
    val promptless = Turn(0, null, true).also { it.done = true }
    this.thread.turns.add(promptless)
    assertFalse(this.thread.canRewind(promptless))
    assertFalse("a turn no longer shown", this.thread.canRewind(Turn(0, Prompt("x", emptyList(), emptyList()), true)))
  }

  fun testRewindWithoutAControllerDoesNothing() {
    val turn = this.doneTurn("fix it")

    this.thread.rewind(turn, false)

    assertNull(this.thread.rewinding)
    assertFalse(this.thread.busy)
  }

  fun testSendingWaitsWhileRewinding() {
    val turn = this.doneTurn("fix it")
    this.thread.rewinding = turn.prompt
    this.thread.input.setTextAndPlaceCursorAtEnd("new prompt")

    this.thread.submit()
    this.thread.sendCommand("/compact")

    assertEquals("new prompt", this.thread.input.text.toString())
    assertEquals(1, this.thread.turns.size)
  }

  fun testARewoundPromptGoesBackIntoTheBoxAndTheDraftToTheStash() {
    val file = ContextItem.create(ContextItem.Kind.FILE, "Parser.java", "class Parser")
    val placeholder = ContextItem("", ContextItem.Kind.FILE, "IDE context", "")
    val turn = this.doneTurn("fix the parser", listOf(file, placeholder))
    this.thread.rewinding = turn.prompt
    this.thread.busy = true
    this.thread.input.setTextAndPlaceCursorAtEnd("half written")

    this.thread.onRewound(true, null)

    assertEquals("fix the parser", this.thread.input.text.toString())
    assertEquals(listOf(file), this.thread.contexts.toList())
    assertNull(this.thread.rewinding)
    assertFalse(this.thread.busy)
    assertEquals("half written", this.awaitStash().single().prompt.text)
  }

  fun testAFailedRewindKeepsTheTimelineAndSaysWhy() {
    val turn = this.doneTurn("fix it")
    this.thread.rewinding = turn.prompt
    this.thread.busy = true

    this.thread.onRewound(false, "The session no longer matches the chat.")

    assertEquals(listOf(turn), this.thread.turns.toList())
    assertEquals("", this.thread.input.text.toString())
    assertEquals("The session no longer matches the chat.", this.thread.alerts.single().text)
    assertNull(this.thread.rewinding)
    assertFalse(this.thread.busy)
  }

  fun testARefusedRewindWithoutReasonShowsNothing() {
    val turn = this.doneTurn("fix it")
    this.thread.rewinding = turn.prompt

    this.thread.onRewound(false, null)

    assertTrue(this.thread.alerts.isEmpty())
    assertNull(this.thread.rewinding)
  }

  fun testTheReplayOfARewindKeepsTheThreadBusy() {
    val turn = this.doneTurn("fix it")
    this.thread.rewinding = turn.prompt
    this.thread.busy = true

    this.thread.replay(emptyList())

    assertTrue(this.thread.busy)
    assertTrue(this.thread.turns.isEmpty())
  }

  private fun doneTurn(text: String, contexts: List<ContextItem> = emptyList()): Turn {
    val turn = this.thread.startTurn(Prompt(text, contexts, emptyList()), false)
    this.thread.finishTurn(null)
    return turn
  }

  private fun awaitStash(): List<PromptStash.Entry> {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      val entries = this.stash.entries()
      if (entries.isNotEmpty()) {
        return entries
      }
      Thread.sleep(20)
    }
    fail("The draft never reached the stash")
    return emptyList()
  }

  private fun clearStash() {
    for (entry in this.stash.entries()) {
      this.stash.remove(entry.id)
    }
  }

  private fun prompt(text: String) = AgentEvent.Prompt(text, false, emptyList())

  private fun info(rewinds: Boolean) = AgentInfo("claude", "Claude Code", "", AgentInfo.Capabilities(true, true, true,
    true, true, rewinds, true), emptyList(), null, emptyList(), null, null, emptyList())
}
