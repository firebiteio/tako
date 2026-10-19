package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTreeTest {
  private class Item(val id: String, val parentId: String? = null)

  private fun tree(vararg items: Item): List<ThreadNode<Item>> = threadTree(items.toList(), { it.id }, { it.parentId })

  private fun shape(nodes: List<ThreadNode<Item>>): String = nodes.joinToString(" ") {
    if (it.children.isEmpty()) it.thread.id else it.thread.id + "(" + this.shape(it.children) + ")"
  }

  @Test
  fun subThreadsNestUnderTheirParentInListOrder() {
    val nodes = this.tree(Item("b", "a"), Item("a"), Item("c", "a"), Item("d"))

    assertEquals("a(b c) d", this.shape(nodes))
  }

  @Test
  fun subThreadsOfSubThreadsNestDeeper() {
    val nodes = this.tree(Item("c", "b"), Item("b", "a"), Item("a"))

    assertEquals("a(b(c))", this.shape(nodes))
  }

  @Test
  fun threadsWhoseParentIsNotListedStayAtTheTop() {
    val nodes = this.tree(Item("a"), Item("b", "closed"), Item("c", "b"))

    assertEquals("a b(c)", this.shape(nodes))
  }

  @Test
  fun anyLooksThroughEveryDepth() {
    val root = this.tree(Item("a"), Item("b", "a"), Item("c", "b")).single()

    assertTrue(root.any { it.id == "c" })
    assertFalse(root.any { it.id == "x" })
  }
}
