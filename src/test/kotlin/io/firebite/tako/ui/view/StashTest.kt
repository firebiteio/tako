package io.firebite.tako.ui.view

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.PromptStash
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.state.removeStashed
import io.firebite.tako.ui.state.restoreStash
import io.firebite.tako.ui.state.toggleStash
import io.firebite.tako.ui.t
import javax.swing.KeyStroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

class StashTest : BasePlatformTestCase() {
  private lateinit var workspace: Workspace
  private lateinit var thread: ThreadState
  private lateinit var stash: PromptStash

  override fun setUp() {
    super.setUp()
    this.workspace = Workspace(ChatService.getInstance(this.project), this.project)
    this.thread = ThreadState(this.workspace, ChatMessage.ThreadItem("thread", "Chat", false, 0, false, 0.0, 0, null,
      null, null, null, "claude", 0, null, false, false, 0))
    this.stash = PromptStash.getInstance(this.project)
    this.clearStash()
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
      this.clearStash()
    } finally {
      super.tearDown()
    }
  }

  fun testStashKeepsTheRawTextAndClearsTheBox() {
    val context = ContextItem.create(ContextItem.Kind.FILE, "Parser.java", "class Parser")
    this.thread.input.setTextAndPlaceCursorAtEnd("    indented()\n")
    this.thread.contexts.add(context)

    this.await(this.thread.toggleStash())

    assertEquals("", this.thread.input.text.toString())
    assertTrue(this.thread.contexts.isEmpty())
    val prompt = this.stash.entries().single().prompt
    assertEquals("    indented()\n", prompt.text)
    assertEquals(listOf(context), prompt.context)
  }

  fun testEmptyBoxBringsBackTheOnlyDraft() {
    this.thread.input.setTextAndPlaceCursorAtEnd("Later")
    this.await(this.thread.toggleStash())

    this.await(this.thread.toggleStash())

    assertEquals("Later", this.thread.input.text.toString())
    assertTrue(this.stash.entries().isEmpty())
  }

  fun testSeveralDraftsOpenTheMenuNewestFirst() {
    this.stash.push(prompt("Older"))
    this.stash.push(prompt("Newer"))

    this.await(this.thread.toggleStash())

    assertEquals(listOf("Newer", "Older"), this.thread.stashMenu!!.map { it.label() })
    assertEquals("", this.thread.input.text.toString())
  }

  fun testPickingADraftAnotherThreadTookSaysSo() {
    val entry = this.stash.push(prompt("Taken"))
    this.stash.take(entry.id)

    this.await(this.thread.restoreStash(entry))

    assertEquals("", this.thread.input.text.toString())
    assertEquals(t("stash.gone"), this.thread.alerts.single().text)
  }

  fun testRemoveDeletesFromTheStashAndTheMenu() {
    val older = this.stash.push(prompt("Older"))
    val newer = this.stash.push(prompt("Newer"))
    this.thread.stashMenu = listOf(newer, older)

    this.await(this.thread.removeStashed(newer))

    assertEquals(listOf(older.id), this.stash.entries().map { it.id })
    assertEquals(listOf(older.id), this.thread.stashMenu!!.map { it.id })
    this.await(this.thread.removeStashed(older))
    assertNull(this.thread.stashMenu)
  }

  fun testShortcutGoesToTheStashOnlyInTheComposer() {
    val action = ActionManager.getInstance().getAction("Tako.StashPrompt")
    val saveAll = ActionManager.getInstance().getAction(SAVE_ALL)
    val composer = SimpleDataContext.getSimpleContext(COMPOSER, this.thread)
    val promoter = StashActionPromoter()

    assertEquals(listOf(action), promoter.promote(listOf(saveAll, action), composer))
    assertTrue(promoter.promote(listOf(saveAll, action), DataContext.EMPTY_CONTEXT).isEmpty())
    val inComposer = TestActionEvent.createTestEvent(action, composer)
    action.update(inComposer)
    assertTrue(inComposer.presentation.isEnabled)
    val elsewhere = TestActionEvent.createTestEvent(action, DataContext.EMPTY_CONTEXT)
    action.update(elsewhere)
    assertFalse(elsewhere.presentation.isEnabled)
  }

  fun testDefaultKeymapSharesSaveAllsShortcut() {
    val keymap = KeymapManager.getInstance().getKeymap("\$default")!!
    val ctrlS = KeyboardShortcut(KeyStroke.getKeyStroke("ctrl S"), null)

    assertTrue(keymap.getShortcuts("Tako.StashPrompt").contains(ctrlS))
    assertTrue(keymap.getShortcuts(SAVE_ALL).contains(ctrlS))
  }

  private fun clearStash() {
    for (entry in this.stash.entries()) {
      this.stash.take(entry.id)
    }
  }

  private fun await(job: Job) {
    PlatformTestUtil.waitWithEventsDispatching("The stash did not answer", { job.isCompleted }, 10)
  }

  private companion object {
    const val SAVE_ALL = "SaveAll"

    fun prompt(text: String) = ChatMessage.Submit(text, emptyList(), emptyList())
  }
}
