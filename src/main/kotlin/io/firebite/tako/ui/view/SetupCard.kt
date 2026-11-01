package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.elapsed
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text

/** How many of the output's last lines show while the output is folded. */
private const val TAIL_LINES = 6

/**
 * The setup of the thread's new worktree, over the composer: the command that runs, the end of its output, and once it
 * failed, a retry or a start without it.
 */
@Composable
fun SetupCard(thread: ThreadState, setup: ChatMessage.Setup) {
  val failed = setup.phase == ChatMessage.SetupPhase.FAILED
  var expanded by remember(thread) { mutableStateOf(false) }
  val shape = RoundedCornerShape(12.dp)
  val border = if (failed) colors.del.copy(alpha = 0.35f) else colors.softBorder
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(colors.card).border(1.dp, border, shape)
    .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (failed) {
        Ico(Icons.error, size = 14.dp, tint = colors.del)
      } else {
        Spinner()
      }
      Text(t("setup.title"), Modifier.weight(1f), style = smallStyle, fontWeight = FontWeight.SemiBold)
      Text(setupStatus(setup, thread.workspace.now), color = if (failed) colors.del else colors.muted,
        style = smallStyle.copy(fontFeatureSettings = "tnum"))
    }
    if (setup.command.isNotEmpty()) {
      Text(setup.command, style = monoStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    setup.error?.let { Text(it, color = colors.del, style = smallStyle) }
    if (setup.output.isNotBlank()) {
      SetupOutput(setup.output, expanded)
      val (interaction, hovered) = rememberHover()
      Text(if (expanded) t("setup.output.less") else t("setup.output.all"),
        Modifier.hoverClick(interaction = interaction) { expanded = !expanded }.padding(horizontal = 2.dp),
        color = if (hovered) colors.fg else colors.muted, style = smallStyle)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
      if (failed) {
        OutlineButton(t("setup.skip")) { thread.controller?.skipSetup() }
        PrimaryButton(t("setup.retry")) { thread.controller?.retrySetup() }
      } else {
        OutlineButton(t("setup.cancel")) { thread.controller?.cancelSetup() }
      }
    }
  }
}

@Composable
private fun SetupOutput(output: String, expanded: Boolean) {
  val shape = RoundedCornerShape(8.dp)
  val text = remember(output, expanded) { if (expanded) output.trimEnd() else setupTail(output, TAIL_LINES) }
  Box(Modifier.fillMaxWidth().clip(shape).background(colors.bg).border(1.dp, colors.softBorder, shape)
    .heightIn(max = 240.dp).let { if (expanded) it.verticalScroll(rememberScrollState()) else it }
    .padding(horizontal = 8.dp, vertical = 6.dp)) {
    SelectionContainer {
      Text(text, color = colors.muted, style = monoTinyStyle)
    }
  }
}

/** The output's last [lines] lines, without trailing blank ones. */
internal fun setupTail(output: String, lines: Int): String = output.trimEnd().lines().takeLast(lines).joinToString("\n")

private fun setupStatus(setup: ChatMessage.Setup, now: Long): String = when {
  setup.phase == ChatMessage.SetupPhase.FAILED -> t("setup.status.failed")
  setup.steps == 0 -> t("setup.status.starting")
  else -> t("setup.status.step", setup.step, setup.steps, elapsed(setup.startedAt, now))
}
