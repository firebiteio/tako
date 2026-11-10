package io.firebite.tako.ui.state

/**
 * The threads whose parent is [parent], created by its agent: not side chats and not archived. Lowest [rank] first, so
 * the ones needing a look can lead, then the latest active first, as the thread list orders them.
 */
fun <T> childThreads(threads: Collection<T>, parent: String, parentId: (T) -> String?, sideChat: (T) -> Boolean,
  archived: (T) -> Boolean, rank: (T) -> Int, updatedAt: (T) -> Long): List<T> =
  threads.filter { parentId(it) == parent && !sideChat(it) && !archived(it) }
    .sortedWith(compareBy(rank).thenByDescending(updatedAt))

/** The thread's children, see [childThreads]. */
fun Workspace.childrenOf(thread: ThreadState, rank: (ThreadState) -> Int): List<ThreadState> =
  childThreads(this.threads.values, thread.id, { it.parentId }, { it.sideChat }, { it.archived }, rank,
    { it.updatedAt })
