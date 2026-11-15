package io.firebite.tako.ui.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ui.bridge.message.thread.Setup
import io.firebite.tako.ui.bridge.message.thread.SetupPhase
import io.firebite.tako.ui.view.setupTail
import kotlinx.coroutines.cancel

class SetupStateTest : BasePlatformTestCase() {
  private lateinit var workspace: Workspace

  override fun setUp() {
    super.setUp()
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
    val record = ThreadStore.getInstance(this.project).create(null, null, null, "claude")
    return ThreadState(this.workspace, record.toItem())
  }

  private fun setup(phase: SetupPhase, steps: Int = 2, error: String? = null) =
    Setup(phase, 1, steps, "npm ci", "added 1 package\n", error, 1000, if (phase ==
      SetupPhase.RUNNING) 0 else 43_000)

  private fun ThreadState.notes(): List<String> =
    this.turns.flatMap { turn -> turn.items.filterIsInstance<TurnItem.Note>().map { it.text } }

  fun testACardShowsWhileTheSetupRunsOrFailed() {
    val thread = this.thread()

    thread.onSetup(this.setup(SetupPhase.RUNNING))
    assertEquals("npm ci", thread.setup?.command)
    assertFalse(thread.setupFailed)

    thread.onSetup(this.setup(SetupPhase.FAILED, error = "exit 1"))
    assertTrue(thread.setupFailed)
    assertEquals("exit 1", thread.setup?.error)
  }

  fun testADoneSetupNotesItInThePromptsTurn() {
    val thread = this.thread()
    thread.startTurn(Prompt("fix it", emptyList(), emptyList()), false)
    thread.onSetup(this.setup(SetupPhase.RUNNING))

    thread.onSetup(this.setup(SetupPhase.DONE))

    assertNull(thread.setup)
    assertEquals(1, thread.notes().size)
    assertTrue(thread.notes().first().contains("42"))
  }

  fun testASkippedSetupNotesThatToo() {
    val thread = this.thread()
    thread.startTurn(Prompt("fix it", emptyList(), emptyList()), false)
    thread.onSetup(this.setup(SetupPhase.FAILED))

    thread.onSetup(this.setup(SetupPhase.SKIPPED))

    assertNull(thread.setup)
    assertEquals(1, thread.notes().size)
  }

  fun testNoNoteWithoutARunningPromptTurn() {
    val thread = this.thread()
    thread.onSetup(this.setup(SetupPhase.RUNNING))

    thread.onSetup(this.setup(SetupPhase.DONE))

    assertNull(thread.setup)
    assertTrue("no turn is made for the note", thread.turns.isEmpty())

    val done = thread.startTurn(Prompt("next", emptyList(), emptyList()), false)
    done.done = true
    thread.onSetup(this.setup(SetupPhase.DONE))
    assertTrue(thread.notes().isEmpty())
  }

  fun testAClearingSetupLeavesNoNote() {
    val thread = this.thread()
    thread.startTurn(Prompt("fix it", emptyList(), emptyList()), false)
    thread.onSetup(this.setup(SetupPhase.RUNNING))

    thread.onSetup(Setup(SetupPhase.DONE, 0, 0, "", "", null, 0, 0))
    thread.onSetup(Setup(SetupPhase.DONE, 0, 0, "", "", null, 0, 0))

    assertNull(thread.setup)
    assertTrue(thread.notes().isEmpty())
  }

  fun testTheFoldedOutputShowsItsLastLines() {
    assertEquals("", setupTail("", 6))
    assertEquals("a\nb", setupTail("a\nb\n\n\n", 6))
    assertEquals((5..10).joinToString("\n") { "line $it" },
      setupTail((1..10).joinToString("\n") { "line $it" } + "\n", 6))
  }
}
