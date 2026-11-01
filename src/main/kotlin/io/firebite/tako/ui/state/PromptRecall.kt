package io.firebite.tako.ui.state

/** The prompts the thread sent, oldest first; a side chat's own only. */
fun ThreadState.sentPrompts(): List<String> = sentPrompts(this.ownTurns, this.queued)

/** The texts of each turn's prompt, its follow-ups, then the queued prompts, without blank ones. */
fun sentPrompts(turns: List<Turn>, queued: List<QueuedPrompt>): List<String> {
  val prompts = ArrayList<String>()
  for (turn in turns) {
    turn.prompt?.let { prompts.add(it.text) }
    for (item in turn.items) {
      if (item is TurnItem.FollowUp) {
        prompts.add(item.prompt.text)
      }
    }
  }
  for (waiting in queued) {
    prompts.add(waiting.prompt.text)
  }
  prompts.removeAll { it.isBlank() }
  return prompts
}

/** Brings sent prompts back into an empty composer, newest first. */
class PromptRecall {
  /** The shown prompt's place among the sent ones, counted from the oldest; -1 when none is shown. */
  private var index = -1
  /** The prompt put in the composer; once the text differs, it was edited into a draft. */
  private var shown: String? = null

  /** Whether [text] is a recalled prompt, untouched. */
  fun shows(text: String): Boolean = this.shown != null && text == this.shown

  /** Whether the arrows step through prompts with [text] in the composer: it is empty or an untouched recalled prompt. */
  fun takes(text: String): Boolean {
    if (!this.shows(text)) {
      this.index = -1
      this.shown = null
    }
    return text.isEmpty() || this.shown != null
  }

  /** @return the prompt before the shown one, or null at the oldest */
  fun back(prompts: List<String>): String? {
    val next = (if (this.index < 0) prompts.size else this.index.coerceAtMost(prompts.size)) - 1
    if (next < 0) {
      return null
    }
    return this.show(prompts, next)
  }

  /** @return the prompt after the shown one, empty past the newest, or null when none is shown */
  fun forward(prompts: List<String>): String? {
    if (this.index < 0) {
      return null
    }
    return this.show(prompts, this.index + 1)
  }

  private fun show(prompts: List<String>, index: Int): String {
    val prompt = prompts.getOrNull(index)
    this.index = if (prompt == null) -1 else index
    this.shown = prompt
    return prompt ?: ""
  }
}
