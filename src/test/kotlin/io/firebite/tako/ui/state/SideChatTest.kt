package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.SideChats
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.settings.ChatSettings
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.view.StatusKind
import io.firebite.tako.ui.view.threadStatus
import io.firebite.tako.ui.view.waitingThreads
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNotEquals

class SideChatTest : BasePlatformTestCase() {
  private lateinit var service: ChatService
  private lateinit var store: ThreadStore
  private lateinit var workspace: Workspace
  private var savedClaudePath = ""

  override fun setUp() {
    super.setUp()
    this.service = ChatService.getInstance(this.project)
    this.store = ThreadStore.getInstance(this.project)
    this.workspace = Workspace(this.service, this.project)
    val settings = ChatSettings.state()
    this.savedClaudePath = settings.claudePath
    settings.claudePath = this.getTestName(false) + "-no-such-claude"
  }

  override fun tearDown() {
    try {
      this.workspace.scope.cancel()
      this.workspace.sidePaneCovers = false
      for (record in this.store.all()) {
        this.service.close(record.id)
      }
      ChatSettings.state().claudePath = this.savedClaudePath
    } finally {
      super.tearDown()
    }
  }

  private fun thread(): ThreadState {
    val record = this.store.create(null, null, null, "claude")
    return this.add(record)
  }

  private fun sideChat(main: ThreadState, inheritedPrompts: Int = 0): ThreadState {
    val record = this.store.create(null, null, null, "claude")
    record.parentId = main.id
    record.sideChat = true
    record.inheritedPrompts = inheritedPrompts
    return this.add(record)
  }

  private fun add(record: ThreadStore.Record): ThreadState {
    val thread = ThreadState(this.workspace, record.toItem())
    this.workspace.threads[record.id] = thread
    return thread
  }

  private fun show(thread: ThreadState) {
    this.store.setActiveId(thread.id)
    this.workspace.active = thread
  }

  private fun doneTurn(thread: ThreadState, text: String, followUps: Int = 0): Turn {
    val turn = thread.startTurn(Prompt(text, emptyList(), emptyList()), false)
    repeat(followUps) { turn.items.add(TurnItem.FollowUp(Prompt("$text, more", emptyList(), emptyList()))) }
    thread.finishTurn(null)
    return turn
  }

  private fun sideQuestion(text: String): String? {
    val invocation = parseTakoCommand(text) ?: return null
    val main = this.thread()
    return (main.resolve(invocation) as? TakoAction.SideChat)?.question
  }

  fun testSideCommandTakesTheQuestionAfterSideOrBtw() {
    assertEquals("why?", this.sideQuestion("/side why?"))
    assertEquals("why?", this.sideQuestion("/BTW   why?"))
    assertEquals("line one\nline two 🐙", this.sideQuestion("/side line one\nline two 🐙\n"))
    assertEquals("", this.sideQuestion("/side"))
    assertEquals("", this.sideQuestion("/btw \n "))
    assertNull(this.sideQuestion("/sidebar"))
    assertNull(this.sideQuestion("/sides why"))
    assertNull(this.sideQuestion("/btwx"))
    assertNull(this.sideQuestion("ask /side later"))
    assertNull(this.sideQuestion(""))
    assertEquals("an escaped one goes to the agent", "/side why?", escapedTakoCommand("//side why?"))
  }

  fun testSideChatsDontForkArchiveOrSwitchAgent() {
    val side = this.sideChat(this.thread())

    for (command in listOf("/fork", "/archive", "/agent codex")) {
      assertTrue(command, side.resolve(parseTakoCommand(command)!!) is TakoAction.Fail)
    }
    assertTrue(side.resolve(parseTakoCommand("/side more")!!) is TakoAction.SideChat)
    assertTrue(side.slashMenu("/").items.none { it.name == "fork" || it.name == "archive" })
    assertTrue(side.slashMenu("/").items.any { it.name == "side" })
  }

  fun testSettledPromptsStopAtTheRunningTurn() {
    val thread = this.thread()
    assertEquals(0, thread.settledPrompts())
    this.doneTurn(thread, "one", followUps = 2)
    this.doneTurn(thread, "two")
    thread.startTurn(Prompt("three", emptyList(), emptyList()), false)
    thread.queued.add(QueuedPrompt(9, Prompt("queued", emptyList(), emptyList())))

    assertEquals(4, thread.settledPrompts())
  }

  fun testInheritedTurnsAreTheLeadingReplayedOnes() {
    val main = this.thread()
    val side = this.sideChat(main, inheritedPrompts = 2)
    side.replay(listOf(prompt("one"), ended(), prompt("two"), ended(), prompt("own"), ended()))

    assertEquals(2, side.inheritedTurns())
    assertEquals(listOf("own"), side.ownTurns.map { it.prompt?.text })
    assertEquals(listOf("own"), side.sentPrompts())
  }

  fun testOwnLiveTurnsShowWhenTheCopyHeldFewerPrompts() {
    val main = this.thread()
    val side = this.sideChat(main, inheritedPrompts = 5)
    side.replay(listOf(prompt("one"), ended()))
    this.doneTurn(side, "own")

    assertEquals(1, side.inheritedTurns())
    assertEquals(listOf("own"), side.ownTurns.map { it.prompt?.text })
  }

  fun testNothingIsHiddenWhenTheForkWentMissing() {
    val side = this.sideChat(this.thread(), inheritedPrompts = 3)
    this.doneTurn(side, "own")

    assertEquals(0, side.inheritedTurns())
    assertEquals(1, side.ownTurns.size)
  }

  fun testSideChatsLeaveTheListWhileTheirMainThreadIsThere() {
    val main = this.thread()
    val side = this.sideChat(main)
    val spawned = this.add(this.store.create(null, null, null, "claude").also { it.parentId = main.id })

    assertFalse(this.workspace.isSideChat(main))
    assertFalse(this.workspace.isSideChat(spawned))
    assertTrue(this.workspace.isSideChat(side))
    assertSame(main, this.workspace.mainOf(side))
    assertEquals(listOf(side), this.workspace.sideChatsOf(main))

    this.workspace.threads.remove(main.id)
    assertFalse("an orphan shows as a thread", this.workspace.isSideChat(side))
    assertSame(side, this.workspace.mainOf(side))
  }

  fun testSideCommandStartsOneSideChatAndTakesTheChipsAlong() {
    val main = this.thread()
    this.show(main)
    val chip = ContextItem.create(ContextItem.Kind.SELECTION, "a.kt:1-2", "code")
    main.contexts.add(chip)
    main.input.setTextAndPlaceCursorAtEnd("/side why?")

    main.submit()

    assertEquals("", main.input.text.toString())
    assertTrue(main.contexts.isEmpty())
    assertTrue(main.turns.isEmpty())
    assertEquals(1, SideChats.of(this.store, main.id).size)
    assertTrue(main.startingSideChat)

    main.input.setTextAndPlaceCursorAtEnd("/btw again")
    main.submit()

    assertEquals("a start in flight keeps the text", "/btw again", main.input.text.toString())
    assertEquals(1, SideChats.of(this.store, main.id).size)
  }

  fun testSideCommandOfAClosedThreadStartsNothing() {
    val main = this.thread()
    this.show(main)
    this.store.remove(main.id)
    main.input.setTextAndPlaceCursorAtEnd("/side why?")

    main.submit()

    assertFalse(main.startingSideChat)
    assertTrue(SideChats.of(this.store, main.id).isEmpty())
  }

  fun testASecondSideCommandWhileOneStartsSaysSoAndKeepsTheText() {
    val main = this.thread()
    this.show(main)
    main.startingSideChat = true
    main.input.setTextAndPlaceCursorAtEnd("/btw why?")

    main.submit()

    assertEquals("/btw why?", main.input.text.toString())
    assertEquals(1, main.alerts.size)
  }

  fun testOwnTurnsWithoutAPromptAfterTheInheritedOnesShow() {
    val side = this.sideChat(this.thread(), inheritedPrompts = 1)
    side.replay(listOf(prompt("one"), ended(), AgentEvent.Text(null, "compacted", null), ended()))

    assertEquals(2, side.turns.size)
    assertEquals(1, side.inheritedTurns())
  }

  fun testAWaitingSideChatMarksItsMainThreadInTheList() {
    val main = this.thread()
    val other = this.thread()
    this.show(other)
    val side = this.sideChat(main)
    side.addPermission("r1", approval())

    assertEquals(StatusKind.INPUT, threadStatus(main, 0).kind)
    assertEquals(1, waitingThreads(this.workspace))
  }

  fun testSideCommandWhileAnApprovalWaitsLeavesTheApproval() {
    val main = this.thread()
    this.show(main)
    main.addPermission("r1", approval())
    main.input.setTextAndPlaceCursorAtEnd("/side is this safe?")

    main.submit()

    assertEquals(1, main.approvals.size)
    assertEquals(1, SideChats.of(this.store, main.id).size)
  }

  fun testSideCommandInASideChatStartsASiblingFromTheMainThread() {
    val main = this.thread()
    this.show(main)
    main.info = info(fork = false)
    val side = this.sideChat(main)
    side.input.setTextAndPlaceCursorAtEnd("/side another")

    side.submit()

    assertTrue("the main thread holds the start", main.startingSideChat)
    assertFalse(side.startingSideChat)
    assertEquals(2, SideChats.of(this.store, main.id).size)
  }

  fun testSubmitAndBranchWithASideCommandOpensNoThread() {
    val main = this.thread()
    this.show(main)
    val before = this.store.all().size
    main.input.setTextAndPlaceCursorAtEnd("/btw why?")

    main.submitAndBranch()

    assertEquals("only the side chat was made", before + 1, this.store.all().size)
  }

  fun testShowingASideChatEndsTheStartAndClearsItsUnread() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    main.startingSideChat = true
    side.unread = true

    this.workspace.receive(ChatMessage.ShowSideChat(side.id, true))

    assertEquals(side.id, main.sideChatShown)
    assertFalse(main.startingSideChat)
    assertFalse(side.unread)
    assertTrue(side.isShown)
    assertNotNull("its agent started", this.service.controller(side.id))
  }

  fun testAStartThatMadeNothingLetsTheMainThreadStartAgain() {
    val main = this.thread()
    main.startingSideChat = true

    this.workspace.receive(ChatMessage.ShowSideChat(main.id, true))

    assertFalse(main.startingSideChat)
    assertNull(main.sideChatShown)
  }

  fun testShowingAgainKeepsAStartInFlight() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    main.startingSideChat = true

    this.workspace.receive(ChatMessage.ShowSideChat(side.id, false))

    assertTrue(main.startingSideChat)
  }

  fun testAnArchivedMainThreadShowsNoPane() {
    val main = this.thread()
    val side = this.sideChat(main)
    main.archived = true
    main.startingSideChat = true

    this.workspace.showSideChat(side.id, true)

    assertNull(main.sideChatShown)
    assertFalse(main.startingSideChat)
  }

  fun testActivatingASideChatShowsItsMainThreadWithIt() {
    val main = this.thread()
    val side = this.sideChat(main)

    this.workspace.activate(side)

    assertSame(main, this.workspace.active)
    assertEquals(side.id, main.sideChatShown)
  }

  fun testHeaderButtonStartsShowsAndHides() {
    val main = this.thread()
    this.show(main)

    this.workspace.toggleSideChats(main)
    assertEquals("no side chat yet: starts one", 1, SideChats.of(this.store, main.id).size)

    val side = this.sideChat(main)
    main.startingSideChat = false
    this.workspace.toggleSideChats(main)
    assertNotNull(main.sideChatShown)

    this.workspace.toggleSideChats(main)
    assertNull(main.sideChatShown)
    assertNotNull(this.workspace.threads[side.id])
  }

  fun testClosingTheShownSideChatShowsTheNext() {
    val main = this.thread()
    this.show(main)
    val first = this.sideChat(main)
    val second = this.sideChat(main)
    this.workspace.showSideChat(first.id, false)

    this.workspace.closeSideChat(first)
    this.workspace.receive(ChatMessage.ThreadRemoved(first.id))

    assertEquals(second.id, main.sideChatShown)
    this.workspace.closeSideChat(second)
    this.workspace.receive(ChatMessage.ThreadRemoved(second.id))
    assertNull(main.sideChatShown)
  }

  fun testClosingAHiddenSideChatTwiceLeavesTheShownOne() {
    val main = this.thread()
    this.show(main)
    val shown = this.sideChat(main)
    val other = this.sideChat(main)
    this.workspace.showSideChat(shown.id, false)

    this.workspace.closeSideChat(other)
    this.workspace.closeSideChat(other)

    assertEquals(shown.id, main.sideChatShown)
  }

  fun testArchivingTheMainThreadHidesItsPane() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    this.workspace.showSideChat(side.id, false)
    side.busy = true

    this.workspace.archive(main)

    assertNull(main.sideChatShown)
    assertNull("a working side chat offers no undo", this.workspace.archivedToast)
  }

  fun testBringBackQuotesTheAnswerUnderTheTitleAfterWhatIsTyped() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    side.title = "Why cold"
    main.input.setTextAndPlaceCursorAtEnd("Fix it:")

    side.bringBack("It is cold\nbecause 🐙")

    assertEquals("Fix it:\n\n> Side chat \"Why cold\":\n>\n> It is cold\n> because 🐙\n\n", main.input.text.toString())
    assertEquals("", side.input.text.toString())
  }

  fun testBringBackOfAnUntitledSideChatNamesItSideChat() {
    val main = this.thread()
    val side = this.sideChat(main)

    side.bringBack("yes")

    assertEquals("> Side chat \"Side chat\":\n>\n> yes\n\n", main.input.text.toString())
  }

  fun testNoBringBackWhileTheMainThreadWaitsForAnAnswer() {
    val main = this.thread()
    val side = this.sideChat(main)
    main.addPermission("r1", approval())

    assertFalse(side.canBringBack)
    side.bringBack("yes")
    side.bringBack("")

    assertEquals("", main.input.text.toString())
  }

  fun testBringBackHidesAPaneThatCoversTheMainThread() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    this.workspace.showSideChat(side.id, false)
    this.workspace.sidePaneCovers = true

    side.bringBack("yes")

    assertNull(main.sideChatShown)
  }

  fun testAMainThreadCannotBringBackIntoItself() {
    val main = this.thread()
    assertFalse(main.canBringBack)
  }

  fun testSideChatsKeepTheirAgentAndDontRewind() {
    val main = this.thread()
    val side = this.sideChat(main)
    side.info = info(fork = true)
    val turn = this.doneTurn(side, "why?")

    assertTrue(main.canChangeAgent)
    assertFalse(side.canChangeAgent)
    assertFalse(side.canRewind(turn))
  }

  fun testFocusGoesToTheThreadsOwnComposer() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    this.workspace.showSideChat(side.id, false)
    val mainFocus = this.workspace.focusComposer
    val sideFocus = side.focusRequests

    side.quote("text")

    assertEquals(mainFocus, this.workspace.focusComposer)
    assertNotEquals(sideFocus, side.focusRequests)
    assertTrue(side.pendingFocus)
  }

  fun testWaitingSideChatsCountOnlyWhenOffScreen() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    side.addPermission("r1", approval())

    assertEquals(1, this.workspace.waitingSideChats(main))
    this.workspace.showSideChat(side.id, false)
    assertEquals(0, this.workspace.waitingSideChats(main))
  }

  fun testACoveringPaneHidesTheMainThread() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    assertTrue(main.isShown)

    this.workspace.showSideChat(side.id, false)
    assertTrue("beside it", main.isShown)
    this.workspace.sidePaneCovers = true
    assertFalse("covered", main.isShown)
    assertTrue(side.isShown)
  }

  fun testAnUnreadAnswerOffScreenCounts() {
    val main = this.thread()
    this.show(main)
    val side = this.sideChat(main)
    side.unread = true

    assertEquals(1, this.workspace.waitingSideChats(main))
    assertFalse(this.workspace.hasAskingSideChat(main))
  }

  fun testSideChatTitleFallsBackForTheDefault() {
    val side = this.sideChat(this.thread())
    assertEquals("Side chat", sideChatTitle(side))
    side.title = "Why?"
    assertEquals("Why?", sideChatTitle(side))
  }

  private fun prompt(text: String) = AgentEvent.Prompt(text, false, emptyList())

  private fun ended() = AgentEvent.TurnEnded(null, null, null)

  private fun approval() = PermissionPrompt(PermissionPrompt.Kind.TOOL, "Write", ToolKind.EDIT, JsonObject(), null,
    null, null, false, emptyList(), emptyList(), null)

  private fun info(fork: Boolean) = AgentInfo("claude", "Claude Code", "", AgentInfo.Capabilities(true, true, fork,
    true, true, true, false), emptyList(), null, emptyList(), null, null, emptyList())
}
