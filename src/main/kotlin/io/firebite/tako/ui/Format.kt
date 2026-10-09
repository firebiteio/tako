package io.firebite.tako.ui

import java.text.ListFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

private val CLOCK = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(textLocale)
private val SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", textLocale)
private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(textLocale)

fun truncate(text: String, limit: Int): String =
  if (text.length > limit) text.substring(0, limit) + "\n" + t("common.truncated") else text

fun firstLine(text: String?): String {
  val line = (text ?: "").substringBefore('\n')
  return if (line.length > 160) line.substring(0, 159) + "…" else line
}

fun duration(millis: Long): String {
  val seconds = max(1, (millis / 1000.0).roundToLong())
  if (seconds < 60) {
    return t("time.duration.seconds", seconds)
  }
  return t("time.duration.minutes", seconds / 60, seconds % 60)
}

/** Seconds below a minute, else [duration]: for clocks that tick from zero. */
fun elapsed(since: Long, now: Long): String {
  val seconds = max(0, ((now - since) / 1000.0).roundToLong())
  return if (seconds < 60) t("time.duration.seconds", seconds) else duration(seconds * 1000)
}

fun ago(timestamp: Long, now: Long): String {
  val seconds = max(0, ((now - timestamp) / 1000.0).roundToLong())
  return when {
    seconds < 60 -> t("time.ago.now")
    seconds < 3600 -> t("time.ago.minutes", seconds / 60)
    seconds < 86400 -> t("time.ago.hours", seconds / 3600)
    else -> t("time.ago.days", seconds / 86400)
  }
}

fun countdown(millis: Long): String {
  val seconds = max(0, ceil(millis / 1000.0).toLong())
  val minutes = seconds / 60
  return if (minutes >= 60) {
    t("time.countdown.hours", minutes / 60, String.format(Locale.ROOT, "%02d", minutes % 60))
  } else {
    minutes.toString() + ":" + String.format(Locale.ROOT, "%02d", seconds % 60)
  }
}

fun money(value: Double): String = when {
  value == 0.0 -> "$0.00"
  value < 0.01 -> "<$0.01"
  value >= 100 -> "$" + String.format(Locale.ROOT, "%.0f", value)
  else -> "$" + String.format(Locale.ROOT, "%.2f", value)
}

fun tokens(value: Long?): String {
  val n = value ?: 0
  return when {
    n == 0L -> "0"
    n >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1e3)
    else -> n.toString()
  }
}

fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun shortDay(day: LocalDate): String = SHORT_DAY.format(day)

fun dateTime(millis: Long): String = DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private val listFormat = ListFormat.getInstance(textLocale, ListFormat.Type.STANDARD, ListFormat.Style.FULL)

/** E.g. "read 2 files, ran 1 command, and used 1 tool", in the texts' language. */
fun conjunction(parts: List<String>): String = listFormat.format(parts)

/** Paths are equal ignoring case and slash direction, as on Windows. */
fun samePath(first: String?, second: String?): Boolean {
  if (first.isNullOrEmpty() || second.isNullOrEmpty()) {
    return false
  }
  return first.replace('\\', '/').equals(second.replace('\\', '/'), ignoreCase = true)
}
