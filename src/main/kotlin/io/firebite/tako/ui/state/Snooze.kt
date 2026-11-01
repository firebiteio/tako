package io.firebite.tako.ui.state

import io.firebite.tako.ui.textLocale
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private val MORNING: LocalTime = LocalTime.of(9, 0)
private const val WEEKDAY_DAYS = 6L
private val TIME by lazy { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(textLocale) }
private val WEEKDAY by lazy { DateTimeFormatter.ofPattern("EEE").withLocale(textLocale) }
private val DATE_TIME by lazy { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(textLocale) }

/** The times a thread can be snoozed until. */
enum class SnoozePreset {
  HOUR,
  TOMORROW,
  NEXT_WEEK,
}

/**
 * When a snooze chosen at [now] ends: in an hour, tomorrow at 9:00, or at 9:00 on the first Monday after tomorrow. A
 * 9:00 that a clock change skips moves to the time after the gap.
 */
fun snoozeUntil(preset: SnoozePreset, now: ZonedDateTime): ZonedDateTime = when (preset) {
  SnoozePreset.HOUR -> now.plusHours(1)
  SnoozePreset.TOMORROW -> ZonedDateTime.of(now.toLocalDate().plusDays(1), MORNING, now.zone)
  SnoozePreset.NEXT_WEEK -> ZonedDateTime.of(
    now.toLocalDate().plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)), MORNING, now.zone)
}

/** A wake time as short as it can be told apart: the time today, weekday and time within a week, else the date. */
fun snoozeTime(millis: Long, now: ZonedDateTime): String {
  val time = Instant.ofEpochMilli(millis).atZone(now.zone)
  val days = ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
  return when {
    days == 0L -> TIME.format(time)
    days in 1..WEEKDAY_DAYS -> WEEKDAY.format(time) + " " + TIME.format(time)
    else -> DATE_TIME.format(time)
  }
}
