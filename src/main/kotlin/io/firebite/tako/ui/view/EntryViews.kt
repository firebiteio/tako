package io.firebite.tako.ui.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.json.Json
import io.firebite.tako.ui.state.EntryStatus
import io.firebite.tako.ui.state.LabelPart
import io.firebite.tako.ui.state.OUTPUT_LIMIT
import io.firebite.tako.ui.state.Step
import io.firebite.tako.ui.state.SubText
import io.firebite.tako.ui.state.TaskCard
import io.firebite.tako.ui.state.TaskState
import io.firebite.tako.ui.state.TaskType
import io.firebite.tako.ui.state.Todo
import io.firebite.tako.ui.state.Tool
import io.firebite.tako.ui.state.ToolEntry
import io.firebite.tako.ui.state.WorkGroup
import io.firebite.tako.ui.state.cardState
import io.firebite.tako.ui.state.entryLabel
import io.firebite.tako.ui.state.groupSummary
import io.firebite.tako.ui.state.kind
import io.firebite.tako.ui.state.noteExpansion
import io.firebite.tako.ui.state.prettyJson
import io.firebite.tako.ui.state.string
import io.firebite.tako.ui.state.taskTitle
import io.firebite.tako.ui.state.todos
import io.firebite.tako.ui.t
import io.firebite.tako.ui.truncate
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey


/** A row label's text, its monospace runs in the editor font a little smaller. */
@Composable
private fun rowLabelText(parts: List<LabelPart>): AnnotatedString {
  val mono = JewelTheme.editorTextStyle.fontFamily
  return buildAnnotatedString {
    for (part in parts) {
      when (part) {
        is LabelPart.Plain -> this.append(part.text)
        is LabelPart.Mono -> this.withStyle(SpanStyle(fontFamily = mono, fontSize = 0.92.em)) { this.append(part.text) }
      }
    }
  }
}

/** One tool call: a row with its label, and what it did once opened. */
@Composable
fun ToolEntryView(entry: ToolEntry, markdown: ChatMarkdown, actions: CodeActions) {
  val label = entryLabel(entry)
  val running = entry.status == EntryStatus.RUNNING && entry.thread.busy
  val failed = entry.status == EntryStatus.ERROR
  Column(Modifier.fillMaxWidth()) {
    Tip(label.joinToString("") { it.text }) {
      Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).hoverClick(alpha = 0.6f) {
        entry.thread.noteExpansion()
        entry.open = !entry.open
      }
        .padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RowIcon {
          Ico(Icons.category(entry.category), tint = if (failed) colors.del.copy(alpha = 0.7f) else colors.muted)
        }
        val declined = entry.status == EntryStatus.DECLINED
        ShineText(rowLabelText(label), running, colors.muted,
          textStyle.copy(textDecoration = if (declined) TextDecoration.LineThrough else null), Modifier.weight(1f))
        if (entry.added > 0 || entry.removed > 0) {
          Text(buildAnnotatedString {
            if (entry.added > 0) {
              this.withStyle(SpanStyle(color = colors.add)) { this.append("+" + entry.added + " ") }
            }
            if (entry.removed > 0) {
              this.withStyle(SpanStyle(color = colors.del)) { this.append("−" + entry.removed) }
            }
          }, style = monoTinyStyle)
        }
        if (failed) {
          Ico(Icons.closeSmall, size = 12.dp, tint = colors.del)
        }
        Chevron(entry.open, tint = colors.faint)
      }
    }
    if (entry.open) {
      Column(Modifier.fillMaxWidth().padding(start = 30.dp, top = 2.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EntryDetails(entry)
        entry.output?.let { Panel(it, if (entry.outputIsError) colors.del else colors.muted) }
        Steps(entry.steps, markdown, actions)
      }
    }
  }
}

/** What an expanded tool row shows, decided by what the call does. */
@Composable
private fun EntryDetails(entry: ToolEntry) {
  val input = entry.input
  when (entry.category) {
    ToolKind.COMMAND -> {
      val command = input.string("command")
      if (command == null) {
        JsonPanel(entry)
      } else {
        Panel((if (entry.name == Tool.POWERSHELL) "PS> " else "$ ") + command, colors.muted)
      }
    }
    ToolKind.EDIT -> EditDetails(entry)
    ToolKind.TODO -> TodoList(todos(input))
    ToolKind.READ, ToolKind.AGENT -> {}
    else -> JsonPanel(entry)
  }
}

@Composable
private fun JsonPanel(entry: ToolEntry) {
  if (entry.input.size() > 0) {
    Panel(prettyJson(entry.input), colors.muted)
  }
}

/** An edit's change as the call describes it: text replacements, a whole new content, or patches. */
@Composable
private fun EditDetails(entry: ToolEntry) {
  val input = entry.input
  when {
    Json.array(input, "edits") != null -> for (edit in Json.objects(input, "edits")) {
      InlineDiff(edit.string("old_string"), edit.string("new_string"))
    }
    input.has("old_string") || input.has("new_string") -> InlineDiff(input.string("old_string"),
      input.string("new_string"))
    input.has("content") -> Panel(truncate(input.string("content") ?: "", OUTPUT_LIMIT), colors.muted)
    Json.array(input, "changes") != null -> for (patch in Json.objects(input, "changes")) {
      Panel(truncate((patch.string("path") ?: "") + "\n" + (patch.string("diff") ?: ""), OUTPUT_LIMIT), colors.muted)
    }
    else -> JsonPanel(entry)
  }
}

/** Preformatted text in a scrolling box, such as a command and its output. */
@Composable
fun Panel(text: String, color: Color) {
  Box(Modifier.fillMaxWidth().heightIn(max = cssRem * 16).clip(RoundedCornerShape(8.dp))
    .background(colors.hover.copy(alpha = colors.hover.alpha * 0.55f)).verticalScroll(rememberScrollState())
    .padding(horizontal = 12.dp, vertical = 8.dp)) {
    SelectionContainer {
      Text(text, color = color, style = monoTinyStyle, lineHeight = monoTinyStyle.fontSize * 1.6f)
    }
  }
}

@Composable
private fun InlineDiff(oldText: String?, newText: String?) {
  val style = monoTinyStyle.copy(lineHeight = monoTinyStyle.fontSize * 1.55f)
  Column(Modifier.fillMaxWidth().heightIn(max = cssRem * 16).clip(RoundedCornerShape(8.dp))
    .verticalScroll(rememberScrollState())) {
    SelectionContainer {
      Column {
        for (line in (oldText ?: "").split('\n')) {
          Text("- $line", Modifier.fillMaxWidth().background(colors.removed).padding(horizontal = 10.dp), style = style)
        }
        for (line in (newText ?: "").split('\n')) {
          Text("+ $line", Modifier.fillMaxWidth().background(colors.added).padding(horizontal = 10.dp), style = style)
        }
      }
    }
  }
}

@Composable
private fun TodoList(todos: List<Todo>) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    for (todo in todos) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (todo.status) {
          "completed" -> Ico(Icons.todoDone, size = 14.dp, tint = colors.add)
          "in_progress" -> Ico(Icons.todoActive, size = 14.dp, tint = colors.muted)
          else -> Ico(Icons.todoPending, size = 14.dp, tint = colors.muted)
        }
        when (todo.status) {
          "completed" -> Text(todo.content, color = colors.muted, textDecoration = TextDecoration.LineThrough)
          "in_progress" -> Text(todo.activeForm ?: todo.content, color = colors.fg, fontWeight = FontWeight.Medium)
          else -> Text(todo.content, color = colors.body)
        }
      }
    }
  }
}

/** A subagent's own calls and texts. */
@Composable
fun Steps(steps: List<Step>, markdown: ChatMarkdown, actions: CodeActions) {
  for (step in steps) {
    when (step) {
      is SubText -> Text(step.text, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = colors.muted,
        style = smallStyle)
      is ToolEntry -> ToolEntryView(step, markdown, actions)
      is TaskCard -> TaskRowView(step)
    }
  }
}

/** Consecutive tool calls of a turn, one row that opens to list them. */
@Composable
fun GroupView(group: WorkGroup, markdown: ChatMarkdown, actions: CodeActions) {
  val entries = group.entries
  if (entries.size <= 1) {
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
      entries.firstOrNull()?.let { ToolEntryView(it, markdown, actions) }
    }
    return
  }
  val categories = entries.map { it.category }.toSet()
  val running = entries.firstOrNull { it.status == EntryStatus.RUNNING }?.takeIf { group.thread.busy }
  Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).hoverClick(alpha = 0.6f) {
      group.thread.noteExpansion()
      group.collapsed = !group.collapsed
    }.padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      RowIcon {
        Ico(if (categories.size == 1) Icons.category(categories.first()) else Icons.group, tint = colors.muted)
      }
      val text = if (running != null) rowLabelText(entryLabel(running)) else AnnotatedString(groupSummary(group))
      ShineText(text, running != null, colors.muted, textStyle, Modifier.weight(1f))
      if (entries.any { it.status == EntryStatus.ERROR }) {
        Ico(Icons.closeSmall, size = 12.dp, tint = colors.del)
      }
      Chevron(!group.collapsed, tint = colors.faint)
    }
    if (!group.collapsed) {
      val divider = colors.divider
      Column(Modifier.padding(start = 12.dp).heightIn(max = cssRem * 18).drawBehind {
        this.drawLine(divider, Offset.Zero, Offset(0f, this.size.height), 1.dp.toPx())
      }.verticalScroll(rememberScrollState()).padding(start = 8.dp)) {
        for (entry in entries) {
          ToolEntryView(entry, markdown, actions)
        }
      }
    }
  }
}

fun taskIcon(type: TaskType): IconKey = when (type) {
  TaskType.AGENT -> Icons.agent
  TaskType.SHELL -> Icons.shell
  TaskType.WORKFLOW -> Icons.workflow
}

@Composable
fun TaskStateIcon(state: TaskState, size: Dp = 12.dp) {
  when (state) {
    TaskState.RUNNING -> {
      val transition = rememberInfiniteTransition()
      val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)))
      Ico(Icons.loader, Modifier.graphicsLayer { this.rotationZ = angle }, size, colors.primary)
    }
    TaskState.DONE -> Ico(Icons.check, size = size, tint = colors.add)
    TaskState.ERROR -> Ico(Icons.closeSmall, size = size, tint = colors.del)
  }
}

/** A task's card in the turn; it opens the task's panel. */
@Composable
fun TaskRowView(card: TaskCard) {
  val thread = card.thread
  val state = thread.cardState(card)
  val now = thread.workspace.now
  val running = state == TaskState.RUNNING
  Tip(t("tasks.click.for.details", taskActivity(card))) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).heightIn(min = 26.dp).hoverClick(alpha = 0.6f) {
      thread.workspace.taskPanel = card
    }.padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      RowIcon { Ico(taskIcon(card.taskType), tint = if (running) colors.primary else colors.muted) }
      ShineText(AnnotatedString(card.kind() + " · " + card.taskTitle()), running,
        if (running) colors.muted else colors.body, textStyle, Modifier.weight(1f))
      Text(taskStats(card, now), color = colors.muted, style = tinyStyle.copy(fontFeatureSettings = "tnum"))
      TaskStateIcon(state)
      Chevron(false, tint = colors.faint)
    }
  }
}

/** A task that ended after its turn, at the end of the conversation. */
@Composable
fun CompletionRowView(card: TaskCard) {
  val thread = card.thread
  val state = thread.cardState(card)
  Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).heightIn(min = 26.dp).hoverClick(alpha = 0.6f) {
    thread.workspace.taskPanel = card
  }.padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    val failed = state == TaskState.ERROR
    RowIcon { Ico(if (failed) Icons.error else Icons.success, tint = if (failed) colors.del else colors.add) }
    Text(if (state == TaskState.ERROR) t("tasks.completion.failed", card.kind(), card.taskTitle()) else
      t("tasks.completion.finished", card.kind(), card.taskTitle()), Modifier.weight(1f), color = colors.body,
      maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(taskStats(card, thread.workspace.now), color = colors.muted, style = tinyStyle.copy(fontFeatureSettings = "tnum"))
    Chevron(false, tint = colors.faint)
  }
}
