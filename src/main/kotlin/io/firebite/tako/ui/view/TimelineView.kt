package io.firebite.tako.ui.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.intellij.openapi.ui.MessageDialogBuilder
import io.firebite.tako.chat.thread.QueuedPrompts
import io.firebite.tako.ide.editor.IdeFiles
import io.firebite.tako.ui.clock
import io.firebite.tako.ui.elapsed
import io.firebite.tako.ui.state.AssistantText
import io.firebite.tako.ui.state.Fold
import io.firebite.tako.ui.state.Follow
import io.firebite.tako.ui.state.Prompt
import io.firebite.tako.ui.state.QueuedPrompt
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Turn
import io.firebite.tako.ui.state.TurnItem
import io.firebite.tako.ui.state.WorkTab
import io.firebite.tako.ui.state.agentToSetUp
import io.firebite.tako.ui.state.bringBack
import io.firebite.tako.ui.state.canActOn
import io.firebite.tako.ui.state.canBringBack
import io.firebite.tako.ui.state.canMoveUp
import io.firebite.tako.ui.state.canQuote
import io.firebite.tako.ui.state.canRewind
import io.firebite.tako.ui.state.canSteer
import io.firebite.tako.ui.state.editQueued
import io.firebite.tako.ui.state.follow
import io.firebite.tako.ui.state.implementElsewhere
import io.firebite.tako.ui.state.moveQueuedUp
import io.firebite.tako.ui.state.noteExpansion
import io.firebite.tako.ui.state.ownTurns
import io.firebite.tako.ui.state.promptsUpTo
import io.firebite.tako.ui.state.removeQueued
import io.firebite.tako.ui.state.rewind
import io.firebite.tako.ui.state.savePlan
import io.firebite.tako.ui.state.sendQueuedNow
import io.firebite.tako.ui.state.steerQueued
import io.firebite.tako.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * The turns as rows: prompts, what the agent did (some folded away), what each turn changed and queued prompts. Forks
 * copy the current agent's session, so they are offered after the last agent switch only and count prompts from there.
 */
private fun timelineRows(thread: ThreadState): List<TimelineRow> {
  val rows = ArrayList<TimelineRow>()
  val turns = thread.ownTurns
  val lastSwitch = turns.indexOfLast { turn -> turn.items.any { it is TurnItem.Divider } }
  var prompts = 0
  for ((index, turn) in turns.withIndex()) {
    if (index == lastSwitch) {
      prompts = 0
    }
    val canFork = thread.info?.capabilities?.fork == true && !thread.sideChat && index > lastSwitch
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

private suspend fun ThreadState.followEnd() {
  this.followingEnd = true
  try {
    this.scrollState.scrollTo(this.scrollState.maxValue)
  } finally {
    this.followingEnd = false
  }
}

/**
 * A thread's conversation over its composer; an empty thread shows a headline instead.
 *
 * @param changesInPane a [WorkPane] beside it lists the changes, so each turn only points there
 */
@Composable
fun ThreadView(thread: ThreadState, markdown: ChatMarkdown, actions: CodeActions, modifier: Modifier,
  changesInPane: Boolean = false) {
  Column(modifier.fillMaxWidth()) {
    if (thread.ownTurns.isEmpty()) {
      Spacer(Modifier.weight(1f))
      Hero(thread)
      thread.agentToSetUp?.let { agent ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { AgentSetupCard(thread, agent) }
      }
      Dock(thread, markdown, actions)
      Spacer(Modifier.weight(1f))
    } else {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Timeline(thread, markdown, actions, changesInPane)
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
private fun Timeline(thread: ThreadState, markdown: ChatMarkdown, actions: CodeActions, changesInPane: Boolean) {
  val scrollState = thread.scrollState
  val rows = timelineRows(thread)
  LaunchedEffect(scrollState) {
    snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }.collect { (scrolling, canForward) ->
      val sinceExpansion = System.currentTimeMillis() - thread.expandedAt
      when (follow(thread.stick, scrolling, thread.followingEnd, canForward, sinceExpansion)) {
        Follow.PIN -> thread.stick = true
        Follow.UNPIN -> thread.stick = false
        Follow.SCROLL_TO_END -> thread.followEnd()
        Follow.NOTHING -> {}
      }
    }
  }
  LaunchedEffect(thread.scrollToEnd) {
    thread.stick = true
    thread.followEnd()
  }
  VerticallyScrollableContainer(Modifier.fillMaxSize(), scrollState = scrollState) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
      for (row in rows) {
        key(row.key) {
          Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = LANE).fillMaxWidth()) {
              TimelineRowView(thread, row, markdown, actions, changesInPane)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TimelineRowView(thread: ThreadState, row: TimelineRow, markdown: ChatMarkdown, actions: CodeActions,
  changesInPane: Boolean) {
  when (row) {
    is TimelineRow.PromptRow -> UserRow(thread, row.prompt, rewinds = row.turn.takeIf { thread.canRewind(it) })
    is TimelineRow.Item -> Box(if (row.foldEnd) Modifier.padding(bottom = 8.dp) else Modifier) {
      TurnItemView(thread, row.item, row.forkPrompts, markdown, actions)
    }
    is TimelineRow.FoldHead -> FoldHeadView(thread, row.fold)
    is TimelineRow.Working -> WorkingRow(thread)
    is TimelineRow.Error -> InlineAlert(row.text, Modifier.fillMaxWidth().padding(bottom = 12.dp))
    is TimelineRow.Changes -> if (changesInPane) ChangesAnchor(thread, row.turn) else ChangesCard(thread, row.turn)
    is TimelineRow.Queued -> UserRow(thread, row.queued.prompt, row.queued)
  }
}

@Composable
private fun TurnItemView(thread: ThreadState, item: TurnItem, forkPrompts: Int?, markdown: ChatMarkdown,
  actions: CodeActions) {
  when (item) {
    is TurnItem.Text -> AssistantRow(thread, item.text, markdown, actions,
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
    is TurnItem.Plan -> PlanCard(thread, item, markdown, actions)
    is TurnItem.FollowUp -> UserRow(thread, item.prompt)
    is TurnItem.Divider -> Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Rule(Modifier.weight(1f))
      Text(item.text, color = colors.muted, style = smallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Rule(Modifier.weight(1f))
    }
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
/**
 * @param queued  the prompt's place in the queue, while the agent has not taken it
 * @param rewinds the turn the prompt starts, when the thread can rewind to before it
 */
private fun UserRow(thread: ThreadState, prompt: Prompt, queued: QueuedPrompt? = null, rewinds: Turn? = null) {
  val workspace = thread.workspace
  val (interaction, hovered) = rememberHover()
  val long = prompt.text.length > COLLAPSE_CHARS || prompt.text.count { it == '\n' } >= COLLAPSE_LINES
  var collapsed by remember(prompt) { mutableStateOf(long) }
  val rem = cssRem
  Column(Modifier.fillMaxWidth().hoverable(interaction).padding(bottom = 6.dp), horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Box(Modifier.fillMaxWidth(0.8f).alpha(if (queued != null) 0.6f else 1f), contentAlignment = Alignment.CenterEnd) {
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
    if (queued != null) {
      QueuedMeta(thread, queued)
    }
    HoverMeta(hovered) {
      if (long) {
        val (toggle, toggleHovered) = rememberHover()
        Text(if (collapsed) t("timeline.show.more") else t("timeline.show.less"),
          Modifier.hoverable(toggle).clickable(toggle, indication = null) {
            thread.noteExpansion()
            collapsed = !collapsed
          }
            .padding(horizontal = 6.dp), color = if (toggleHovered) colors.fg else colors.muted, style = smallStyle)
      }
      Text(clock(prompt.at), color = colors.muted, style = smallStyle.copy(fontFeatureSettings = "tnum"))
      IconBtn(Icons.copy, t("common.copy"), iconSize = 14.dp) { copyText(prompt.text) }
      if (rewinds != null) {
        RewindButton(thread, rewinds)
      }
    }
  }
}

/** Opens the choice to rewind the conversation, or the conversation and the files, to before the turn's prompt. */
@Composable
private fun RewindButton(thread: ThreadState, turn: Turn) {
  var open by remember(turn) { mutableStateOf(false) }
  var hasSnapshot by remember(turn) { mutableStateOf(false) }
  LaunchedEffect(open) {
    if (open) {
      hasSnapshot = false
      val controller = thread.controller
      val prompts = thread.promptsUpTo(turn)
      hasSnapshot = controller != null &&
        withContext(Dispatchers.IO) { controller.hasCheckpoint(prompts.size - 1, prompts.last()) }
    }
  }
  Box {
    IconBtn(Icons.history, t("timeline.rewind"), iconSize = 14.dp) { open = true }
    if (open) {
      MenuPopup(listOf(
        MenuEntry(t("timeline.rewind.conversation"), t("timeline.rewind.conversation.description")) {
          confirmRewind(thread, turn, false)
        },
        MenuEntry(t("timeline.rewind.files"),
          if (hasSnapshot) t("timeline.rewind.files.description") else t("timeline.rewind.files.unavailable"),
          enabled = hasSnapshot) {
          confirmRewind(thread, turn, true)
        }), t("timeline.rewind.menu"), Alignment.End) { open = false }
    }
  }
}

private fun confirmRewind(thread: ThreadState, turn: Turn, files: Boolean) {
  val title = if (files) t("timeline.rewind.files") else t("timeline.rewind.conversation")
  val text = if (files) t("timeline.rewind.confirm") + "\n\n" + t("timeline.rewind.files.confirm")
  else t("timeline.rewind.confirm")
  if (MessageDialogBuilder.yesNo(title, text).guessWindowAndAsk()) {
    thread.rewind(turn, files)
  }
}

/** A queued prompt's status, and while it waits, what the person can do with it. */
@Composable
private fun QueuedMeta(thread: ThreadState, queued: QueuedPrompt) {
  val label = when (queued.status) {
    QueuedPrompts.Status.HELD -> if (thread.queueHeld || !thread.busy) t("timeline.queued.held") else t("timeline.queued")
    QueuedPrompts.Status.NEXT -> t("timeline.queued.next")
    QueuedPrompts.Status.SENDING -> t("timeline.queued.sending")
    QueuedPrompts.Status.STEERING -> t("timeline.queued.steering")
  }
  Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(label, color = colors.muted, style = smallStyle)
    if (queued.status != QueuedPrompts.Status.HELD) {
      return@Row
    }
    if (thread.busy) {
      TextAction(t("queue.send.now"), t("queue.send.now.tip")) { thread.sendQueuedNow(queued) }
      if (thread.canSteer && QueuedPrompts.isSteerable(queued.prompt.text)) {
        TextAction(t("queue.steer"), t("queue.steer.tip")) { thread.steerQueued(queued) }
      }
    } else {
      TextAction(t("queue.send"), t("queue.send.tip")) { thread.sendQueuedNow(queued) }
    }
    if (thread.canQuote) {
      TextAction(t("queue.edit"), t("queue.edit.tip")) { thread.editQueued(queued) }
    }
    if (thread.canMoveUp(queued)) {
      TextAction(t("queue.move.up"), null) { thread.moveQueuedUp(queued) }
    }
    TextAction(t("queue.remove"), null) { thread.removeQueued(queued) }
  }
}

/** A small text button, muted until hovered. */
@Composable
private fun TextAction(label: String, tip: String?, onClick: () -> Unit) {
  val (interaction, hovered) = rememberHover()
  Tip(tip) {
    Text(label, Modifier.hoverable(interaction).clickable(interaction, indication = null, onClick = onClick),
      color = if (hovered) colors.fg else colors.muted, style = smallStyle)
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

/**
 * @param thread whose composer takes quotes of the selected text
 * @param onFork forks the thread after this text, null where no fork goes
 */
@Composable
private fun AssistantRow(thread: ThreadState, text: AssistantText, markdown: ChatMarkdown, actions: CodeActions,
  onFork: (() -> Unit)?) {
  val (interaction, hovered) = rememberHover()
  val fade = remember { Animatable(if (text.done) 1f else 0f) }
  LaunchedEffect(Unit) {
    fade.animateTo(1f, tween(600, easing = LinearOutSlowInEasing))
  }
  Column(Modifier.fillMaxWidth().hoverable(interaction).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)) {
    QuoteMenu(thread) {
      MarkdownText(markdown, text.text, text.done, actions, Modifier.graphicsLayer { this.alpha = fade.value })
    }
    HoverMeta(hovered) {
      IconBtn(Icons.copy, t("common.copy"), iconSize = 14.dp) { actions.copy(text.text) }
      if (thread.sideChat && text.done && thread.canBringBack) {
        IconBtn(Icons.bringBack, t("side.bring.back"), iconSize = 14.dp) { thread.bringBack(text.text) }
      }
      if (onFork != null) {
        IconBtn(Icons.fork, t("timeline.fork"), iconSize = 14.dp, onClick = onFork)
      }
    }
  }
}

@Composable
private fun FoldHeadView(thread: ThreadState, fold: Fold) {
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    val (interaction, hovered) = rememberHover()
    Row(Modifier.fillMaxWidth().hoverClick(alpha = 0f, interaction = interaction) {
      thread.noteExpansion()
      fold.open = !fold.open
    }
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
    val text = when {
      thread.approvals.isNotEmpty() || thread.setupFailed -> t("timeline.working.waiting", since)
      thread.setup != null -> t("timeline.working.setup", since)
      else -> t("timeline.working", since)
    }
    Text(text,
      Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), color = colors.muted,
      style = textStyle.copy(fontFeatureSettings = "tnum"))
    Rule()
  }
}

/** One line for what the turn changed, which shows the work pane's list of changes. */
@Composable
private fun ChangesAnchor(thread: ThreadState, turn: Turn) {
  val changes = turn.changeList
  Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 26.dp).hoverClick(alpha = 0.6f) {
    thread.workTab = WorkTab.CHANGES
  }.padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    RowIcon { Ico(Icons.diff, tint = colors.muted) }
    Text(t("timeline.changes", changes.size), Modifier.weight(1f), color = colors.muted, maxLines = 1,
      overflow = TextOverflow.Ellipsis)
    Text("+" + changes.sumOf { it.added }, color = colors.add, style = monoStyle)
    Text("−" + changes.sumOf { it.removed }, color = colors.del, style = monoStyle)
  }
}

@Composable
fun ChangesCard(thread: ThreadState, turn: Turn) {
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
        Row(Modifier.height(26.dp).hoverClick(interaction = interaction) { thread.controller?.diffs()?.showTurnDiff(turn.no, null) }
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
              thread.controller?.diffs()?.showTurnDiff(turn.no, change.path)
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

/** The menu entries that hand the plan to a new thread; none for a blank plan. */
fun implementElsewhereEntries(thread: ThreadState, plan: TurnItem.Plan): List<MenuEntry> {
  if (plan.plan.isBlank()) {
    return emptyList()
  }
  val enabled = thread.canActOn(plan)
  return listOf(
    MenuEntry(t("plan.implement.thread"), icon = Icons.thread, enabled = enabled) {
      thread.implementElsewhere(plan, false)
    },
    MenuEntry(t("plan.implement.worktree"), icon = Icons.worktree, enabled = enabled) {
      thread.implementElsewhere(plan, true)
    })
}

@Composable
private fun PlanMenuButton(thread: ThreadState, item: TurnItem.Plan) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconBtn(Icons.more, t("plan.more"), iconSize = 14.dp) { open = true }
    if (open) {
      MenuPopup(implementElsewhereEntries(thread, item), alignment = Alignment.End) { open = false }
    }
  }
}

/** A plan the agent proposes, long ones collapsed. */
@Composable
private fun PlanCard(thread: ThreadState, item: TurnItem.Plan, markdown: ChatMarkdown, actions: CodeActions) {
  val plan = item.plan
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
      if (plan.isNotBlank()) {
        IconBtn(Icons.download, t("plan.save"), iconSize = 14.dp) { thread.savePlan(item) }
      }
      IconBtn(Icons.copy, t("timeline.plan.copy"), iconSize = 14.dp) { actions.copy(plan) }
      if (plan.isNotBlank()) {
        PlanMenuButton(thread, item)
      }
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
        Modifier.padding(top = 8.dp)) {
        thread.noteExpansion()
        collapsed = !collapsed
      }
    }
  }
}
