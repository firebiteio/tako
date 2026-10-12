package io.firebite.tako.ui.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.firebite.tako.ide.editor.IdeFiles
import io.firebite.tako.ui.clock
import io.firebite.tako.ui.elapsed
import io.firebite.tako.ui.state.AssistantText
import io.firebite.tako.ui.state.Fold
import io.firebite.tako.ui.state.Prompt
import io.firebite.tako.ui.state.QueuedPrompt
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Turn
import io.firebite.tako.ui.state.TurnItem
import io.firebite.tako.ui.t
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** A prompt longer than this starts collapsed. */
private const val COLLAPSE_CHARS = 600
private const val COLLAPSE_LINES = 8
/** A plan longer than this starts collapsed. */
private const val PLAN_COLLAPSE_LINES = 24

/** One row of the timeline's list. */
private sealed class TimelineRow(val key: String) {
  class PromptRow(val turn: Turn, val prompt: Prompt) : TimelineRow("p" + System.identityHashCode(turn))

  /**
   * @param foldEnd     the last item of an open fold, which keeps some space under it
   * @param forkPrompts how many prompts a fork after this item keeps, null where no fork goes
   */
  class Item(val turn: Turn, val item: TurnItem, val foldEnd: Boolean = false, val forkPrompts: Int? = null) :
    TimelineRow("i" + System.identityHashCode(item))

  class FoldHead(val turn: Turn, val fold: Fold) : TimelineRow("f" + System.identityHashCode(fold))

  class Working(val turn: Turn) : TimelineRow("w" + System.identityHashCode(turn))

  class Error(val turn: Turn, val text: String) : TimelineRow("e" + System.identityHashCode(turn))

  class Changes(val turn: Turn) : TimelineRow("c" + System.identityHashCode(turn))

  class Queued(val queued: QueuedPrompt) : TimelineRow("q" + System.identityHashCode(queued))
}

/** The turns as rows: prompts, what the agent did (some folded away), what each turn changed and queued prompts. */
private fun timelineRows(thread: ThreadState): List<TimelineRow> {
  val rows = ArrayList<TimelineRow>()
  val canFork = thread.info?.capabilities?.fork == true
  var prompts = 0
  for (turn in thread.turns) {
    turn.prompt?.let {
      prompts++
      rows.add(TimelineRow.PromptRow(turn, it))
    }
    val forkAfter = if (canFork && turn.done && prompts > 0) turn.items.lastOrNull { it is TurnItem.Text } else null
    for (item in turn.items) {
      val fold = turn.folds.firstOrNull { item in it.items }
      if (fold != null) {
        if (item === fold.first) {
          rows.add(TimelineRow.FoldHead(turn, fold))
        }
        if (fold.open) {
          rows.add(TimelineRow.Item(turn, item, item === fold.last, prompts.takeIf { item === forkAfter }))
        }
        continue
      }
      rows.add(TimelineRow.Item(turn, item, forkPrompts = prompts.takeIf { item === forkAfter }))
    }
    if (!turn.replayed && !turn.done && thread.busy) {
      rows.add(TimelineRow.Working(turn))
    }
    turn.error?.let { rows.add(TimelineRow.Error(turn, it)) }
    if (turn.changeList.isNotEmpty()) {
      rows.add(TimelineRow.Changes(turn))
    }
  }
  for (queued in thread.queued) {
    rows.add(TimelineRow.Queued(queued))
  }
  return rows
}

private suspend fun ScrollState.scrollToEnd() {
  this.scrollTo(this.maxValue)
}

/** A thread's conversation over its composer; an empty thread shows a headline instead. */
@Composable
fun ThreadView(thread: ThreadState, markdown: ChatMarkdown, actions: CodeActions, modifier: Modifier) {
  Column(modifier.fillMaxWidth()) {
    if (thread.turns.isEmpty()) {
      Spacer(Modifier.weight(1f))
      Hero(thread)
      Dock(thread, markdown, actions)
      Spacer(Modifier.weight(1f))
    } else {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Timeline(thread, markdown, actions)
        if (!thread.stick && thread.scrollState.canScrollForward) {
          ScrollToEnd(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { thread.scrollToEnd++ }
        }
      }
      Dock(thread, markdown, actions)
    }
  }
}

/** A glassy pill over the timeline's end. */
@Composable
private fun ScrollToEnd(modifier: Modifier, onClick: () -> Unit) {
  val (interaction, hovered) = rememberHover()
  val shape = RoundedCornerShape(50)
  val color = if (hovered) colors.fg else colors.muted
  Row(modifier.height(26.dp).shadow(2.dp, shape).clip(shape).background(colors.bg.copy(alpha = 0.8f))
    .border(1.dp, colors.softBorder, shape).hoverable(interaction)
    .clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Ico(Icons.arrowDown, size = 14.dp, tint = color)
    Text(t("timeline.scroll.to.end"), color = color, style = smallStyle)
  }
}

@Composable
private fun Hero(thread: ThreadState) {
  val workspace = thread.workspace
  val name = workspace.projectOf(thread).name
  Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(heroTitle(thread.id, name), Modifier.padding(horizontal = 16.dp), color = colors.fg,
      fontSize = textStyle.fontSize * 1.6f, letterSpacing = (-0.02).em, textAlign = TextAlign.Center)
    val info = thread.info
    if (info != null) {
      Text(t("composer.hint", info.authDescription), Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
        color = colors.muted, style = smallStyle, textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun Timeline(thread: ThreadState, markdown: ChatMarkdown, actions: CodeActions) {
  val scrollState = thread.scrollState
  val rows = timelineRows(thread)
  LaunchedEffect(scrollState) {
    snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }.collect { (scrolling, canForward) ->
      if (scrolling) {
        thread.stick = !canForward
      } else if (thread.stick && canForward) {
        scrollState.scrollToEnd()
      }
    }
  }
  LaunchedEffect(thread.scrollToEnd) {
    thread.stick = true
    scrollState.scrollToEnd()
  }
  VerticallyScrollableContainer(Modifier.fillMaxSize(), scrollState = scrollState) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
      for (row in rows) {
        key(row.key) {
          Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = LANE).fillMaxWidth()) {
              TimelineRowView(thread, row, markdown, actions)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TimelineRowView(thread: ThreadState, row: TimelineRow, markdown: ChatMarkdown, actions: CodeActions) {
  when (row) {
    is TimelineRow.PromptRow -> UserRow(thread, row.prompt)
    is TimelineRow.Item -> Box(if (row.foldEnd) Modifier.padding(bottom = 8.dp) else Modifier) {
      TurnItemView(thread, row.item, row.forkPrompts, markdown, actions)
    }
    is TimelineRow.FoldHead -> FoldHeadView(row.fold)
    is TimelineRow.Working -> WorkingRow(thread)
    is TimelineRow.Error -> InlineAlert(row.text, Modifier.fillMaxWidth().padding(bottom = 12.dp))
    is TimelineRow.Changes -> ChangesCard(thread, row.turn)
    is TimelineRow.Queued -> UserRow(thread, row.queued.prompt, true)
  }
}

@Composable
private fun TurnItemView(thread: ThreadState, item: TurnItem, forkPrompts: Int?, markdown: ChatMarkdown,
  actions: CodeActions) {
  when (item) {
    is TurnItem.Text -> AssistantRow(item.text, markdown, actions,
      forkPrompts?.let { prompts -> { thread.workspace.service.fork(thread.id, prompts) } })
    is TurnItem.Thinking -> Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(2.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      RowIcon { Ico(Icons.effort, tint = colors.muted) }
      ShineText(AnnotatedString(t("timeline.thinking")), true, colors.muted, textStyle, Modifier.weight(1f))
    }
    is TurnItem.Note -> Text(item.text, Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
      color = colors.muted, style = smallStyle)
    is TurnItem.Single -> ToolEntryView(item.entry, markdown, actions)
    is TurnItem.Group -> GroupView(item.group, markdown, actions)
    is TurnItem.Task -> TaskRowView(item.card)
    is TurnItem.Completion -> CompletionRowView(item.card)
    is TurnItem.Plan -> PlanCard(item.plan, markdown, actions)
    is TurnItem.FollowUp -> UserRow(thread, item.prompt)
  }
}

/** The leading icon slot of a row. */
@Composable
fun RowIcon(content: @Composable () -> Unit) {
  Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { content() }
}

/** The web page's rem, which its sizes stepped from: the chat's body text size. */
val cssRem: Dp
  @Composable @ReadOnlyComposable get() = textStyle.fontSize.value.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
/** @param queued the prompt waits for the running turn to end */
private fun UserRow(thread: ThreadState, prompt: Prompt, queued: Boolean = false) {
  val workspace = thread.workspace
  val (interaction, hovered) = rememberHover()
  val long = prompt.text.length > COLLAPSE_CHARS || prompt.text.count { it == '\n' } >= COLLAPSE_LINES
  var collapsed by remember(prompt) { mutableStateOf(long) }
  val rem = cssRem
  Column(Modifier.fillMaxWidth().hoverable(interaction).padding(bottom = 6.dp), horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Box(Modifier.fillMaxWidth(0.8f).alpha(if (queued) 0.6f else 1f), contentAlignment = Alignment.CenterEnd) {
      Column(Modifier.clip(RoundedCornerShape(18.dp)).background(colors.message)
        .let { if (collapsed) it.heightIn(max = rem * 11) else it }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (prompt.images.isNotEmpty()) {
          val more = prompt.text.isNotEmpty() || prompt.contexts.isNotEmpty()
          FlowRow(Modifier.padding(bottom = if (more) 6.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (image in prompt.images) {
              ImageThumb(image, 96.dp, { workspace.lightbox = image })
            }
          }
        }
        if (prompt.text.isNotEmpty()) {
          SelectionContainer {
            Text(prompt.text, color = colors.fg, lineHeight = textStyle.fontSize * 1.55f)
          }
        }
        if (prompt.contexts.isNotEmpty()) {
          FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in prompt.contexts) {
              MentionChip(item, null)
            }
          }
        }
      }
      if (collapsed) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(rem * 1.75f)
          .background(Brush.verticalGradient(listOf(Color.Transparent, colors.bg))))
      }
    }
    if (queued) {
      Text(t("timeline.queued"), Modifier.padding(horizontal = 6.dp), color = colors.muted, style = smallStyle)
    }
    HoverMeta(hovered) {
      if (long) {
        val (toggle, toggleHovered) = rememberHover()
        Text(if (collapsed) t("timeline.show.more") else t("timeline.show.less"),
          Modifier.hoverable(toggle).clickable(toggle, indication = null) { collapsed = !collapsed }
            .padding(horizontal = 6.dp), color = if (toggleHovered) colors.fg else colors.muted, style = smallStyle)
      }
      Text(clock(prompt.at), color = colors.muted, style = smallStyle.copy(fontFeatureSettings = "tnum"))
      IconBtn(Icons.copy, t("common.copy"), iconSize = 14.dp) { copyText(prompt.text) }
    }
  }
}

/** A row's time and actions, shown while the mouse is over the row. */
@Composable
private fun HoverMeta(hovered: Boolean, content: @Composable () -> Unit) {
  val alpha by animateFloatAsState(if (hovered) 1f else 0f, tween(200))
  Row(Modifier.heightIn(min = 20.dp).alpha(alpha), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    content()
  }
}

/** @param onFork forks the thread after this text, null where no fork goes */
@Composable
private fun AssistantRow(text: AssistantText, markdown: ChatMarkdown, actions: CodeActions, onFork: (() -> Unit)?) {
  val (interaction, hovered) = rememberHover()
  val fade = remember { Animatable(if (text.done) 1f else 0f) }
  LaunchedEffect(Unit) {
    fade.animateTo(1f, tween(600, easing = LinearOutSlowInEasing))
  }
  Column(Modifier.fillMaxWidth().hoverable(interaction).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)) {
    MarkdownText(markdown, text.text, text.done, actions, Modifier.graphicsLayer { this.alpha = fade.value })
    HoverMeta(hovered) {
      IconBtn(Icons.copy, t("common.copy"), iconSize = 14.dp) { actions.copy(text.text) }
      if (onFork != null) {
        IconBtn(Icons.fork, t("timeline.fork"), iconSize = 14.dp, onClick = onFork)
      }
    }
  }
}

@Composable
private fun FoldHeadView(fold: Fold) {
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    val (interaction, hovered) = rememberHover()
    Row(Modifier.fillMaxWidth().hoverClick(alpha = 0f, interaction = interaction) { fold.open = !fold.open }
      .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      val color = if (hovered) colors.fg else colors.muted
      Text(fold.label, color = color)
      Chevron(fold.open, 14.dp, color)
    }
    Rule()
  }
}

/** The "Working for …" line under the running turn. */
@Composable
private fun WorkingRow(thread: ThreadState) {
  val since = elapsed(thread.startedAt, thread.workspace.now)
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    Text(if (thread.approvals.isNotEmpty()) t("timeline.working.waiting", since) else t("timeline.working", since),
      Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), color = colors.muted,
      style = textStyle.copy(fontFeatureSettings = "tnum"))
    Rule()
  }
}

@Composable
private fun ChangesCard(thread: ThreadState, turn: Turn) {
  val changes = turn.changeList
  val shape = RoundedCornerShape(10.dp)
  Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(shape).background(colors.card)
    .border(1.dp, colors.divider, shape)) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(t("timeline.changes", changes.size), Modifier.weight(1f), style = smallStyle, fontWeight = FontWeight.Medium)
      Text("+" + changes.sumOf { it.added }, color = colors.add, style = monoStyle)
      Text("−" + changes.sumOf { it.removed }, color = colors.del, style = monoStyle)
      if (!turn.replayed) {
        val (interaction, hovered) = rememberHover()
        val color = if (hovered) colors.fg else colors.muted
        Row(Modifier.height(26.dp).hoverClick(interaction = interaction) { thread.controller?.showTurnDiff(turn.no, null) }
          .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Ico(Icons.diff, size = 14.dp, tint = color)
          Text(t("timeline.open.diff"), color = color, style = smallStyle, fontWeight = FontWeight.Medium)
        }
      }
    }
    Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
      for (change in changes) {
        Tip(if (turn.replayed) t("timeline.open.file") else t("timeline.show.diff")) {
          Row(Modifier.fillMaxWidth().hoverClick {
            if (turn.replayed) {
              IdeFiles.open(thread.workspace.project, change.path, 1)
            } else {
              thread.controller?.showTurnDiff(turn.no, change.path)
            }
          }.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(Icons.file, size = 14.dp, tint = colors.muted)
            Text(thread.relative(change.path), Modifier.weight(1f), color = colors.body, style = monoStyle,
              maxLines = 1, overflow = TextOverflow.StartEllipsis)
            if (change.isNew) {
              Text(t("timeline.new"), Modifier.border(1.dp, colors.add.copy(alpha = 0.4f), RoundedCornerShape(50))
                .padding(horizontal = 6.dp), color = colors.add, style = tinyStyle)
            }
            Text("+" + change.added, color = colors.add, style = monoStyle)
            Text("−" + change.removed, color = colors.del, style = monoStyle)
          }
        }
      }
    }
  }
}

/** A plan the agent proposes, long ones collapsed. */
@Composable
private fun PlanCard(plan: String, markdown: ChatMarkdown, actions: CodeActions) {
  val long = plan.count { it == '\n' } >= PLAN_COLLAPSE_LINES
  var collapsed by remember(plan) { mutableStateOf(long) }
  val shape = RoundedCornerShape(22.dp)
  Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(shape).background(colors.card)
    .border(1.dp, colors.softBorder, shape).padding(16.dp)) {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(t("timeline.plan"), Modifier.clip(RoundedCornerShape(6.dp)).background(colors.hover)
        .padding(horizontal = 8.dp, vertical = 1.dp), style = tinyStyle, fontWeight = FontWeight.Medium)
      Text(t("timeline.plan.title"), Modifier.weight(1f))
      IconBtn(Icons.copy, t("timeline.plan.copy"), iconSize = 14.dp) { actions.copy(plan) }
    }
    Box(if (collapsed) Modifier.heightIn(max = cssRem * 22).clip(RoundedCornerShape(0.dp)) else Modifier) {
      MarkdownText(markdown, plan, true, actions)
      if (collapsed) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(cssRem * 6)
          .background(Brush.verticalGradient(listOf(Color.Transparent, colors.card))))
      }
    }
    if (long) {
      OutlineButton(if (collapsed) t("timeline.plan.expand") else t("timeline.plan.collapse"),
        Modifier.padding(top = 8.dp)) { collapsed = !collapsed }
    }
  }
}
