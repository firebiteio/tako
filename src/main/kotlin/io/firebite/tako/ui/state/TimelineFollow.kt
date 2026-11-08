package io.firebite.tako.ui.state

/** How long after opening a row the timeline's growth counts as that row's. */
private const val EXPANSION_MILLIS = 400L

/** What the timeline does about a change to its scroll position or length. */
enum class Follow { NOTHING, PIN, UNPIN, SCROLL_TO_END }

/**
 * Only the person's own scrolling unpins the timeline from its end, and scrolling back to the end pins it again. New
 * text keeps a pinned timeline at its end. A row the person just opened unpins it instead, so what they opened stays
 * where it is.
 *
 * @param isPinned       whether the timeline follows its end
 * @param isScrolling    whether a scroll is running
 * @param isAutoScroll   whether that scroll is the timeline following its end
 * @param canForward     whether there is more below
 * @param sinceExpansion milliseconds since the person last opened or closed a row
 */
fun follow(isPinned: Boolean, isScrolling: Boolean, isAutoScroll: Boolean, canForward: Boolean,
  sinceExpansion: Long): Follow = when {
  isScrolling && isAutoScroll -> Follow.NOTHING
  isScrolling -> if (canForward) Follow.UNPIN else Follow.PIN
  !isPinned || !canForward -> Follow.NOTHING
  sinceExpansion < EXPANSION_MILLIS -> Follow.UNPIN
  else -> Follow.SCROLL_TO_END
}

/** Call before opening or closing a row of the timeline, so the timeline doesn't jump. */
fun ThreadState.noteExpansion() {
  this.expandedAt = System.currentTimeMillis()
}
