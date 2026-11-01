package io.firebite.tako.ui.state

import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ui.t

private val SIDE_COMMAND = Regex("^/(?:side|btw)(?:\\s+(.*))?$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** The names of Tako's own slash commands that start a side chat. */
val SIDE_COMMANDS = listOf("side", "btw")

/** The question of a typed `/side` or `/btw` prompt, empty without one; null for any other text. */
fun sideCommand(text: String): String? = SIDE_COMMAND.matchEntire(text.trim())?.let { it.groupValues[1].trim() }

/** How many prompts the leading done turns hold, each turn's prompt and follow-ups: what a fork can copy now. */
fun ThreadState.settledPrompts(): Int {
  var count = 0
  for (turn in this.turns) {
    if (!turn.done) {
      break
    }
    if (turn.prompt != null) {
      count++
    }
    count += turn.items.count { it is TurnItem.FollowUp }
  }
  return count
}

/** How many leading turns replay the prompts a side chat's session copied from its main thread. */
fun ThreadState.inheritedTurns(): Int {
  var prompts = 0
  var turns = 0
  for (turn in this.turns) {
    if (!turn.replayed) {
      break
    }
    val count = (if (turn.prompt != null) 1 else 0) + turn.items.count { it is TurnItem.FollowUp }
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

/** Whether the thread list shows the thread. */
fun Workspace.isListed(thread: ThreadState): Boolean = !this.isSideChat(thread)

/** The main thread's side chats, the latest active first. */
fun Workspace.sideChatsOf(main: ThreadState): List<ThreadState> =
  this.threads.values.filter { it.sideChat && it.parentId == main.id }.sortedByDescending { it.updatedAt }

/** The side chat the main thread's pane shows, null while it is hidden. */
fun Workspace.shownSideChat(main: ThreadState): ThreadState? =
  main.sideChatShown?.let { this.threads[it] }?.takeIf { it.parentId == main.id }

/** The thread is on screen: the active one, or the side chat shown beside it. */
val ThreadState.isShown: Boolean
  get() {
    if (this.isActive) {
      return true
    }
    val main = this.workspace.mainOf(this)
    return main !== this && main.isActive && main.sideChatShown == this.id
  }

/** Side chats of the main thread waiting for an approval or question that are not on screen. */
fun Workspace.waitingSideChats(main: ThreadState): Int =
  this.sideChatsOf(main).count { !it.archived && !it.isShown && it.approvals.isNotEmpty() }

/** A side chat's title, "Side chat" until its first prompt names it. */
fun sideChatTitle(thread: ThreadState): String =
  if (thread.title == ThreadStore.Record.DEFAULT_TITLE) t("side.untitled") else thread.title

/**
 * Asks the IDE for a side chat of the thread's main thread, with [question] as its first prompt or none when blank.
 * Copies the main thread's session when its agent can fork, up to its last done turn.
 *
 * @return false while a side chat of the same main thread is being made, so nothing starts
 */
fun ThreadState.startSideChat(question: String): Boolean {
  val main = this.workspace.mainOf(this)
  if (main.startingSideChat) {
    return false
  }
  val canFork = main.info?.capabilities?.fork == true
  main.startingSideChat = this.workspace.service.startSideChat(main.id, if (canFork) main.settledPrompts() else 0,
    question)
  return true
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
