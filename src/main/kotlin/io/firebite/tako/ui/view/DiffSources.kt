package io.firebite.tako.ui.view

import io.firebite.tako.ide.project.GitDiffs
import io.firebite.tako.ui.ago
import io.firebite.tako.ui.t

/**
 * The diff menu of a thread's checkout: its uncommitted changes, its branch against the base branch, then its commits.
 * Without sources, one entry tells [error].
 * @param now the time commits are dated against
 */
fun diffMenu(sources: GitDiffs.Sources?, error: String?, now: Long, open: (GitDiffs.Source) -> Unit): List<MenuEntry> {
  if (sources == null) {
    return listOf(MenuEntry(t("diff.git.error"), error, Icons.error, enabled = false) {})
  }
  val entries = mutableListOf<MenuEntry>()
  entries += if (sources.uncommitted != 0) {
    val description = if (sources.uncommitted == GitDiffs.UNKNOWN) t("diff.git.error")
    else t("diff.uncommitted.count", sources.uncommitted)
    MenuEntry(t("diff.uncommitted"), description, Icons.diff) { open(GitDiffs.Uncommitted()) }
  } else {
    MenuEntry(t("diff.uncommitted"), t("diff.uncommitted.none"), Icons.diff, enabled = false) {}
  }
  val branch = sources.branch
  val base = sources.base ?: ""
  entries += when {
    branch != null -> MenuEntry(t("diff.branch.base", branch.base),
      t("diff.branch.commits", sources.branchCommits, branch.mergeBaseShort), Icons.branch) { open(branch) }
    sources.branchState == GitDiffs.BranchState.NO_OWN_COMMITS ->
      MenuEntry(t("diff.branch.base", base), t("diff.branch.no.own", base), Icons.branch, enabled = false) {}
    sources.branchState == GitDiffs.BranchState.UNBORN ->
      MenuEntry(t("diff.branch"), t("diff.branch.unborn"), Icons.branch, enabled = false) {}
    else -> MenuEntry(t("diff.branch"), t("diff.branch.no.base"), Icons.branch, enabled = false) {}
  }
  for (commit in sources.commits) {
    entries += MenuEntry(commit.subject.ifBlank { t("diff.commit.no.message") },
      t("diff.commit.meta", commit.shortSha, ago(commit.timeMillis, now)), Icons.todoActive) { open(commit) }
  }
  return entries
}
