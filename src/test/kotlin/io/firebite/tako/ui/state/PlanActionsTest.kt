package io.firebite.tako.ui.state

import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import kotlinx.coroutines.cancel

class PlanActionsTest : BasePlatformTestCase() {
  private lateinit var store: ThreadStore
  private lateinit var workspace: Workspace
  private lateinit var thread: ThreadState

  override fun setUp() {
    super.setUp()
    this.store = ThreadStore.getInstance(this.project)
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
    val record = this.store.create(null, null, null, "claude")
    this.thread = ThreadState(this.workspace, record.toItem())
    this.workspace.threads[record.id] = this.thread
  }

  override fun tearDown() {
    try {
      this.store.remove(this.thread.id)
      this.workspace.scope.cancel()
    } finally {
      super.tearDown()
    }
  }

  private fun input(plan: String?): JsonObject = JsonObject().apply { plan?.let { this.addProperty("plan", it) } }

  private fun exitPlanMode(id: String, plan: String?, parentId: String? = null) =
    AgentEvent.ToolStarted(id, "ExitPlanMode", ToolKind.PLAN, this.input(plan), null, parentId)

  private fun planPrompt(plan: String, toolUseId: String) = PermissionPrompt(PermissionPrompt.Kind.PLAN, "ExitPlanMode",
    ToolKind.PLAN, this.input(plan), toolUseId, null, null, false, emptyList(), emptyList(), plan)

  private fun toolPrompt(toolUseId: String) = PermissionPrompt(PermissionPrompt.Kind.TOOL, "Bash", ToolKind.COMMAND,
    JsonObject(), toolUseId, null, null, false, emptyList(), emptyList(), null)

  private fun plans(): List<TurnItem.Plan> = this.thread.turns.flatMap { it.items }.filterIsInstance<TurnItem.Plan>()

  private fun replay(vararg events: AgentEvent) {
    this.thread.replay(listOf(AgentEvent.Prompt("plan it", false, emptyList())) + events.toList() +
      AgentEvent.Text("m1", "Here is the plan.", null) + AgentEvent.TurnEnded(10L, null, null))
  }

  fun testAReplayedPlanComesBackAsACardOutsideTheFold() {
    this.replay(this.exitPlanMode("t1", "# Add dark mode\n1. do it"), AgentEvent.ToolFinished("t1", "ok", false, null))

    val plans = this.plans()
    assertEquals(1, plans.size)
    assertEquals("# Add dark mode\n1. do it", plans[0].plan)
    assertNull(plans[0].requestId)
    assertTrue(this.thread.turns.flatMap { it.folds }.none { plans[0] in it.items })
  }

  fun testOnlyAReplayedExitPlanModeWithAPlanMakesACard() {
    this.replay(this.exitPlanMode("t1", "   "), this.exitPlanMode("t2", null),
      AgentEvent.ToolStarted("t3", "switch_mode", ToolKind.PLAN, this.input("# ACP plan"), null, null),
      AgentEvent.ToolStarted("t4", "Task", ToolKind.AGENT, JsonObject(), null, null),
      this.exitPlanMode("t5", "# Subagent plan", "t4"))

    assertTrue(this.plans().isEmpty())
  }

  fun testALivePlanCardComesOnlyWithItsApproval() {
    this.thread.handleEvent(this.exitPlanMode("t1", "# Live"))
    assertTrue(this.plans().isEmpty())

    this.thread.addPermission("r1", this.planPrompt("# Live", "t1"))

    assertEquals("r1", this.plans().single().requestId)
  }

  fun testAPlanCardStaysOutOfTheFoldAfterItsTurn() {
    this.thread.handleEvent(AgentEvent.Prompt("plan it", false, emptyList()))
    this.thread.handleEvent(AgentEvent.ToolStarted("t0", "Read", ToolKind.READ, JsonObject(), null, null))
    this.thread.addPermission("r1", this.planPrompt("# Live", "t1"))
    this.thread.handleEvent(AgentEvent.Text("m1", "Waiting.", null))
    this.thread.handleEvent(AgentEvent.TurnEnded(10L, null, null))

    val plan = this.plans().single()
    val folds = this.thread.turns.flatMap { it.folds }
    assertFalse(folds.isEmpty())
    assertTrue(folds.none { plan in it.items })
  }

  fun testImplementingElsewhereDeclinesOnlyThePlansWaitingApproval() {
    this.thread.addPermission("r0", this.toolPrompt("t0"))
    this.thread.addPermission("r1", this.planPrompt("# A", "t1"))

    this.thread.onImplementedElsewhere(this.plans().single())

    assertEquals(listOf("r0"), this.thread.approvals.map { it.requestId })
    assertTrue("t1" in this.thread.declined)
    assertFalse("t0" in this.thread.declined)
  }

  fun testImplementingElsewhereLeavesAnAnsweredOrReplayedPlanAlone() {
    this.thread.addPermission("r1", this.planPrompt("# A", "t1"))
    val plan = this.plans().single()
    this.thread.answer(this.thread.approvals.single(), true)
    this.thread.addPermission("r2", this.toolPrompt("t2"))

    this.thread.onImplementedElsewhere(plan)
    this.thread.onImplementedElsewhere(TurnItem.Plan("# A"))

    assertEquals(listOf("r2"), this.thread.approvals.map { it.requestId })
    assertTrue(this.thread.declined.isEmpty())
  }

  fun testABlankPlanOffersNothing() {
    val before = this.store.all().size

    this.thread.implementElsewhere(TurnItem.Plan("  \n", "r1"), false)
    this.thread.savePlan(TurnItem.Plan(""))

    assertFalse(this.thread.canActOn(TurnItem.Plan(" ")))
    assertFalse(this.thread.implementing)
    assertEquals(before, this.store.all().size)
  }

  fun testNothingStartsWhileAThreadForAPlanIsBeingStarted() {
    val plan = TurnItem.Plan("# A")
    assertTrue(this.thread.canActOn(plan))
    this.thread.implementing = true
    val before = this.store.all().size

    this.thread.implementElsewhere(plan, true)
    this.thread.implementElsewhere(plan, false)

    assertFalse(this.thread.canActOn(plan))
    assertEquals(before, this.store.all().size)
  }

  fun testThePlanIsNotApprovedHereWhileANewThreadForItStarts() {
    this.thread.addPermission("r0", this.toolPrompt("t0"))
    this.thread.addPermission("r1", this.planPrompt("# A", "t1"))
    this.thread.implementing = true

    this.thread.answer(this.thread.approvals[1], true)
    this.thread.answer(this.thread.approvals[1], true, suggestion = 0)
    this.thread.answer(this.thread.approvals[0], true)

    assertEquals(listOf("r1"), this.thread.approvals.map { it.requestId })
    this.thread.answer(this.thread.approvals[0], false, "Keep planning.")
    assertTrue(this.thread.approvals.isEmpty())
  }

  fun testAPlanOfAThreadRemovedMeanwhileStartsNothingAndCanBeTriedAgain() {
    this.store.remove(this.thread.id)
    val before = this.store.all().size

    this.thread.implementElsewhere(TurnItem.Plan("# A"), false)

    assertFalse(this.thread.implementing)
    assertEquals(before, this.store.all().size)
  }
}
