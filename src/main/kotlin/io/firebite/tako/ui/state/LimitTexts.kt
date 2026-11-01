package io.firebite.tako.ui.state

import io.firebite.tako.chat.thread.ChatController
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.clock
import io.firebite.tako.ui.dateTime
import io.firebite.tako.ui.t
import java.time.Instant
import java.time.ZoneId

/** How much of the continue prompt the banner shows. */
private const val PROMPT_PREVIEW = 80

/** The banner's title: who stopped on which limit. */
fun limitTitle(limit: ChatMessage.LimitItem, agent: String): String {
  val label = limit.label ?: return t("limit.title", agent)
  return t("limit.title.label", agent, label)
}

/** The banner's line: when the limit resets, or when and with what the thread resumes. */
fun limitLine(limit: ChatMessage.LimitItem, now: Long): String = when {
  limit.scheduled -> t("limit.scheduled", moment(limit.fireAt, now), promptPreview(limit.prompt))
  limit.resetsAt == 0L -> t("limit.reset.unknown")
  limit.resetsAt > now -> t("limit.resets", moment(limit.resetsAt, now))
  else -> t("limit.reset.passed", moment(limit.resetsAt, now))
}

/**
 * Whether the banner offers to resume at the reset: the agent named one, and the resume would not be late yet. That
 * includes a reset that passed by our clock a moment ago, as the agent's clock may be behind.
 */
fun canScheduleResume(limit: ChatMessage.LimitItem, now: Long): Boolean =
  !limit.scheduled && limit.resetsAt != 0L && limit.fireAt > now

/** Whether the banner offers to hide the thread until the resume would go out: the agent named a reset still ahead. */
fun canSnoozeUntilReset(limit: ChatMessage.LimitItem, now: Long): Boolean = limit.resetsAt != 0L && limit.fireAt > now

/** The time alone on the day of [now], else the date and time, in the IDE's time zone. */
fun moment(millis: Long, now: Long): String {
  val zone = ZoneId.systemDefault()
  val isToday = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone)
    .toLocalDate()
  return if (isToday) clock(millis) else dateTime(millis)
}

private fun promptPreview(prompt: String): String {
  val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
  return ChatController.cut(line, PROMPT_PREVIEW)
}
