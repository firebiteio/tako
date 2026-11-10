package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class ChildrenTest {
  private class Item(val id: String, val parentId: String? = null, val sideChat: Boolean = false,
    val archived: Boolean = false, val rank: Int = 0, val updatedAt: Long = 0)

  private fun children(parent: String, vararg items: Item): String =
    childThreads(items.toList(), parent, { it.parentId }, { it.sideChat }, { it.archived }, { it.rank },
      { it.updatedAt }).joinToString(" ") { it.id }

  @Test
  fun onlyTheThreadsItsAgentCreatedCount() {
    assertEquals("b c", this.children("a", Item("a"), Item("b", "a", updatedAt = 2), Item("c", "a", updatedAt = 1),
      Item("d", "x"), Item("e")))
  }

  @Test
  fun grandchildrenBelongToTheirOwnParent() {
    assertEquals("b", this.children("a", Item("b", "a"), Item("c", "b")))
    assertEquals("c", this.children("b", Item("b", "a"), Item("c", "b")))
  }

  @Test
  fun sideChatsAndArchivedThreadsAreLeftOut() {
    assertEquals("b", this.children("a", Item("b", "a"), Item("c", "a", sideChat = true),
      Item("d", "a", archived = true)))
  }

  @Test
  fun noChildrenGiveAnEmptyList() {
    assertEquals("", this.children("a", Item("a"), Item("b")))
    assertEquals("", this.children("a"))
  }

  @Test
  fun theLatestActiveComesFirst() {
    assertEquals("c a b", this.children("p", Item("a", "p", updatedAt = 5), Item("b", "p", updatedAt = 1),
      Item("c", "p", updatedAt = 9)))
  }

  @Test
  fun lowerRankComesBeforeNewerActivity() {
    assertEquals("b c a", this.children("p", Item("a", "p", rank = 2, updatedAt = 9), Item("b", "p", rank = 0,
      updatedAt = 1), Item("c", "p", rank = 1, updatedAt = 5)))
  }
}
