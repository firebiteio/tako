package io.firebite.tako.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.ui.state.TaskCard
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Turn
import io.firebite.tako.ui.state.TurnItem
import io.firebite.tako.ui.state.WorkTab
import io.firebite.tako.ui.state.ownTurns
import io.firebite.tako.ui.state.todos
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** What the thread produced, beside its conversation: its changed files, its plan and its tasks. */
@Composable
fun WorkPane(thread: ThreadState, markdown: ChatMarkdown, actions: CodeActions, modifier: Modifier) {
  val turns = thread.ownTurns
  val tasks = turns.flatMap { turn -> turn.items.filterIsInstance<TurnItem.Task>().map { it.card } }
  Column(modifier) {
    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      WorkTabButton(thread, WorkTab.CHANGES, t("work.changes"),
        turns.flatMap { it.changeList }.distinctBy { it.path }.size)
      WorkTabButton(thread, WorkTab.PLAN, t("work.plan"), 0)
      WorkTabButton(thread, WorkTab.TASKS, t("work.tasks"), tasks.size)
    }
    Rule()
    key(thread.id, thread.workTab) {
      VerticallyScrollableContainer(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
          when (thread.workTab) {
            WorkTab.CHANGES -> ChangesTab(thread, turns)
            WorkTab.PLAN -> PlanTab(turns, markdown, actions)
            WorkTab.TASKS -> TasksTab(tasks)
          }
        }
      }
    }
  }
}

/** @param count shown after the label when above 0 */
@Composable
private fun WorkTabButton(thread: ThreadState, tab: WorkTab, label: String, count: Int) {
  val isShown = thread.workTab == tab
  val (interaction, hovered) = rememberHover()
  val accent = colors.primary
  val color = if (isShown || hovered) colors.fg else colors.muted
  Row(Modifier.fillMaxHeight().hoverClick(RoundedCornerShape(0.dp), 0f, interaction = interaction) { thread.workTab = tab }
    .drawBehind {
      if (isShown) {
        val height = 2.dp.toPx()
        this.drawRect(accent, Offset(0f, this.size.height - height), Size(this.size.width, height))
      }
    }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, color = color, style = smallStyle.copy(fontWeight = if (isShown) FontWeight.Medium else null))
    if (count > 0) {
      Text(count.toString(), color = colors.faint, style = tinyStyle)
    }
  }
}

/** Each turn's changes, the newest on top, under the prompt that asked for them. */
@Composable
private fun ChangesTab(thread: ThreadState, turns: List<Turn>) {
  val changed = turns.filter { it.changeList.isNotEmpty() }.asReversed()
  if (changed.isEmpty()) {
    EmptyTab(t("work.changes.empty"))
  }
  for (turn in changed) {
    key(System.identityHashCode(turn)) {
      turn.prompt?.let { prompt ->
        Text(prompt.text.lineSequence().first(), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
          color = colors.muted, style = smallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      ChangesCard(thread, turn)
    }
  }
}

/** The latest task list and the latest plan, which stay in view while the conversation moves on. */
@Composable
private fun PlanTab(turns: List<Turn>, markdown: ChatMarkdown, actions: CodeActions) {
  val items = turns.flatMap { it.items }
  val todoEntry = items.filterIsInstance<TurnItem.Single>().lastOrNull { it.entry.category == ToolKind.TODO }?.entry
  val plan = items.filterIsInstance<TurnItem.Plan>().lastOrNull { it.plan.isNotBlank() }
  if (todoEntry == null && plan == null) {
    EmptyTab(t("work.plan.empty"))
  }
  todoEntry?.let {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)) {
      TodoList(todos(it.input))
    }
  }
  plan?.let {
    Column(Modifier.padding(horizontal = 4.dp)) {
      MarkdownText(markdown, it.plan, true, actions)
    }
  }
}

@Composable
private fun TasksTab(tasks: List<TaskCard>) {
  if (tasks.isEmpty()) {
    EmptyTab(t("work.tasks.empty"))
  }
  for (card in tasks.asReversed()) {
    key(card.id) {
      TaskRowView(card)
    }
  }
}

@Composable
private fun EmptyTab(text: String) {
  Text(text, Modifier.padding(4.dp), color = colors.muted, style = smallStyle)
}
