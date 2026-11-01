package io.firebite.tako.ui.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.thread.QueuedPrompts.Delivery
import io.firebite.tako.chat.thread.QueuedPrompts.Status
import io.firebite.tako.ui.bridge.ChatMessage
import kotlinx.coroutines.cancel

class QueueTest : BasePlatformTestCase() {
  private lateinit var workspace: Workspace
  private lateinit var thread: ThreadState

  override fun setUp() {
    super.setUp()
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
    this.thread = ThreadState(this.workspace, ChatMessage.ThreadItem("thread", "Chat", false, 0, false, 0.0, 0, null,
      null, null, null, "claude", 0, null, null, false, false, 0, null, false, 0))
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
    } finally {
      super.tearDown()
    }
  }

  private fun queued(no: Int, text: String = "p$no"): QueuedPrompt =
    QueuedPrompt(no, Prompt(text, emptyList(), emptyList())).also { this.thread.queued.add(it) }

  private fun snapshot(held: Boolean, running: Boolean, vararg items: Pair<Int, Status>) =
    ChatMessage.Queue(items.map { ChatMessage.QueueItem(it.first, it.second) }, held, running)

  fun testDraftAndQueuedTextMerge() {
    assertEquals("queued", mergeDraft("", "queued"))
    assertEquals("queued", mergeDraft("  \n", "queued"))
    assertEquals("draft", mergeDraft("draft", ""))
    assertEquals("draft\n\nqueued", mergeDraft("draft\n\n\n", "queued"))
    assertEquals("ä 😀\n\n日本", mergeDraft("ä 😀", "日本"))
  }

  fun testDeliveryFollowsTheSettingTheModifierAndTheAgent() {
    assertEquals(Delivery.QUEUE, followUpDelivery(false, false, true))
    assertEquals(Delivery.STEER, followUpDelivery(false, true, true))
    assertEquals(Delivery.STEER, followUpDelivery(true, false, true))
    assertEquals(Delivery.QUEUE, followUpDelivery(true, true, true))
    assertEquals("an agent that can't steer always queues", Delivery.QUEUE, followUpDelivery(true, false, false))
    assertEquals(Delivery.QUEUE, followUpDelivery(false, true, false))
    assertEquals("before the agent started", Delivery.QUEUE, this.thread.followUpDelivery(true))
  }

  fun testSnapshotSetsStatusesAndOrder() {
    val first = this.queued(2)
    val second = this.queued(3)
    val third = this.queued(4)
    this.thread.applyQueue(this.snapshot(false, true, 4 to Status.NEXT, 2 to Status.HELD, 3 to Status.STEERING))
    assertEquals(listOf(third, first, second), this.thread.queued.toList())
    assertEquals(Status.NEXT, third.status)
    assertEquals(Status.STEERING, second.status)
    assertTrue(this.thread.queueGoesOn)
  }

  fun testPromptsTheSnapshotDoesNotListKeepTheirSlot() {
    val taken = this.queued(2)
    val first = this.queued(3)
    val second = this.queued(4)
    val added = this.queued(5)
    this.thread.applyQueue(this.snapshot(false, true, 4 to Status.HELD, 3 to Status.HELD))
    assertEquals(listOf(taken, second, first, added), this.thread.queued.toList())
  }

  fun testHeldQueueDoesNotGoOn() {
    this.queued(2)
    this.thread.applyQueue(this.snapshot(true, false, 2 to Status.HELD))
    assertTrue(this.thread.queueHeld)
    assertFalse(this.thread.queueGoesOn)
  }

  fun testNothingRunningEndsABusyThreadOnlyOnceItsTurnEnded() {
    this.thread.startPrompt("hi", emptyList(), emptyList())
    assertTrue(this.thread.busy)
    this.thread.applyQueue(this.snapshot(false, false))
    assertTrue("the turn's end is still on its way", this.thread.busy)
    this.thread.finishTurn(null)
    this.thread.applyQueue(this.snapshot(false, false))
    assertFalse(this.thread.busy)
    assertTrue(this.thread.finished)
  }

  fun testLateSteerJoinsTheEndedTurnWithoutMakingTheThreadBusy() {
    this.thread.startPrompt("hi", emptyList(), emptyList())
    this.thread.turnNo = 2
    this.thread.queued.add(QueuedPrompt(2, Prompt("also b", emptyList(), emptyList()), Status.STEERING))
    this.thread.handleEvent(AgentEvent.TurnEnded(10L, null, null))
    assertTrue("a steering prompt keeps it busy", this.thread.busy)
    this.thread.applyQueue(this.snapshot(false, false))
    assertFalse(this.thread.busy)
    this.thread.handleEvent(AgentEvent.PromptTaken(2, false))
    assertTrue(this.thread.queued.isEmpty())
    assertFalse(this.thread.busy)
    val last = this.thread.turns.last()
    assertTrue(last.done)
    assertTrue(last.items.any { it is TurnItem.FollowUp && it.prompt.text == "also b" })
  }

  fun testExitAndReplayKeepTheQueueAndItsNumbers() {
    this.thread.turnNo = 7
    this.queued(7)
    this.thread.onExit(1, "")
    assertEquals(1, this.thread.queued.size)
    this.thread.replay(listOf(AgentEvent.Prompt("old", false, emptyList())))
    assertEquals(1, this.thread.queued.size)
    assertEquals(7, this.thread.turnNo)
    assertEquals(0, this.thread.turns.single().no)
  }

  fun testStashingClearsTheQueue() {
    this.queued(2)
    this.thread.applyQueue(this.snapshot(true, false, 2 to Status.HELD))
    this.thread.onQueueStashed(1)
    assertTrue(this.thread.queued.isEmpty())
    assertFalse(this.thread.queueHeld)
    assertEquals(1, this.thread.alerts.size)
  }

  fun testOnlyAPromptAfterAHeldOneMovesUp() {
    val steering = QueuedPrompt(2, Prompt("s", emptyList(), emptyList()), Status.STEERING)
    this.thread.queued.add(steering)
    val first = this.queued(3)
    val second = this.queued(4)
    assertFalse(this.thread.canMoveUp(steering))
    assertFalse(this.thread.canMoveUp(first))
    assertTrue(this.thread.canMoveUp(second))
  }
}
