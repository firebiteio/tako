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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.childrenOf
import io.firebite.tako.ui.state.effortLabel
import io.firebite.tako.ui.t
import io.firebite.tako.ui.textLocale
import org.jetbrains.jewel.ui.component.Text

private fun rank(thread: ThreadState): Int = when (threadStatus(thread, 0).kind) {
  StatusKind.INPUT -> 0
  StatusKind.WORKING -> 1
  else -> 2
}

/** The threads this one's agent created with their status, those needing a look first. Not shown without any. */
@Composable
fun ChildrenPanel(thread: ThreadState) {
  val workspace = thread.workspace
  val children = workspace.childrenOf(thread, ::rank)
  if (children.isEmpty()) {
    return
  }
  val folded = thread.childrenFolded
  val shape = RoundedCornerShape(10.dp)
  Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp).fillMaxWidth().clip(shape).background(colors.card)
    .border(1.dp, colors.softBorder, shape)) {
    val (interaction, hovered) = rememberHover()
    val color = if (hovered) colors.fg else colors.muted
    Row(Modifier.fillMaxWidth().hoverClick(shape, 0.6f, interaction = interaction) {
      thread.childrenFolded = !folded
    }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Chevron(!folded, tint = color)
      Text(t("children.title").uppercase(textLocale), color = color,
        style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em))
      Text(children.size.toString(), Modifier.weight(1f), color = colors.faint, style = tinyStyle)
      if (folded) {
        val status = rememberStatus(workspace, children[0])
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
          StatusIcon(status.kind, children[0], statusColor(status.kind))
        }
      }
    }
    if (!folded) {
      Column(Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp,
        bottom = 4.dp)) {
        for (child in children) {
          ChildRow(thread, child)
        }
      }
    }
  }
}

/** A child's status, title, agent with model and effort; it opens the child. */
@Composable
private fun ChildRow(parent: ThreadState, child: ThreadState) {
  val workspace = parent.workspace
  val status = rememberStatus(workspace, child)
  val subColor = when (status.kind) {
    StatusKind.WORKING -> colors.fg
    StatusKind.INPUT -> colors.warn
    StatusKind.ERROR -> colors.del
    else -> colors.muted
  }
  val meta = listOfNotNull(child.agentName, (child.currentModel?.displayName?.ifEmpty { null } ?: child.model),
    child.effort?.let { effortLabel(it) }).joinToString(" · ")
  Row(Modifier.fillMaxWidth().hoverClick(RoundedCornerShape(8.dp), 0.7f) { workspace.open(child) }
    .padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.padding(top = 2.dp).size(14.dp), contentAlignment = Alignment.Center) {
      StatusIcon(status.kind, child, statusColor(status.kind))
    }
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tip(child.title, Modifier.weight(1f)) {
          Text(child.title, color = if (child.unread) colors.fg else colors.body, style = smallStyle,
            fontWeight = if (child.unread) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(status.label, Modifier.widthIn(max = 120.dp), color = subColor, style = tinyStyle, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      }
      Text(meta, color = colors.muted, style = tinyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}
