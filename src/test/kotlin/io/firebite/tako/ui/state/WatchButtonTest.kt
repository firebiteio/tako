package io.firebite.tako.ui.state

import io.firebite.tako.ide.project.GitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchButtonTest {
  private fun pr(number: String, state: String, url: String = "https://github.com/o/r/pull/12") =
    GitInfo.PullRequest(number, "Title", url, state)

  @Test
  fun hiddenWithoutAnOpenPullRequest() {
    assertNull(watchButton(null, null, false))
    assertNull(watchButton(pr("#12", "merged"), null, false))
    assertNull(watchButton(pr("#12", "closed"), null, false))
    assertNull(watchButton(pr("!7", "open", "https://example.com/o/r/7"), null, false))
  }

  @Test
  fun offeredForAnOpenPullRequest() {
    assertEquals(WatchButton("#12", watching = false, starting = false), watchButton(pr("#12", "open"), null, false))
    assertEquals(WatchButton("#12", watching = false, starting = true), watchButton(pr("#12", "open"), null, true))
  }

  @Test
  fun offeredForAnOpenGitLabMergeRequest() {
    val mergeRequest = pr("!7", "open", "https://git.example.com/group/sub/app/-/merge_requests/7")
    assertEquals(WatchButton("!7", watching = false, starting = false), watchButton(mergeRequest, null, false))
  }

  @Test
  fun runningWatchKeepsItsButtonWhenTheBranchMovedOn() {
    assertEquals(WatchButton("#12", watching = true, starting = false), watchButton(null, "#12", false))
    assertEquals(WatchButton("#12", watching = true, starting = false), watchButton(pr("#13", "open"), "#12", true))
    assertEquals(WatchButton("#12", watching = true, starting = false), watchButton(pr("#12", "merged"), "#12", false))
  }
}
