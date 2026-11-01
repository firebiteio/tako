package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ui.MessageDialogBuilder
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.closeSideChat
import io.firebite.tako.ui.state.inheritedTurns
import io.firebite.tako.ui.state.showSideChat
import io.firebite.tako.ui.state.sideChatTitle
import io.firebite.tako.ui.state.sideChatsOf
import io.firebite.tako.ui.state.startSideChat
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text

/**
 * A side chat of [main] beside it: a picker of the main thread's side chats, then the side chat's alerts, timeline and
 * composer. Starts the side chat's agent when it has none.
 */
@Composable
fun SideChatPane(main: ThreadState, side: ThreadState, markdown: ChatMarkdown, actions: CodeActions,
  modifier: Modifier) {
  val workspace = main.workspace
  LaunchedEffect(side) {
    side.unread = false
    workspace.service.openSideChat(side.id)
  }
  Column(modifier.background(colors.bg)) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Box(Modifier.weight(1f)) {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.hoverClick { open = true }.padding(horizontal = 6.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Ico(Icons.sideChats, size = 14.dp, tint = colors.muted)
          Text(sideChatTitle(side), Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
          Chevron(open)
        }
        if (open) {
          SideChatMenu(main, side) { open = false }
        }
      }
      IconBtn(Icons.delete, t("side.close")) {
        val confirmed = !side.busy || MessageDialogBuilder.yesNo(t("side.close"),
          t("sidebar.close.confirm", side.agentName)).guessWindowAndAsk()
        if (confirmed) {
          workspace.closeSideChat(side)
        }
      }
      IconBtn(Icons.hidePane, t("side.hide")) { main.sideChatShown = null }
    }
    Rule()
    if (side.inheritedTurns() > 0) {
      Text(t("side.inherited"), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = colors.muted,
        style = smallStyle)
    }
    if (side.alerts.isNotEmpty()) {
      Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (alert in side.alerts.toList()) {
          AlertCard(alert) { side.alerts.remove(alert) }
        }
      }
    }
    ThreadView(side, markdown, actions, Modifier.weight(1f))
  }
}

/** The main thread's side chats to switch to, with their status, and a new one. */
@Composable
private fun SideChatMenu(main: ThreadState, shown: ThreadState, onDismiss: () -> Unit) {
  val workspace = main.workspace
  val entries = workspace.sideChatsOf(main).map { side ->
    val status = threadStatus(side, workspace.now)
    MenuEntry(sideChatTitle(side), status.label.takeIf { status.kind == StatusKind.INPUT || status.kind ==
      StatusKind.WORKING }, checked = side === shown) {
      workspace.showSideChat(side.id, false)
    }
  } + MenuEntry(t("side.new"), icon = Icons.add) { main.startSideChat("") }
  MenuPopup(entries, t("side.chats"), onDismiss = onDismiss)
}
