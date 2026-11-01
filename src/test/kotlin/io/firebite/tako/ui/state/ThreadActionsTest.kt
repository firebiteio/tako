package io.firebite.tako.ui.state

import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.t
import io.firebite.tako.ui.textLocale
import io.firebite.tako.ui.view.StatusKind
import io.firebite.tako.ui.view.threadStatus
import io.firebite.tako.ui.view.waitingThreads
import kotlinx.coroutines.cancel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ThreadActionsTest : BasePlatformTestCase() {
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
      for (thread in this.workspace.threads.values) {
        this.store.remove(thread.id)
      }
    } finally {
      super.tearDown()
    }
  }

  private fun thread(parent: ThreadState? = null, hasSession: Boolean = true): ThreadState {
    val record = this.store.create(null, null, null, "claude")
    record.parentId = parent?.id
    val thread = ThreadState(this.workspace, record.toItem())
    thread.hasSession = hasSession
    this.workspace.threads[record.id] = thread
    return thread
  }

  private fun actions(thread: ThreadState): List<ThreadAction> = this.workspace.threadActions(thread)

  private fun ask(thread: ThreadState) {
    thread.approvals.add(Approval("r", PermissionPrompt(PermissionPrompt.Kind.QUESTION, "AskUserQuestion",
      ToolKind.QUESTION, JsonObject(), null, null, null, false, emptyList(), emptyList(), null)))
  }

  fun testAThreadWithAConversationOffersEverything() {
    assertEquals(listOf(ThreadAction.PIN, ThreadAction.SNOOZE, ThreadAction.MARK_UNREAD, ThreadAction.RENAME,
      ThreadAction.REGENERATE_TITLE), this.actions(this.thread()))
  }

  fun testANewThreadHasNothingToMarkUnread() {
    assertFalse(ThreadAction.MARK_UNREAD in this.actions(this.thread(hasSession = false)))
  }

  fun testTheShownOrAnUnreadThreadCannotBeMarkedUnread() {
    val shown = this.thread()
    this.workspace.active = shown
    val unread = this.thread()
    unread.unread = true

    assertFalse(ThreadAction.MARK_UNREAD in this.actions(shown))
    assertFalse(ThreadAction.MARK_UNREAD in this.actions(unread))
  }

  fun testPinnedThreadOffersUnpin() {
    val thread = this.thread()
    thread.pinned = true

    assertEquals(ThreadAction.UNPIN, this.actions(thread).first())
  }

  fun testSnoozedThreadAndItsSubThreadsOfferWake() {
    val parent = this.thread()
    val child = this.thread(parent)
    val grandchild = this.thread(child)
    parent.snoozedUntil = System.currentTimeMillis() + 60_000

    assertTrue(ThreadAction.WAKE in this.actions(parent))
    assertTrue(ThreadAction.WAKE in this.actions(grandchild))
    assertFalse(ThreadAction.SNOOZE in this.actions(child))
  }

  fun testArchivedThreadCanOnlyBeRenamedAndTitled() {
    val thread = this.thread()
    thread.archived = true
    thread.pinned = true
    thread.snoozedUntil = System.currentTimeMillis() + 60_000

    assertEquals(listOf(ThreadAction.RENAME, ThreadAction.REGENERATE_TITLE), this.actions(thread))
    assertFalse(this.workspace.isHidden(thread))
  }

  fun testRunsKeepTheirPlaceAndName() {
    val run = this.thread()
    this.workspace.benches = listOf(ChatMessage.BenchItem("b", "Fix", null, "0123456", listOf(run.id)))

    assertEquals(listOf(ThreadAction.MARK_UNREAD, ThreadAction.REGENERATE_TITLE), this.actions(run))
  }

  fun testASubThreadsQuestionKeepsItsParentFromSnoozing() {
    val parent = this.thread()
    val child = this.thread(parent)
    val other = this.thread()

    this.ask(this.thread(child))

    assertTrue(this.workspace.waitsForAnswer(parent))
    assertTrue(this.workspace.waitsForAnswer(child))
    assertFalse(this.workspace.waitsForAnswer(other))
  }

  fun testUnreadThreadShowsAsDoneAndNewAfterARestart() {
    val thread = this.thread()
    thread.unread = true

    val status = threadStatus(thread, System.currentTimeMillis())

    assertEquals(StatusKind.DONE, status.kind)
    assertEquals(t("sidebar.status.done.new"), status.label)
  }

  fun testSnoozedThreadsAndTheirSubThreadsLeaveTheBadge() {
    val parent = this.thread()
    val child = this.thread(parent)
    child.unread = true
    val loud = this.thread()
    loud.unread = true
    assertEquals(2, waitingThreads(this.workspace))

    parent.snoozedUntil = System.currentTimeMillis() + 60_000

    assertEquals(1, waitingThreads(this.workspace))
  }

  fun testUpdateFromTheIdeCarriesTheListState() {
    val thread = this.thread()
    val record = this.store.find(thread.id)!!
    record.pinned = true
    record.unread = true
    record.snoozedUntil = 42

    thread.update(record.toItem())

    assertTrue(thread.pinned)
    assertTrue(thread.unread)
    assertEquals(42, thread.snoozedUntil)
  }

  fun testWakeTimeIsAsShortAsItCanBe() {
    val zone = ZoneId.of("Europe/Berlin")
    val now = ZonedDateTime.of(LocalDateTime.parse("2026-11-02T10:00"), zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(textLocale)
    val weekday = DateTimeFormatter.ofPattern("EEE").withLocale(textLocale)
    val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(textLocale)
    fun at(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), zone)
    fun shown(text: String) = snoozeTime(at(text).toInstant().toEpochMilli(), now)

    assertEquals(time.format(at("2026-11-02T11:00")), shown("2026-11-02T11:00"))
    assertEquals(weekday.format(at("2026-11-03T09:00")) + " " + time.format(at("2026-11-03T09:00")),
      shown("2026-11-03T09:00"))
    assertEquals(dateTime.format(at("2026-11-09T09:00")), shown("2026-11-09T09:00"))
  }
}
