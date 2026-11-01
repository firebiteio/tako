package io.firebite.tako.ui.state

import io.firebite.tako.ide.project.GitInfo

/** The header's pull request watch button. [number] names the pull request it watches or would watch. */
data class WatchButton(val number: String, val watching: Boolean, val starting: Boolean)

/**
 * The watch button for a thread, or null when there is nothing to watch: only an open GitHub pull request can be
 * watched. A running watch keeps its button even when the branch no longer has the pull request, so it can be stopped.
 */
fun watchButton(pr: GitInfo.PullRequest?, watching: String?, starting: Boolean): WatchButton? = when {
  watching != null -> WatchButton(watching, true, false)
  pr != null && pr.isGitHub && pr.state == "open" -> WatchButton(pr.number, false, starting)
  else -> null
}

/** Starts or stops watching the thread's pull request. Ignored while a start runs. */
fun ThreadState.toggleWatch() {
  if (this.watching != null) {
    this.workspace.service.unwatchPullRequest(this.id)
    return
  }
  if (this.watchStarting) {
    return
  }
  this.watchStarting = true
  this.workspace.service.watchPullRequest(this.id).thenRun { this.watchStarting = false }
}
