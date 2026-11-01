package io.firebite.tako.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SnoozeTest {
  private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

  private fun at(text: String): ZonedDateTime = ZonedDateTime.of(LocalDateTime.parse(text), this.berlin)

  @Test
  fun anHourIsSixtyMinutesEvenAcrossAClockChange() {
    val beforeChange = this.at("2026-10-25T02:30")

    val until = snoozeUntil(SnoozePreset.HOUR, beforeChange)

    assertEquals(3_600_000L, until.toInstant().toEpochMilli() - beforeChange.toInstant().toEpochMilli())
  }

  @Test
  fun tomorrowIsNineTheNextDayAlsoLateAtNight() {
    assertEquals(this.at("2026-11-03T09:00"), snoozeUntil(SnoozePreset.TOMORROW, this.at("2026-11-02T23:30")))
    assertEquals(this.at("2026-11-03T09:00"), snoozeUntil(SnoozePreset.TOMORROW, this.at("2026-11-02T00:10")))
  }

  @Test
  fun tomorrowAcrossTheSpringClockChangeIsStillNine() {
    val until = snoozeUntil(SnoozePreset.TOMORROW, this.at("2026-03-28T20:00"))

    assertEquals(this.at("2026-03-29T09:00"), until)
    assertEquals(9, until.hour)
  }

  @Test
  fun nextWeekIsTheMondayAfterTomorrow() {
    assertEquals(this.at("2026-11-09T09:00"), snoozeUntil(SnoozePreset.NEXT_WEEK, this.at("2026-11-02T10:00")))
    assertEquals(this.at("2026-11-09T09:00"), snoozeUntil(SnoozePreset.NEXT_WEEK, this.at("2026-11-06T10:00")))
    assertEquals(this.at("2026-11-09T09:00"), snoozeUntil(SnoozePreset.NEXT_WEEK, this.at("2026-11-07T10:00")))
  }

  @Test
  fun onASundayNextWeekIsNotTomorrow() {
    val sunday = this.at("2026-11-01T10:00")
    val nextWeek = snoozeUntil(SnoozePreset.NEXT_WEEK, sunday)

    assertEquals(DayOfWeek.MONDAY, nextWeek.dayOfWeek)
    assertNotEquals(snoozeUntil(SnoozePreset.TOMORROW, sunday), nextWeek)
    assertEquals(this.at("2026-11-09T09:00"), nextWeek)
  }
}
