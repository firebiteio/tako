package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.ui.duration
import io.firebite.tako.ui.state.OUTPUT_LIMIT
import io.firebite.tako.ui.state.TaskCard
import io.firebite.tako.ui.state.TaskState
import io.firebite.tako.ui.state.TaskType
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.cardOf
import io.firebite.tako.ui.state.cardState
import io.firebite.tako.ui.state.kind
import io.firebite.tako.ui.state.runningTasks
import io.firebite.tako.ui.state.string
import io.firebite.tako.ui.state.taskTitle
import io.firebite.tako.ui.state.taskType
import io.firebite.tako.ui.state.title
import io.firebite.tako.ui.t
import io.firebite.tako.ui.textLocale
import io.firebite.tako.ui.tokens
import io.firebite.tako.ui.truncate
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer


private fun doneAgents(card: TaskCard): Int = card.task?.agents?.values?.count { it.state == "done" } ?: 0

fun taskStats(card: TaskCard, now: Long): String {
  val task = card.task ?: return ""
  val parts = ArrayList<String>()
  if (task.agents.isNotEmpty()) {
    parts.add(t("tasks.agents", doneAgents(card), task.agents.size))
  }
  val toolUses = task.toolUses
  if (toolUses != null && toolUses > 0 && task.agents.isEmpty()) {
    parts.add(t("tasks.tools", toolUses))
  }
  val end = task.endedAt ?: if (card.thread.cardState(card) == TaskState.RUNNING) now else null
  if (end != null) {
    parts.add(duration(end - task.startedAt))
  }
  return parts.joinToString(" · ")
}

fun taskActivity(card: TaskCard): String {
  val task = card.task
  val state = card.thread.cardState(card)
  if (state == TaskState.RUNNING) {
    val running = task?.agents?.values?.filter { it.state == "running" } ?: emptyList()
    if (running.isNotEmpty()) {
      return t("tasks.running", running.joinToString(", ") { it.label ?: "" })
    }
    return task?.lastTool?.let { t("tasks.running", it) } ?: t("tasks.working")
  }
  task?.summary?.let { return it }
  return if (state == TaskState.ERROR) t("tasks.failed") else t("tasks.completed")
}

/** A task's detail panel: its status, agents, steps, log and result. */
@Composable
fun TaskPanel(card: TaskCard, markdown: ChatMarkdown, actions: CodeActions) {
  val thread = card.thread
  val state = thread.cardState(card)
  val task = card.task
  val now = thread.workspace.now
  Row(Modifier.fillMaxSize().drawBehind {
    val width = 24.dp.toPx()
    this.drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.06f),
      Color.Black.copy(alpha = 0.16f)), -width, 0f), Offset(-width, 0f), Size(width, this.size.height))
  }) {
    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
    VerticallyScrollableContainer(Modifier.fillMaxSize().background(colors.bg)) {
      Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp)) {
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.padding(top = 2.dp)) { Ico(taskIcon(card.taskType), tint = stateColor(state)) }
          Column(Modifier.weight(1f)) {
            val subagent = card.input.string("subagent_type")
            Text((card.kind() + (if (subagent != null) " · $subagent" else "")).uppercase(textLocale),
              color = colors.muted, style = tinyStyle.copy(letterSpacing = 0.04.em))
            Text(card.taskTitle(), color = colors.fg, fontWeight = FontWeight.SemiBold)
          }
          IconBtn(Icons.close, t("tasks.panel.close"), iconSize = 14.dp) { thread.workspace.taskPanel = null }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TaskStateIcon(state)
          Text(taskActivity(card), color = colors.body, style = smallStyle)
        }
        val facts = listOfNotNull(
          if (task != null && task.agents.isNotEmpty()) t("tasks.agents", doneAgents(card), task.agents.size) else null,
          task?.toolUses?.takeIf { it > 0 }?.let { t("tasks.tool.calls", it) },
          task?.totalTokens?.takeIf { it > 0 }?.let { t("tasks.tokens", tokens(it)) },
          task?.let { (it.endedAt ?: if (state == TaskState.RUNNING) now else null)?.let { end ->
            duration(end - it.startedAt)
          } },
        )
        if (facts.isNotEmpty()) {
          Text(facts.joinToString(" · "), Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp), color = colors.muted,
            style = tinyStyle)
        }
        if (task != null && task.agents.isNotEmpty()) {
          Agents(task.agents.values.sortedBy { it.index })
        }
        val steps = card.steps.size
        if (steps > 0) {
          SectionTitle(t("tasks.panel.steps"), steps.toString())
          Steps(card.steps, markdown, actions)
        }
        if (task != null && task.logs.isNotEmpty()) {
          SectionTitle(t("tasks.panel.log"), task.logs.size.toString())
          SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              for (log in task.logs) {
                Text(log, color = colors.muted, style = monoTinyStyle)
              }
            }
          }
        }
        if (card.resultText.isNotEmpty() && card.taskType == TaskType.AGENT) {
          SectionTitle(t("tasks.panel.result"))
          MarkdownText(markdown, card.resultText, true, actions)
        }
        val prompt = card.input.string("prompt") ?: task?.prompt
        if (prompt != null) {
          Details(t("tasks.panel.prompt"), prompt)
        }
        if (card.resultText.isNotEmpty() && card.taskType != TaskType.AGENT) {
          Details(t("tasks.panel.technical"), card.resultText)
        }
      }
    }
  }
}

/** A workflow's agents by phase, each opening to its error, result and prompt. */
@Composable
private fun Agents(agents: List<AgentEvent.WorkflowAgent>) {
  val open = remember { mutableStateMapOf<Int, Boolean>() }
  val phases = LinkedHashMap<Any, Pair<String, MutableList<AgentEvent.WorkflowAgent>>>()
  for (agent in agents) {
    val key: Any = agent.phaseIndex ?: agent.phaseTitle ?: ""
    phases.getOrPut(key) { (agent.phaseTitle ?: t("tasks.panel.agents")) to ArrayList() }.second.add(agent)
  }
  for ((title, group) in phases.values) {
    val models = group.mapNotNull { it.model }.toSet()
    SectionTitle(title + (if (models.size == 1) " · " + models.first() else ""),
      group.count { it.state == "done" }.toString() + "/" + group.size)
    for (agent in group) {
      val isOpen = open[agent.index] == true
      val (interaction, hovered) = rememberHover()
      val shape = RoundedCornerShape(6.dp)
      val background = when {
        isOpen -> colors.hover.copy(alpha = colors.hover.alpha * 0.6f)
        hovered -> colors.hover
        else -> Color.Transparent
      }
      Row(Modifier.fillMaxWidth().clip(shape).background(background).hoverable(interaction)
        .clickable(interaction, indication = null) { open[agent.index] = !isOpen }.pointerHoverIcon(PointerIcon.Hand)
        .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(14.dp)) {
          when (agent.state) {
            "done" -> TaskStateIcon(TaskState.DONE)
            "error" -> TaskStateIcon(TaskState.ERROR)
            "start" -> Ico(Icons.queued, size = 12.dp, tint = colors.muted)
            else -> TaskStateIcon(TaskState.RUNNING)
          }
        }
        Text(agent.label ?: t("tasks.panel.agent", agent.index + 1), Modifier.weight(1f), color = colors.fg,
          style = smallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(if (models.size > 1) agent.model else null,
          agent.toolCalls?.takeIf { it > 0 }?.let { t("tasks.tools", it) }, agent.tokens?.takeIf { it > 0 }?.let { tokens(it) })
        Text(meta.joinToString(" · "), color = colors.muted, style = tinyStyle, maxLines = 1)
      }
      if (isOpen) {
        Column(Modifier.padding(start = 26.dp, end = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          agent.error?.let { Text(it, color = colors.del, style = smallStyle) }
          agent.resultPreview?.let { Text(it, color = colors.body, style = smallStyle) }
          agent.promptPreview?.let { AgentPrompt(it) }
          if (agent.error == null && agent.resultPreview == null && agent.promptPreview == null) {
            Text(t("tasks.panel.no.details"), color = colors.body, style = smallStyle)
          }
        }
      }
    }
  }
}

/** A summary that opens to preformatted text. */
@Composable
private fun Details(summary: String, text: String) {
  var open by remember { mutableStateOf(false) }
  Column(Modifier.padding(top = 12.dp)) {
    Row(Modifier.hoverClick(alpha = 0f) { open = !open }, verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Chevron(open, tint = colors.muted)
      Text(summary, color = colors.muted, style = smallStyle)
    }
    if (open) {
      Box(Modifier.padding(top = 4.dp)) { Panel(truncate(text, OUTPUT_LIMIT), colors.muted) }
    }
  }
}

/** An agent's prompt behind a plain summary, in the detail's own text. */
@Composable
private fun AgentPrompt(text: String) {
  var open by remember { mutableStateOf(false) }
  Column {
    Row(Modifier.hoverClick(alpha = 0f) { open = !open }, verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Chevron(open, tint = colors.body)
      Text(t("tasks.panel.prompt"), color = colors.body, style = smallStyle)
    }
    if (open) {
      Text(text, color = colors.body, style = smallStyle)
    }
  }
}

@Composable
private fun stateColor(state: TaskState): Color = when (state) {
  TaskState.RUNNING -> colors.primary
  TaskState.DONE -> colors.add
  TaskState.ERROR -> colors.del
}

/** Pills for the shown thread's running tasks, each opening its panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksTray(thread: ThreadState) {
  val running = thread.runningTasks()
  if (running.isEmpty()) {
    return
  }
  val now = thread.workspace.now
  FlowRow(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (task in running) {
      val card = thread.cardOf(task)
      val shape = RoundedCornerShape(50)
      Tip(t("tasks.pill", task.taskType().title(), task.description ?: "")) {
        Row(Modifier.height(26.dp).clip(shape).background(lerpColor(colors.card, colors.primary, 0.08f))
          .border(1.dp, lerpColor(colors.softBorder, colors.primary, 0.35f), shape).hoverClick(shape, alpha = 0f) {
            if (card != null) {
              thread.workspace.taskPanel = card
            }
          }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TaskStateIcon(TaskState.RUNNING)
          Text(task.description ?: t("tasks.background"), Modifier.weight(1f, fill = false), color = colors.fg,
            style = smallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
          val stats = card?.let { taskStats(it, now) } ?: ""
          if (stats.isNotEmpty()) {
            Text(stats, color = colors.muted, style = tinyStyle, maxLines = 1)
          }
        }
      }
    }
  }
}
