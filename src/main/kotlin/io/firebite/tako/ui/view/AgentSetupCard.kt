package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import io.firebite.tako.agent.SetupProblem
import io.firebite.tako.agent.claude.ClaudeBackend
import io.firebite.tako.agent.codex.CodexBackend
import io.firebite.tako.chat.ChatService
import io.firebite.tako.settings.AcpAgentsConfigurable
import io.firebite.tako.settings.ClaudeCodeConfigurable
import io.firebite.tako.settings.CodexConfigurable
import io.firebite.tako.ui.bridge.message.workspace.AgentOption
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.readyAgents
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text

private const val CLAUDE_INSTALL_URL = "https://code.claude.com"

/**
 * What keeps the empty thread's agent from starting and how to fix it: an install link, an API key, the CLI login, or
 * another agent that is ready.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSetupCard(thread: ThreadState, agent: AgentOption) {
  val shape = RoundedCornerShape(12.dp)
  Column(Modifier.widthIn(max = LANE).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).clip(shape)
    .background(colors.card).border(1.dp, colors.softBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Ico(Icons.warning, size = 14.dp, tint = colors.muted)
      Text(t("agent.setup.title", agent.name), style = smallStyle, fontWeight = FontWeight.SemiBold)
    }
    Text(explanation(agent), color = colors.muted, style = smallStyle)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Fixes(thread, agent)
      OutlineButton(t("agent.setup.settings")) { openSettings(thread, agent) }
      OutlineButton(t("agent.setup.recheck"), icon = Icons.refresh) { ChatService.retryFailedStartsInOpenProjects() }
    }
    if (agent.id == ClaudeBackend.ID && agent.problem == SetupProblem.NO_API_KEY) {
      Text(t("agent.setup.claude.cli.note"), color = colors.faint, style = tinyStyle)
    }
    val others = thread.readyAgents
    if (others.isNotEmpty()) {
      Rule()
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(t("agent.setup.others"), color = colors.muted, style = smallStyle)
        for (other in others) {
          OutlineButton(other.name) { thread.workspace.service.setAgent(thread.id, other.id) }
        }
      }
    }
  }
}

@Composable
private fun Fixes(thread: ThreadState, agent: AgentOption) {
  when {
    agent.id == ClaudeBackend.ID && agent.problem == SetupProblem.NOT_INSTALLED ->
      PrimaryButton(t("agent.setup.claude.install")) { browse(CLAUDE_INSTALL_URL) }
    agent.id == ClaudeBackend.ID && agent.problem == SetupProblem.NO_API_KEY -> {
      PrimaryButton(t("agent.setup.claude.key.add")) { askApiKey(thread) }
      OutlineButton(t("agent.setup.claude.cli")) { ChatService.useClaudeCliLogin() }
    }
  }
}

private fun explanation(agent: AgentOption): String = when {
  agent.id == ClaudeBackend.ID && agent.problem == SetupProblem.NO_API_KEY -> t("agent.setup.claude.key")
  agent.id == ClaudeBackend.ID -> t("agent.setup.claude.missing")
  agent.id == CodexBackend.ID -> t("agent.setup.codex.missing")
  else -> t("agent.setup.acp.missing", agent.name)
}

private fun askApiKey(thread: ThreadState) {
  val key = Messages.showPasswordDialog(thread.workspace.project, t("agent.setup.key.message"),
    t("agent.setup.key.title"), null)
  if (!key.isNullOrBlank()) {
    ChatService.useClaudeApiKey(key.trim())
  }
}

private fun openSettings(thread: ThreadState, agent: AgentOption) {
  val page: Class<out Configurable> = when (agent.id) {
    ClaudeBackend.ID -> ClaudeCodeConfigurable::class.java
    CodexBackend.ID -> CodexConfigurable::class.java
    else -> AcpAgentsConfigurable::class.java
  }
  ShowSettingsUtil.getInstance().showSettingsDialog(thread.workspace.project, page)
}
