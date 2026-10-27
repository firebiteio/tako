package io.firebite.tako.ui.state

/** The prompts the thread sent, oldest first: each turn's prompt, its follow-ups, then the queued ones. */
fun ThreadState.sentPrompts(): List<String> {
  val prompts = ArrayList<String>()
  for (turn in this.turns) {
    turn.prompt?.let { prompts.add(it.text) }
    for (item in turn.items) {
      if (item is TurnItem.FollowUp) {
        prompts.add(item.prompt.text)
      }
    }
  }
  for (queued in this.queued) {
    prompts.add(queued.prompt.text)
  }
  prompts.removeAll { it.isBlank() }
  return prompts
}

/** Brings sent prompts back into an empty composer, newest first. */
class PromptRecall {
  /** How far back the shown prompt is, 0 when none is shown. */
  private var depth = 0
  /** The prompt put in the composer; once the text differs, it was edited into a draft. */
  private var shown: String? = null

  /**
   * Steps back or forward from the composer's [text] and caret.
   *
   * @return the text to show, empty to clear the composer, null when the key moves the caret instead
   */
  fun step(prompts: List<String>, text: String, caret: Int, back: Boolean): String? {
    if (text != this.shown) {
      this.depth = 0
      this.shown = null
      if (text.isNotEmpty()) {
        return null
      }
    }
    val onEdge = if (back) text.lastIndexOf('\n', caret - 1) < 0 else text.indexOf('\n', caret) < 0
    if (!onEdge) {
      return null
    }
    if (back) {
      if (this.depth >= prompts.size) {
        return null
      }
      this.depth++
    } else {
      if (this.depth == 0) {
        return null
      }
      this.depth--
    }
    this.shown = if (this.depth == 0) null else prompts.getOrNull(prompts.size - this.depth)
    return this.shown ?: ""
  }
}
