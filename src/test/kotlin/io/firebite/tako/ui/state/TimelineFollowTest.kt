package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineFollowTest {
  private val longAgo = 60_000L

  @Test
  fun scrollingUpUnpins() {
    assertEquals(Follow.UNPIN, follow(true, true, false, true, this.longAgo))
  }

  @Test
  fun scrollingBackToTheEndPins() {
    assertEquals(Follow.PIN, follow(false, true, false, false, this.longAgo))
  }

  @Test
  fun followingTheEndNeverUnpins() {
    assertEquals(Follow.NOTHING, follow(true, true, true, true, this.longAgo))
  }

  @Test
  fun newTextKeepsAPinnedTimelineAtItsEnd() {
    assertEquals(Follow.SCROLL_TO_END, follow(true, false, false, true, this.longAgo))
  }

  @Test
  fun anUnpinnedTimelineStaysPut() {
    assertEquals(Follow.NOTHING, follow(false, false, false, true, this.longAgo))
  }

  @Test
  fun openingARowUnpinsInsteadOfJumping() {
    assertEquals(Follow.UNPIN, follow(true, false, false, true, 50))
  }

  @Test
  fun openingARowThatStillFitsKeepsThePin() {
    assertEquals(Follow.NOTHING, follow(true, false, false, false, 50))
  }
}
