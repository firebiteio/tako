package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class NameListTest {
  @Test
  fun listsAFewNames() {
    assertEquals("a.ts, b.ts", nameList(listOf("a.ts", "b.ts")))
  }

  @Test
  fun countsWhatDoesNotFit() {
    assertEquals("a, b, c +2", nameList(listOf("a", "b", "c", "d", "e")))
  }

  @Test
  fun namesAFileReadTwiceOnce() {
    assertEquals("a.ts", nameList(listOf("a.ts", "a.ts")))
  }

  @Test
  fun shortensLongNames() {
    assertEquals("x".repeat(40) + "…", nameList(listOf("x".repeat(60))))
  }
}
