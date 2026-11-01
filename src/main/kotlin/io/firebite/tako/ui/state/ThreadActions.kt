package io.firebite.tako.ui.state

/** What the thread menu offers. */
enum class ThreadAction {
  PIN,
  UNPIN,
  SNOOZE,
  WAKE,
  MARK_UNREAD,
  RENAME,
  REGENERATE_TITLE,
}

/**
 * The thread menu's actions in their order. Runs of a parallel run keep their place and name, so they are not pinned,
 * snoozed or renamed. Archived threads can only be renamed and titled.
 */
fun Workspace.threadActions(thread: ThreadState): List<ThreadAction> {
  val actions = ArrayList<ThreadAction>()
  val isRun = this.runOf(thread) != null
  if (!thread.archived && !isRun) {
    actions.add(if (thread.pinned) ThreadAction.UNPIN else ThreadAction.PIN)
    actions.add(if (this.isHidden(thread)) ThreadAction.WAKE else ThreadAction.SNOOZE)
  }
  val hasContent = thread.hasSession || thread.turns.isNotEmpty()
  if (!thread.archived && !thread.unread && thread !== this.active && hasContent) {
    actions.add(ThreadAction.MARK_UNREAD)
  }
  if (!isRun) {
    actions.add(ThreadAction.RENAME)
  }
  actions.add(ThreadAction.REGENERATE_TITLE)
  return actions
}

/** Whether a snooze hides the thread from the list: its own or one of its parents'. */
fun Workspace.isHidden(thread: ThreadState): Boolean =
  isSnoozeHidden(thread, this::parentOf, { it.snoozedUntil > 0 }, { it.archived })

/** Whether the thread or a sub thread under it waits for an answer, which a snooze would hide. */
fun Workspace.waitsForAnswer(thread: ThreadState): Boolean = this.threads.values.any { waiting ->
  waiting.approvals.isNotEmpty() && lineage(waiting, this::parentOf).any { it === thread }
}

private fun Workspace.parentOf(thread: ThreadState): ThreadState? = thread.parentId?.let { this.threads[it] }
