package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import kotlinx.coroutines.cancel

class WorkspaceArchiveTest : BasePlatformTestCase() {
  private lateinit var store: ThreadStore
  private lateinit var workspace: Workspace

  override fun setUp() {
    super.setUp()
    this.store = ThreadStore.getInstance(this.project)
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
    } finally {
      super.tearDown()
    }
  }

  private fun thread(): ThreadState {
    val record = this.store.create(null, null, null, "claude")
    val thread = ThreadState(this.workspace, record.toItem())
    this.workspace.threads[record.id] = thread
    return thread
  }

  private fun show(thread: ThreadState) {
    this.store.setActiveId(thread.id)
    this.workspace.active = thread
  }

  private fun isArchived(thread: ThreadState): Boolean = this.store.find(thread.id)!!.archived

  fun testUndoListsAThreadAgainWithoutLeavingTheShownOne() {
    val shown = this.thread()
    val other = this.thread()
    this.show(shown)

    this.workspace.archive(other)
    assertTrue(this.isArchived(other))
    this.workspace.undoArchive()

    assertFalse(this.isArchived(other))
    assertEquals(shown.id, this.store.activeId())
    assertNull(this.workspace.archivedToast)
  }

  fun testArchivingAWorkingThreadOffersNoUndo() {
    this.show(this.thread())
    this.workspace.archive(this.thread())
    assertNotNull(this.workspace.archivedToast)
    val working = this.thread()
    working.busy = true

    this.workspace.archive(working)

    assertTrue(this.isArchived(working))
    assertNull(this.workspace.archivedToast)
  }

  fun testUndoStaysOnAThreadPickedSinceAndClosesTheUnusedNewOne() {
    val archived = this.thread()
    val replacement = this.thread()
    val picked = this.thread()
    this.store.find(archived.id)!!.archived = true
    this.show(picked)
    this.workspace.archivedToast = ChatService.Archived(archived.id, replacement.id, true)

    this.workspace.undoArchive()

    assertFalse(this.isArchived(archived))
    assertEquals(picked.id, this.store.activeId())
    assertNull(this.store.find(replacement.id))
  }

  fun testUndoKeepsANewThreadWithADraft() {
    val archived = this.thread()
    val replacement = this.thread()
    this.store.find(archived.id)!!.archived = true
    this.show(this.thread())
    replacement.input.setTextAndPlaceCursorAtEnd("draft")
    this.workspace.archivedToast = ChatService.Archived(archived.id, replacement.id, true)

    this.workspace.undoArchive()

    assertNotNull(this.store.find(replacement.id))
  }
}
