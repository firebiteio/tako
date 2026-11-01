package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.firebite.tako.chat.thread.QueuedPrompts
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.t

/** Whether queued prompts are still to go once the running turn ends. */
val ThreadState.queueGoesOn: Boolean
  get() = this.queued.any { it.status != QueuedPrompts.Status.HELD } || (!this.queueHeld && this.queued.isNotEmpty())

/** The agent takes prompts into its running turn. */
val ThreadState.canSteer: Boolean
  get() = this.info?.capabilities?.steer == true

/** How a prompt sent now goes while a turn runs: the setting's way, or the other one with [opposite]. */
fun ThreadState.followUpDelivery(opposite: Boolean): QueuedPrompts.Delivery =
  followUpDelivery(this.workspace.steers, opposite, this.canSteer)

/** Steers when the setting says so, or with [opposite] when it doesn't, if the agent can; else queues. */
fun followUpDelivery(steers: Boolean, opposite: Boolean, canSteer: Boolean): QueuedPrompts.Delivery =
  if (canSteer && steers != opposite) QueuedPrompts.Delivery.STEER else QueuedPrompts.Delivery.QUEUE

/**
 * Follows the IDE's queue: each prompt's status and their order. A prompt it no longer lists stays until the agent's
 * take of it arrives, or the person took it out. Once nothing runs, a thread whose last turn ended is done.
 */
fun ThreadState.applyQueue(message: ChatMessage.Queue) {
  this.queueHeld = message.held
  val order = HashMap<Int, Int>()
  for ((index, item) in message.items.withIndex()) {
    order[item.turn] = index
  }
  val slots = ArrayList<Int>()
  for ((index, queued) in this.queued.withIndex()) {
    val at = order[queued.no] ?: continue
    queued.status = message.items[at].status
    slots.add(index)
  }
  val listed = slots.map { this.queued[it] }
  val sorted = listed.sortedBy { order[it.no] }
  if (sorted != listed) {
    for ((index, slot) in slots.withIndex()) {
      this.queued[slot] = sorted[index]
    }
  }
  if (!message.running && this.busy && this.turn?.done != false && !this.startingBench && this.rewinding == null) {
    this.setBusy(false)
    this.finished = true
    if (!this.isActive) {
      this.unread = true
    }
  }
}

/** The queued prompts went to the stash as the thread closed. */
fun ThreadState.onQueueStashed(count: Int) {
  this.queued.clear()
  this.queueHeld = false
  this.showAlert(t("queue.stashed", count))
}

/** Whether a held prompt waits before this one, so it can move up. */
fun ThreadState.canMoveUp(queued: QueuedPrompt): Boolean {
  for (other in this.queued) {
    if (other === queued) {
      return false
    }
    if (other.status == QueuedPrompts.Status.HELD) {
      return true
    }
  }
  return false
}

/** Takes the queued prompt back into the box, after what is typed there. */
fun ThreadState.editQueued(queued: QueuedPrompt) {
  val prompt = this.controller?.unqueue(queued.no) ?: return
  this.queued.remove(queued)
  this.input.setTextAndPlaceCursorAtEnd(mergeDraft(this.input.text.toString(), prompt.text))
  this.contexts.addAll(prompt.context)
  this.images.addAll(prompt.images.map { PendingImage(it) })
  this.workspace.focusComposer++
}

/** Deletes the queued prompt, with the files of its pasted text. */
fun ThreadState.removeQueued(queued: QueuedPrompt) {
  val controller = this.controller ?: return
  val prompt = controller.unqueue(queued.no) ?: return
  this.queued.remove(queued)
  for (item in prompt.context) {
    controller.discardPaste(item)
  }
}

fun ThreadState.moveQueuedUp(queued: QueuedPrompt) {
  this.controller?.moveQueuedUp(queued.no)
}

/** Sends the queued prompt next, stopping the running turn, or at once when none runs. */
fun ThreadState.sendQueuedNow(queued: QueuedPrompt) {
  val controller = this.controller ?: return
  if (controller.sendQueuedNow(queued.no) == QueuedPrompts.SendNow.SEND) {
    this.finished = false
    this.failed = false
    this.setBusy(true)
  }
  this.scrollToEnd++
}

/** Hands the queued prompt to the running turn. */
fun ThreadState.steerQueued(queued: QueuedPrompt) {
  this.controller?.steerQueued(queued.no)
}

/** The draft with [text] after it, an empty line between. */
fun mergeDraft(draft: String, text: String): String = when {
  draft.isBlank() -> text
  text.isEmpty() -> draft
  else -> draft.trimEnd() + "\n\n" + text
}
