package io.firebite.tako.ui.state

import io.firebite.tako.ui.bridge.ChatMessage

/**
 * The typed texts of the prompts the agent got, up to and including the one that starts [turn], in the order its
 * session keeps them: each turn's prompt, then the follow-ups the turn took.
 */
fun ThreadState.promptsUpTo(turn: Turn): List<String> {
  val texts = ArrayList<String>()
  for (each in this.sessionTurns()) {
    val prompt = each.prompt
    if (each === turn) {
      prompt?.let { texts.add(it.text) }
      return texts
    }
    prompt?.let { texts.add(it.text) }
    for (item in each.items) {
      if (item is TurnItem.FollowUp) {
        texts.add(item.prompt.text)
      }
    }
  }
  return texts
}

/**
 * The turns of the current agent's session: those after the last agent switch. Earlier agents' turns are not in the
 * session, so it cannot rewind or fork to them.
 */
fun ThreadState.sessionTurns(): List<Turn> {
  val lastSwitch = this.turns.indexOfLast { turn -> turn.items.any { it is TurnItem.Divider } }
  return if (lastSwitch < 0) this.turns else this.turns.subList(lastSwitch + 1, this.turns.size)
}

/** How many prompts the agent got or holds, which is the number of the next prompt's file checkpoint. */
fun ThreadState.promptCount(): Int {
  var count = this.queued.size
  for (turn in this.sessionTurns()) {
    if (turn.prompt != null) {
      count++
    }
    count += turn.items.count { it is TurnItem.FollowUp }
  }
  return count
}

/**
 * Whether the thread can go back to before [turn]'s prompt now: the agent can rewind and waits for nothing. Not side
 * chats: their files are their main thread's.
 */
fun ThreadState.canRewind(turn: Turn): Boolean =
  !this.sideChat && this.info?.capabilities?.rewind == true && turn.prompt != null && turn.done &&
    turn in this.sessionTurns() && !this.busy &&
    this.approvals.isEmpty() && this.question == null && this.queued.isEmpty() && this.rewinding == null

/** Has the agent forget [turn]'s prompt and all after it, and with [files] puts the files back to then. */
fun ThreadState.rewind(turn: Turn, files: Boolean) {
  val prompt = turn.prompt ?: return
  if (!this.canRewind(turn)) {
    return
  }
  val controller = this.controller ?: return
  this.rewinding = prompt
  this.setBusy(true)
  controller.rewind(this.promptsUpTo(turn), files)
}

/**
 * A rewind ended. When the agent forgot the prompt, it goes back into the box to edit, and a draft there moves to the
 * stash first.
 */
fun ThreadState.onRewound(rewound: Boolean, error: String?) {
  val prompt = this.rewinding
  this.rewinding = null
  this.setBusy(false)
  if (rewound && prompt != null) {
    if (this.typed.isNotEmpty() || this.images.isNotEmpty() || this.contexts.isNotEmpty()) {
      this.toggleStash()
    }
    this.putBack(ChatMessage.Submit(prompt.text, prompt.contexts.filter { it.text.isNotEmpty() }, prompt.images))
    this.workspace.focusComposer++
  }
  error?.let { this.showAlert(it) }
}
