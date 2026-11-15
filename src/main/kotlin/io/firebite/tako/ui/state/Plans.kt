package io.firebite.tako.ui.state

import io.firebite.tako.chat.Plans

/** Whether the plan can be saved or handed to another thread: it has text and no such thread is being started. */
fun ThreadState.canActOn(plan: TurnItem.Plan): Boolean = plan.plan.isNotBlank() && !this.implementing

/**
 * Starts a thread that implements the plan, once at a time. Once it started, the plan's approval, if still waiting,
 * is declined and this thread's agent stopped, so this thread doesn't carry out the plan as well. A plan already
 * approved here can still be handed to a new thread on purpose.
 *
 * @param worktree the new thread works in a new git worktree
 */
fun ThreadState.implementElsewhere(plan: TurnItem.Plan, worktree: Boolean) {
  if (!this.canActOn(plan)) {
    return
  }
  this.implementing = true
  this.workspace.service.plans().implement(this.id, plan.plan, worktree).whenComplete { created, _ ->
    this.implementing = false
    if (created != null) {
      this.onImplementedElsewhere(plan)
    }
  }
}

/** Declines the plan's approval, if still waiting, as another thread implements the plan. */
fun ThreadState.onImplementedElsewhere(plan: TurnItem.Plan) {
  val approval = this.approvals.firstOrNull { it.requestId == plan.requestId } ?: return
  this.answer(approval, false, Plans.DECLINE_MESSAGE, true)
}

/** Asks where to save the plan as Markdown, then opens the file. */
fun ThreadState.savePlan(plan: TurnItem.Plan) {
  if (plan.plan.isNotBlank()) {
    this.workspace.service.plans().save(this.id, plan.plan)
  }
}
