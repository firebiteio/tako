package io.firebite.tako.ui.state

import io.firebite.tako.agent.ImageAttachment
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.t

/** The prompts the agent got in the turn, as its session counts them: the turn's prompt and its follow-ups. */
private val Turn.promptCount: Int
  get() = (if (this.prompt != null) 1 else 0) + this.items.count { it is TurnItem.FollowUp }

/** How many prompts the leading done turns hold: what a fork can copy now. */
fun ThreadState.settledPrompts(): Int {
  var count = 0
  for (turn in this.turns) {
    if (!turn.done) {
      break
    }
    count += turn.promptCount
  }
  return count
}

/** How many leading turns replay the prompts a side chat's session copied from its main thread. */
fun ThreadState.inheritedTurns(): Int {
  var prompts = 0
  var turns = 0
  for (turn in this.turns) {
    if (!turn.replayed || prompts >= this.inheritedPrompts) {
      break
    }
    val count = turn.promptCount
    if (prompts + count > this.inheritedPrompts) {
      break
    }
    prompts += count
    turns++
  }
  return turns
}

/** The turns a side chat shows: all but the ones its main thread had. */
val ThreadState.ownTurns: List<Turn>
  get() = if (this.inheritedPrompts > 0) this.turns.drop(this.inheritedTurns()) else this.turns

/** The main thread of a side chat while the chat holds it, else the thread itself. */
fun Workspace.mainOf(thread: ThreadState): ThreadState =
  if (thread.sideChat) thread.parentId?.let { this.threads[it] } ?: thread else thread

/** A side chat whose main thread the chat holds; it shows in that thread's pane only. */
fun Workspace.isSideChat(thread: ThreadState): Boolean = this.mainOf(thread) !== thread

/** The main thread's side chats, the latest active first. */
fun Workspace.sideChatsOf(main: ThreadState): List<ThreadState> =
  this.threads.values.filter { it.sideChat && it.parentId == main.id }.sortedByDescending { it.updatedAt }

/** The side chat the main thread's pane shows, null while it is hidden. */
fun Workspace.shownSideChat(main: ThreadState): ThreadState? =
  main.sideChatShown?.let { this.threads[it] }?.takeIf { it.parentId == main.id }

/** The thread is on screen: the active one unless its side chat covers it, or the side chat shown beside it. */
val ThreadState.isShown: Boolean
  get() {
    if (this.isActive) {
      return !this.workspace.sidePaneCovers || this.workspace.shownSideChat(this) == null
    }
    val main = this.workspace.mainOf(this)
    return main !== this && main.isActive && main.sideChatShown == this.id
  }

/** Side chats of the main thread off screen that wait for an approval or question, or have an unread answer. */
fun Workspace.waitingSideChats(main: ThreadState): Int = this.threads.values.count {
  it.sideChat && it.parentId == main.id && !it.archived && !it.isShown && (it.approvals.isNotEmpty() || it.unread)
}

/** Whether a side chat of the main thread waits for an approval or question. */
fun Workspace.hasAskingSideChat(main: ThreadState): Boolean = this.threads.values.any {
  it.sideChat && it.parentId == main.id && !it.archived && it.approvals.isNotEmpty()
}

/** A side chat's title, "Side chat" until its first prompt names it. */
fun sideChatTitle(thread: ThreadState): String =
  if (thread.title == ThreadStore.Record.DEFAULT_TITLE) t("side.untitled") else thread.title

/**
 * Asks the IDE for a side chat of the thread's main thread, with [question] and the attachments as its first prompt;
 * with a blank question they wait in its composer. Copies the main thread's session when its agent can fork, up to its
 * last done turn.
 *
 * @return false when nothing started: a side chat of the same main thread is being made, or the thread is gone
 */
fun ThreadState.startSideChat(question: String, contexts: List<ContextItem> = emptyList(),
  images: List<ImageAttachment> = emptyList()): Boolean {
  val main = this.workspace.mainOf(this)
  if (main.startingSideChat) {
    return false
  }
  // Before the main agent started its capabilities are unknown; a fork it can't make falls back to an excerpt.
  val canFork = main.info?.capabilities?.fork != false
  val started = this.workspace.service.startSideChat(main.id, if (canFork) main.settledPrompts() else 0,
    ChatMessage.Submit(question, contexts, images))
  main.startingSideChat = started
  return started
}

/**
 * Shows the side chat in its main thread's pane and starts its agent if needed. A just [started] one lets the main
 * thread start the next.
 */
fun Workspace.showSideChat(id: String, started: Boolean) {
  val side = this.threads[id] ?: return
  val main = this.mainOf(side)
  if (started) {
    main.startingSideChat = false
  }
  if (main === side || main.archived) {
    return
  }
  main.sideChatShown = side.id
  side.unread = false
  if (main.isActive) {
    side.requestFocus()
  }
  this.service.openSideChat(side.id)
}

/** The header button: shows the latest side chat, starts the first one, or hides the pane. */
fun Workspace.toggleSideChats(main: ThreadState) {
  if (main.sideChatShown != null) {
    main.sideChatShown = null
    return
  }
  val latest = this.sideChatsOf(main).firstOrNull()
  if (latest != null) {
    this.showSideChat(latest.id, false)
  } else {
    main.startSideChat("")
  }
}

/** Closes the side chat; its pane shows the next one, or hides after the last. */
fun Workspace.closeSideChat(side: ThreadState) {
  val main = this.mainOf(side)
  if (main.sideChatShown == side.id) {
    val next = this.sideChatsOf(main).firstOrNull { it !== side }
    main.sideChatShown = null
    next?.let { this.showSideChat(it.id, false) }
  }
  this.service.close(side.id)
}

/** Focuses the thread's own composer: a side chat's in the pane, else the main one. */
fun ThreadState.requestFocus() {
  if (this.sideChat) {
    this.pendingFocus = true
    this.focusRequests++
  } else {
    this.workspace.focusComposer++
  }
}

/** Whether a side chat's answers can go to its main thread's composer now. */
val ThreadState.canBringBack: Boolean
  get() {
    val main = this.workspace.mainOf(this)
    return main !== this && main.canQuote
  }

/**
 * Quotes the side chat's answer in its main thread's composer, under the side chat's title. Hides a pane that covers
 * the main thread, so its composer shows.
 */
fun ThreadState.bringBack(answer: String) {
  if (!this.canBringBack || answer.isBlank()) {
    return
  }
  val main = this.workspace.mainOf(this)
  main.quote(t("side.bring.back.quote", sideChatTitle(this)) + "\n\n" + answer.trim())
  if (this.workspace.sidePaneCovers) {
    main.sideChatShown = null
  }
}
