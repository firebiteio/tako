package io.firebite.tako.ui.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.firebite.tako.TakoBundle
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.ui.bridge.message.workspace.LimitItem
import io.firebite.tako.ui.clock
import io.firebite.tako.ui.dateTime
import io.firebite.tako.ui.t
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.cancel

class LimitTextsTest : BasePlatformTestCase() {
  private val zone = ZoneId.systemDefault()
  private val noon = LocalDate.of(2030, 3, 14).atTime(LocalTime.NOON).atZone(this.zone).toInstant().toEpochMilli()
  private val hour = 3_600_000L

  private fun limit(label: String?, resetsAt: Long, scheduled: Boolean = false, prompt: String = "Continue"):
    LimitItem {
    val stop = ThreadStore.LimitStop()
    stop.label = label
    stop.resetsAt = resetsAt
    stop.hitAt = this.noon
    stop.scheduled = scheduled
    stop.prompt = prompt
    return stop.item("unused")
  }

  fun testTheTitleNamesTheAgentAndTheLimitWhenKnown() {
    assertEquals(t("limit.title.label", "Claude Code", "5h"), limitTitle(this.limit("5h", 0), "Claude Code"))
    assertEquals(t("limit.title", "Codex"), limitTitle(this.limit(null, 0), "Codex"))
  }

  fun testEachStateHasItsLine() {
    val reset = this.noon + 2 * this.hour
    assertEquals(t("limit.reset.unknown"), limitLine(this.limit("5h", 0), this.noon))
    assertEquals(t("limit.resets", clock(reset)), limitLine(this.limit("5h", reset), this.noon))
    assertEquals(t("limit.reset.passed", clock(reset)), limitLine(this.limit("5h", reset), reset + this.hour))
    assertEquals("within the grace the reset passed but the resume is still offered",
      t("limit.reset.passed", clock(reset)), limitLine(this.limit("5h", reset), reset + 1))
    assertTrue(canScheduleResume(this.limit("5h", reset), reset + 1))
    val fireAt = reset + ThreadStore.LimitStop.GRACE_MILLIS
    assertEquals(t("limit.scheduled", clock(fireAt), "Continue"),
      limitLine(this.limit("5h", reset, true), this.noon))
  }

  fun testAnotherDayShowsTheDate() {
    val reset = this.noon + 30 * this.hour
    assertEquals(t("limit.resets", dateTime(reset)), limitLine(this.limit("7d", reset), this.noon))
    assertEquals(clock(this.noon), moment(this.noon, this.noon + 11 * this.hour))
    assertEquals(dateTime(this.noon), moment(this.noon, this.noon + 13 * this.hour))
  }

  fun testThePromptShowsItsFirstLineCut() {
    val prompt = "\n  \nWeiter mit den Tests 🐙 " + "x".repeat(200) + "\nsecond line"
    val line = limitLine(this.limit(null, this.noon + this.hour, true, prompt), this.noon)
    assertTrue(line.contains("Weiter mit den Tests 🐙"))
    assertFalse(line.contains("second line"))
    assertTrue(line.endsWith("…"))
    assertFalse(line.contains("x".repeat(80)))
  }

  fun testResumeIsOfferedOnlyForAResetStillAhead() {
    val reset = this.noon + this.hour
    assertTrue(canScheduleResume(this.limit("5h", reset), this.noon))
    assertFalse("scheduled already", canScheduleResume(this.limit("5h", reset, true), this.noon))
    assertFalse("no reset", canScheduleResume(this.limit("5h", 0), this.noon))
    assertFalse("passed", canScheduleResume(this.limit("5h", reset), reset + this.hour))
  }

  fun testSnoozeIsOfferedWhileTheResetIsAheadScheduledOrNot() {
    val reset = this.noon + this.hour
    assertTrue(canSnoozeUntilReset(this.limit("5h", reset), this.noon))
    assertTrue(canSnoozeUntilReset(this.limit("5h", reset, true), this.noon))
    assertFalse("no reset", canSnoozeUntilReset(this.limit("5h", 0), this.noon))
    assertFalse("passed", canSnoozeUntilReset(this.limit("5h", reset), reset + this.hour))
  }

  fun testTheThreadFollowsItsRecordsLimit() {
    val workspace = Workspace(ChatService.getInstance(this.project), this.project)
    try {
      val record = ThreadStore.getInstance(this.project).create(null, null, null, "claude")
      val thread = ThreadState(workspace, record.toItem())
      assertNull(thread.limit)
      record.limit = ThreadStore.LimitStop()
      record.limit!!.resetsAt = this.noon
      thread.update(record.toItem())
      assertEquals(this.noon, thread.limit!!.resetsAt)
      assertEquals(TakoBundle.message("limit.prompt.default"), thread.limit!!.prompt)
      record.limit = null
      thread.update(record.toItem())
      assertNull(thread.limit)
    } finally {
      workspace.scope.cancel()
    }
  }
}
