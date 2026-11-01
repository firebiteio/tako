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

  private class Listed(val id: String, val parentId: String? = null, val pinned: Boolean = false,
    val snoozed: Boolean = false, val archived: Boolean = false)

  private fun pinnedTree(vararg items: Listed): String {
    val nodes = threadTree(pinnedFirst(items.toList()) { it.pinned }, { it.id }, { it.parentId })
    fun shape(nodes: List<ThreadNode<Listed>>): String = nodes.joinToString(" ") {
      if (it.children.isEmpty()) it.thread.id else it.thread.id + "(" + shape(it.children) + ")"
    }
    return shape(nodes)
  }

  @Test
  fun pinnedThreadsComeFirstKeepingTheirOrder() {
    assertEquals("c e a b d", this.pinnedTree(Listed("a"), Listed("b"), Listed("c", pinned = true), Listed("d"),
      Listed("e", pinned = true)))
  }

  @Test
  fun pinnedSubThreadComesFirstAmongItsSiblingsOnly() {
    assertEquals("x a(c b)", this.pinnedTree(Listed("a"), Listed("b", "a"), Listed("c", "a", pinned = true),
      Listed("x", pinned = true)))
  }

  @Test
  fun nothingPinnedKeepsTheOrder() {
    assertEquals("a b", this.pinnedTree(Listed("a"), Listed("b")))
    assertEquals("", this.pinnedTree())
  }

  private fun hidden(id: String, vararg items: Listed): Boolean {
    val byId = items.associateBy { it.id }
    return isSnoozeHidden(byId.getValue(id), { child -> child.parentId?.let { byId[it] } }, { it.snoozed },
      { it.archived })
  }

  @Test
  fun snoozeHidesTheThreadAndEverySubThreadUnderIt() {
    val items = arrayOf(Listed("a", snoozed = true), Listed("b", "a"), Listed("c", "b"), Listed("d"))

    assertTrue(this.hidden("a", *items))
    assertTrue(this.hidden("c", *items))
    assertFalse(this.hidden("d", *items))
  }

  @Test
  fun archivedOrMissingParentEndsTheSnooze() {
    assertFalse(this.hidden("b", Listed("a", snoozed = true, archived = true), Listed("b", "a")))
    assertFalse(this.hidden("a", Listed("a", snoozed = true, archived = true)))
    assertFalse(this.hidden("b", Listed("b", "closed")))
  }

  @Test
  fun parentCycleDoesNotHang() {
    assertFalse(this.hidden("a", Listed("a", "b"), Listed("b", "a")))
    assertTrue(this.hidden("a", Listed("a", "b"), Listed("b", "a", snoozed = true)))
  }
}
