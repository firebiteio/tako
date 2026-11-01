package io.firebite.tako.ui.view

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.ide.project.GitDiffs

class DiffSourcesTest : BasePlatformTestCase() {
  private val opened = mutableListOf<GitDiffs.Source>()
  private val now = 10L * 24 * 3600 * 1000
  private val commit = GitDiffs.Commit("a".repeat(40), "aaaaaaa", "Fix the login", now - 2 * 3600 * 1000)
  private val branch = GitDiffs.Branch("origin/main", "1".repeat(40), "1234567", "c".repeat(40), "feature (ccccccc)")

  fun testAnErrorIsTheOnlyEntry() {
    val entries = diffMenu(null, "fatal: not a git repository", this.now) { this.opened += it }

    assertEquals(1, entries.size)
    assertEquals("Can't read git", entries[0].label)
    assertEquals("fatal: not a git repository", entries[0].description)
    assertFalse(entries[0].enabled)
  }

  fun testABranchWithCommitsOffersEverySource() {
    val entries = diffMenu(this.sources(3, GitDiffs.BranchState.OK, 1, listOf(this.commit)), null, this.now) {
      this.opened += it
    }

    assertEquals(listOf("Uncommitted changes", "Branch vs origin/main", "Fix the login"), entries.map { it.label })
    assertEquals("3 changed files", entries[0].description)
    assertEquals("1 commit since 1234567", entries[1].description)
    assertTrue(entries[2].description!!.startsWith("aaaaaaa · "))
    assertTrue(entries.all { it.enabled })
    for (entry in entries) {
      entry.run()
      entry.run()
    }
    assertEquals(listOf(GitDiffs.Uncommitted(), GitDiffs.Uncommitted(), this.branch, this.branch, this.commit,
      this.commit), this.opened)
  }

  fun testNothingToShowIsDisabled() {
    val entries = diffMenu(this.sources(0, GitDiffs.BranchState.NO_OWN_COMMITS, 0, listOf()), null, this.now) {
      this.opened += it
    }

    assertEquals(listOf("Nothing uncommitted", "No commits of its own since origin/main"),
      entries.map { it.description })
    assertTrue(entries.none { it.enabled })
  }

  fun testBranchStatesWithoutABaseExplainThemselves() {
    val unborn = diffMenu(this.sources(1, GitDiffs.BranchState.UNBORN, 0, listOf(), null), null, this.now) {}
    val noBase = diffMenu(this.sources(2, GitDiffs.BranchState.NO_BASE, 0, listOf(this.commit), null), null,
      this.now) {}

    assertEquals("1 changed file", unborn[0].description)
    assertEquals("Branch", unborn[1].label)
    assertEquals("No commits yet", unborn[1].description)
    assertFalse(unborn[1].enabled)
    assertEquals("No base branch found", noBase[1].description)
    assertFalse(noBase[1].enabled)
    assertEquals(3, noBase.size)
  }

  fun testUnreadableUncommittedChangesStayClickableToShowWhy() {
    val entries = diffMenu(this.sources(GitDiffs.UNKNOWN, GitDiffs.BranchState.OK, 1, listOf()), null, this.now) {
      this.opened += it
    }

    assertEquals("Can't read git", entries[0].description)
    assertTrue(entries[0].enabled)
    entries[0].run()
    assertEquals(listOf<GitDiffs.Source>(GitDiffs.Uncommitted()), this.opened)
  }

  fun testACommitWithoutSubjectStillHasALabel() {
    val blank = GitDiffs.Commit("b".repeat(40), "bbbbbbb", "  ", this.now)

    val entries = diffMenu(this.sources(0, GitDiffs.BranchState.OK, 1, listOf(blank)), null, this.now) {}

    assertEquals("(no message)", entries[2].label)
  }

  private fun sources(uncommitted: Int, state: GitDiffs.BranchState, branchCommits: Int,
    commits: List<GitDiffs.Commit>, base: String? = "origin/main"): GitDiffs.Sources =
    GitDiffs.Sources(uncommitted, state, base, if (state == GitDiffs.BranchState.OK) this.branch else null,
      branchCommits, commits)
}
