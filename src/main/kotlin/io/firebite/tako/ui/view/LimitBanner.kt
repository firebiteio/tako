package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.canScheduleResume
import io.firebite.tako.ui.state.canSnoozeUntilReset
import io.firebite.tako.ui.state.limitLine
import io.firebite.tako.ui.state.limitTitle
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/**
 * The usage limit the thread's agent stopped on, above the composer: when it resets, and the offer to send a continue
 * prompt then, whose text the person can change.
 */
@Composable
fun LimitBanner(thread: ThreadState) {
  val limit = thread.limit ?: return
  val workspace = thread.workspace
  val service = workspace.service
  var editing by remember(thread) { mutableStateOf(false) }
  val draft = remember(thread) { TextFieldState() }
  LaunchedEffect(limit.scheduled) {
    if (!limit.scheduled) {
      editing = false
    }
  }
  val shape = RoundedCornerShape(14.dp)
  Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape)
    .background(lerpColor(colors.bg, colors.warn, 0.08f)).border(1.dp, colors.warn.copy(alpha = 0.32f), shape)
    .padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top) {
    Ico(Icons.timer, Modifier.padding(top = 1.dp), size = 14.dp, tint = colors.warn)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(limitTitle(limit, thread.agentName), color = colors.fg,
        style = smallStyle.copy(fontWeight = FontWeight.Medium))
      if (editing && limit.scheduled) {
        TextArea(draft, Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 160.dp).onPreviewKeyEvent { event ->
          val isEscape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
          if (isEscape) {
            editing = false
          }
          isEscape
        })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          PrimaryButton(t("limit.save")) {
            service.setResumePrompt(thread.id, draft.text.toString())
            editing = false
          }
          OutlineButton(t("limit.edit.cancel")) { editing = false }
        }
      } else {
        Text(limitLine(limit, workspace.now), color = colors.muted, style = smallStyle, maxLines = 3,
          overflow = TextOverflow.Ellipsis)
        val canResume = canScheduleResume(limit, workspace.now)
        val canSnooze = canSnoozeUntilReset(limit, workspace.now)
        if (limit.scheduled || canResume || canSnooze) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (limit.scheduled) {
              OutlineButton(t("limit.edit")) {
                draft.setTextAndPlaceCursorAtEnd(limit.prompt)
                editing = true
              }
              OutlineButton(t("limit.cancel")) { service.cancelResume(thread.id) }
            } else if (canResume) {
              PrimaryButton(t("limit.resume")) { service.scheduleResume(thread.id) }
            }
            if (canSnooze) {
              OutlineButton(t("limit.snooze"), icon = Icons.snooze) { service.snooze(thread.id, limit.fireAt) }
            }
          }
        }
      }
    }
    IconBtn(Icons.closeSmall, t("common.dismiss"), iconSize = 14.dp) { service.dismissLimit(thread.id) }
  }
}
