package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text

/** The agents found on this computer, to pick the one new threads start with. Picking one moves this thread to it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPickCard(thread: ThreadState) {
  val shape = RoundedCornerShape(12.dp)
  val (ready, missing) = thread.workspace.agents.partition { it.problem == null }
  Column(Modifier.widthIn(max = LANE).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).clip(shape)
    .background(colors.card).border(1.dp, colors.softBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(t("agent.pick.title"), style = smallStyle, fontWeight = FontWeight.SemiBold)
    Text(t("agent.pick.text"), color = colors.muted, style = smallStyle)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (agent in ready) {
        OutlineButton(agent.name) { thread.workspace.service.pickDefaultAgent(thread.id, agent.id) }
      }
    }
    if (missing.isNotEmpty()) {
      Text(t("agent.pick.missing", missing.joinToString(", ") { it.name }), color = colors.faint, style = tinyStyle)
    }
  }
}
