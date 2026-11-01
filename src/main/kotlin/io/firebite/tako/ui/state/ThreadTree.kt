package io.firebite.tako.ui.state

/** A thread in the list with the sub threads its agent created, which the list nests under it. */
class ThreadNode<T>(val thread: T, val children: List<ThreadNode<T>>) {
  /** Whether the thread or one of its sub threads, at any depth, passes the test. */
  fun any(test: (T) -> Boolean): Boolean = test(this.thread) || this.children.any { it.any(test) }
}

/**
 * Nests each thread under its parent, keeping the list's order among siblings. A thread whose parent is not in the
 * list, because it was closed, archived or is in another folder, stays at the top.
 */
fun <T> threadTree(threads: List<T>, id: (T) -> String, parentId: (T) -> String?): List<ThreadNode<T>> {
  val ids = HashSet<String>()
  for (thread in threads) {
    ids.add(id(thread))
  }
  val children = HashMap<String, MutableList<T>>()
  val roots = ArrayList<T>()
  for (thread in threads) {
    val parent = parentId(thread)
    if (parent != null && parent in ids) {
      children.getOrPut(parent) { ArrayList() }.add(thread)
    } else {
      roots.add(thread)
    }
  }
  fun node(thread: T): ThreadNode<T> = ThreadNode(thread, children[id(thread)].orEmpty().map(::node))
  return roots.map(::node)
}

/** The pinned threads, then the others, each in the list's order; [threadTree] keeps that among siblings too. */
fun <T> pinnedFirst(threads: List<T>, pinned: (T) -> Boolean): List<T> {
  val (first, rest) = threads.partition(pinned)
  return first + rest
}

/**
 * Whether a snooze hides the thread: its own or one of its parents'. An archived thread is never hidden, and an
 * archived or missing parent ends the walk, as the list does not nest the thread under it. Stops on a parent cycle.
 */
fun <T : Any> isSnoozeHidden(thread: T, parent: (T) -> T?, snoozed: (T) -> Boolean, archived: (T) -> Boolean): Boolean =
  lineage(thread, parent).takeWhile { !archived(it) }.any(snoozed)

/** The thread and its parents, nearest first, ending at a missing parent or where the parents loop. */
fun <T : Any> lineage(thread: T, parent: (T) -> T?): Sequence<T> {
  val seen = HashSet<T>()
  return generateSequence(thread, parent).takeWhile { seen.add(it) }
}
