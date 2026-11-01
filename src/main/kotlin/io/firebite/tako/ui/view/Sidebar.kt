package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.intellij.openapi.ui.MessageDialogBuilder
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.chat.ChatService
import io.firebite.tako.ui.ago
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.dateTime
import io.firebite.tako.ui.duration
import io.firebite.tako.ui.elapsed
import io.firebite.tako.ui.money
import io.firebite.tako.ui.samePath
import io.firebite.tako.ui.state.ThreadNode
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.state.isSideChat
import io.firebite.tako.ui.state.runName
import io.firebite.tako.ui.state.sideChatsOf
import io.firebite.tako.ui.state.waitingSideChats
import io.firebite.tako.ui.state.runningTasks
import io.firebite.tako.ui.state.threadTree
import io.firebite.tako.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

private const val SEARCH_DELAY_MILLIS = 300L

enum class StatusKind { INPUT, WORKING, ERROR, DONE, IDLE }

data class ThreadStatus(val kind: StatusKind, val label: String)

fun threadStatus(thread: ThreadState, now: Long): ThreadStatus {
  val approval = thread.approvals.firstOrNull()
  if (approval != null) {
    return ThreadStatus(StatusKind.INPUT, when (approval.prompt.kind) {
      PermissionPrompt.Kind.QUESTION -> t("sidebar.status.question")
      PermissionPrompt.Kind.PLAN -> t("sidebar.status.plan")
      else -> t("sidebar.status.approval")
    })
  }
  if (thread.workspace.sideChatsOf(thread).any { !it.archived && it.approvals.isNotEmpty() }) {
    return ThreadStatus(StatusKind.INPUT, t("sidebar.status.side.chat"))
  }
  if (thread.busy) {
    return ThreadStatus(StatusKind.WORKING, t("sidebar.status.working", elapsed(thread.startedAt, now)))
  }
  val background = thread.runningTasks().size
  if (background > 0) {
    return ThreadStatus(StatusKind.WORKING, t("sidebar.status.background", background))
  }
  if (thread.failed) {
    return ThreadStatus(StatusKind.ERROR, t("sidebar.status.failed"))
  }
  if (thread.finished) {
    return ThreadStatus(StatusKind.DONE, if (thread.unread) t("sidebar.status.done.new") else t("sidebar.status.done"))
  }
  return ThreadStatus(StatusKind.IDLE,
    if (thread.turns.isNotEmpty()) t("sidebar.status.idle") else t("sidebar.status.not.started"))
}

/** The thread's status, recomposing its reader only when the status changes, not on every clock tick. */
@Composable
private fun rememberStatus(workspace: Workspace, thread: ThreadState): ThreadStatus {
  val status by remember(thread) { derivedStateOf(structuralEqualityPolicy()) { threadStatus(thread, workspace.now) } }
  return status
}

/** Threads needing a look that are not shown, for the sidebar toggle's badge. */
fun waitingThreads(workspace: Workspace): Int = workspace.threads.values.count {
  !it.archived && it !== workspace.active && !workspace.isSideChat(it) &&
    (it.approvals.isNotEmpty() || it.unread || workspace.waitingSideChats(it) > 0)
}

/** The thread list: threads grouped by project folder, archived ones apart, each with its live status. */
@Composable
fun Sidebar(workspace: Workspace, narrow: Boolean, modifier: Modifier) {
  Column(modifier) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(t("sidebar.threads").uppercase(io.firebite.tako.ui.textLocale), Modifier.weight(1f), color = colors.muted,
        style = smallStyle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em))
      IconBtn(Icons.search, t("sidebar.search"), color = if (workspace.searching) colors.fg else colors.muted) {
        if (workspace.searching) workspace.closeSearch() else workspace.searching = true
      }
      Box {
        IconBtn(Icons.history, t("sidebar.history")) { workspace.showHistory() }
        if (workspace.sessions != null) {
          SessionsMenu(workspace)
        }
      }
      IconBtn(Icons.addProject, t("sidebar.add.project")) { workspace.service.addProject() }
      IconBtn(Icons.add, t("common.new.thread")) { workspace.newThreadHere() }
    }
    if (workspace.searching) {
      SearchField(workspace)
    }
    Rule()
    val sorted = workspace.threads.values.filter { !workspace.isSideChat(it) }.sortedByDescending { it.updatedAt }
    val runIds = workspace.benches.flatMap { it.threads }.toSet()
    val query = if (workspace.searching) workspace.searchInput.text.toString().trim() else ""
    LaunchedEffect(query) {
      if (query.isNotEmpty()) {
        delay(SEARCH_DELAY_MILLIS)
        workspace.searchSessions(query)
      }
    }
    VerticallyScrollableContainer(Modifier.weight(1f)) {
      Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (query.isNotEmpty()) {
          SearchResults(workspace, sorted, query, narrow)
        } else {
          val projects = workspace.projectList()
          for (project in projects) {
            ProjectGroup(workspace, project, sorted, runIds, narrow)
          }
        }
      }
    }
    workspace.archivedToast?.let { ArchivedToast(workspace, it) }
    val archived = sorted.filter { it.archived }
    if (archived.isNotEmpty()) {
      Rule()
      val (toggleInteraction, toggleHovered) = rememberHover()
      Row(Modifier.fillMaxWidth().hoverable(toggleInteraction)
        .clickable(toggleInteraction, indication = null) { workspace.showArchived = !workspace.showArchived }
        .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (toggleHovered) colors.fg else colors.muted
        Chevron(workspace.showArchived, tint = color)
        Ico(Icons.archive, size = 14.dp, tint = color)
        Text(t("sidebar.archived.count", archived.size), color = color, style = smallStyle)
      }
      if (workspace.showArchived) {
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())
          .padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          for (thread in archived) {
            ThreadRow(workspace, thread, narrow)
          }
        }
      }
    }
    Rule()
    val (usageInteraction, usageHovered) = rememberHover()
    Tip(t("sidebar.usage.title"), Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
      Row(Modifier.fillMaxWidth().hoverClick(interaction = usageInteraction) {
        if (narrow) {
          workspace.setSidebar(false)
        }
        workspace.showUsage()
      }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val color = if (usageHovered) colors.fg else colors.muted
        Ico(Icons.usage, size = 14.dp, tint = color)
        Text(t("sidebar.usage"), color = color, style = smallStyle)
        Spacer(Modifier.weight(1f))
        workspace.usage?.let { Text(money(it.totalCost), color = color, style = smallStyle) }
      }
    }
  }
}

/** Offers to undo the last archive, below the list so it covers nothing, while the thread is still archived. */
@Composable
private fun ArchivedToast(workspace: Workspace, archived: ChatService.Archived) {
  if (workspace.threads[archived.thread]?.archived != true) {
    return
  }
  val shape = RoundedCornerShape(10.dp)
  Row(Modifier.fillMaxWidth().padding(6.dp).clip(shape).background(colors.card).border(1.dp, colors.softBorder, shape)
    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Ico(Icons.archive, size = 14.dp, tint = colors.muted)
    Text(t("sidebar.archived.toast"), Modifier.weight(1f), color = colors.fg, style = smallStyle, maxLines = 1,
      overflow = TextOverflow.Ellipsis)
    OutlineButton(t("sidebar.archived.undo")) { workspace.undoArchive() }
  }
}

@Composable
private fun SearchField(workspace: Workspace) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  TextField(workspace.searchInput, Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
    .focusRequester(focus), placeholder = { Text(t("sidebar.search.placeholder"), color = colors.muted) })
}

/** Every thread, archived ones too, whose title, loaded conversation or session mentions the query. */
@Composable
private fun SearchResults(workspace: Workspace, sorted: List<ThreadState>, query: String, narrow: Boolean) {
  val (hitsQuery, hits) = workspace.searchHits
  val found = sorted.filter {
    it.title.contains(query, true) || it.mentions(query) || (hitsQuery == query && it.id in hits)
  }
  if (found.isEmpty()) {
    Text(if (hitsQuery == query) t("sidebar.search.none") else t("sidebar.search.searching"),
      Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = colors.muted, style = smallStyle)
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
    for (thread in found) {
      ThreadRow(workspace, thread, narrow)
    }
  }
}

/** The folded sidebar: a strip of the threads' statuses, in the sidebar's order, to switch threads without unfolding. */
@Composable
fun SidebarRail(workspace: Workspace, modifier: Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
      IconBtn(Icons.add, t("common.new.thread")) { workspace.newThreadHere() }
    }
    Rule()
    val sorted = workspace.threads.values.filter { !workspace.isSideChat(it) }.sortedByDescending { it.updatedAt }
    val runIds = workspace.benches.flatMap { it.threads }.toSet()
    val projects = workspace.projectList()
    VerticallyScrollableContainer(Modifier.weight(1f)) {
      Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((index, project) in projects.withIndex()) {
          if (index > 0) {
            Box(Modifier.padding(vertical = 4.dp).width(20.dp).height(1.dp).background(colors.divider))
          }
          for (bench in projectBenches(workspace, project)) {
            for (run in bench.threads.mapNotNull { workspace.threads[it] }.filter { !it.archived }) {
              RailThread(workspace, run)
            }
          }
          for (node in projectThreads(workspace, project, sorted, runIds)) {
            RailBranch(workspace, node)
          }
        }
      }
    }
    Rule()
    Box(Modifier.padding(vertical = 4.dp)) {
      IconBtn(Icons.usage, t("sidebar.usage.title")) { workspace.showUsage() }
    }
  }
}

@Composable
private fun RailThread(workspace: Workspace, thread: ThreadState) {
  val status = rememberStatus(workspace, thread)
  val title = workspace.runOf(thread)?.let { thread.runName(it.second) } ?: thread.title
  Tip(title + "\n" + status.label) {
    Box(Modifier.size(32.dp).hoverClick(RoundedCornerShape(8.dp), 0.7f, thread === workspace.active) {
      workspace.open(thread)
    }, contentAlignment = Alignment.Center) {
      StatusIcon(status.kind, thread, statusColor(status.kind))
    }
  }
}

/** The thread and, where the list unfolds them, its sub threads. */
@Composable
private fun RailBranch(workspace: Workspace, node: ThreadNode<ThreadState>) {
  RailThread(workspace, node.thread)
  if (workspace.expandedThreads[node.thread.id] == true) {
    for (child in node.children) {
      RailBranch(workspace, child)
    }
  }
}

private fun projectBenches(workspace: Workspace, project: ChatMessage.ProjectItem): List<ChatMessage.BenchItem> =
  workspace.benches.reversed().filter { workspace.projectAt(it.projectPath).path == project.path }

private fun projectThreads(workspace: Workspace, project: ChatMessage.ProjectItem, sorted: List<ThreadState>,
  runIds: Set<String>): List<ThreadNode<ThreadState>> =
  threadTree(sorted.filter { !it.archived && it.id !in runIds && workspace.projectOf(it).path == project.path },
    { it.id }, { it.parentId })

/** The agents' sessions no thread holds, to resume. */
@Composable
fun SessionsMenu(workspace: Workspace) {
  val sessions = workspace.sessions ?: return
  val several = workspace.projects.size > 1
  val agents = workspace.agents.size > 1
  val entries = sessions.map { item ->
    MenuEntry(item.title, (if (agents) item.agentName + " · " else "") +
      (if (several && item.projectName.isNotEmpty()) item.projectName + " · " else "") + dateTime(item.lastModified),
      Icons.question) {
      workspace.service.resume(item.id, item.title, item.projectPath, item.agent)
    }
  }
  MenuPopup(entries, t("sidebar.history")) { workspace.sessions = null }
}

@Composable
private fun ProjectGroup(workspace: Workspace, project: ChatMessage.ProjectItem, sorted: List<ThreadState>,
  runIds: Set<String>, narrow: Boolean) {
  val benches = projectBenches(workspace, project)
  val threads = projectThreads(workspace, project, sorted, runIds)
  val collapsed = workspace.isCollapsed(project)
  val active = workspace.active
  val hasActive = active != null && !active.archived && samePath(workspace.projectOf(active).path, project.path)
  val (interaction, hovered) = rememberHover()
  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Tip(project.path) {
      Row(Modifier.fillMaxWidth().height(28.dp).hoverClick(alpha = 0.6f, interaction = interaction) {
        workspace.collapsedProjects[project.path] = !collapsed
        workspace.service.collapseProject(project.path, !collapsed)
      }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (hovered || (collapsed && hasActive)) colors.fg else colors.muted
        Chevron(!collapsed, tint = color)
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
          ProjectIcon(project, tint = color)
        }
        Text(project.name, Modifier.weight(1f), color = color, style = smallStyle.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hovered) {
          IconBtn(Icons.add, t("sidebar.project.new.thread"), iconSize = 14.dp) {
            workspace.service.newThread(project.path, null)
          }
          if (!project.primary) {
            IconBtn(Icons.remove, t("sidebar.project.remove"), iconSize = 14.dp) {
              workspace.service.removeProject(project.path)
            }
          }
        } else if (threads.size + benches.size > 0) {
          Text((threads.size + benches.size).toString(), color = colors.faint,
            style = smallStyle.copy(fontWeight = FontWeight.Medium))
        }
      }
    }
    if (!collapsed) {
      for (bench in benches) {
        BenchGroup(workspace, bench, narrow)
      }
      for (node in threads) {
        ThreadBranch(workspace, node, narrow)
      }
    }
  }
}

/** The thread's row and, unfolded, its sub threads hanging off a line under its status icon. */
@Composable
private fun ThreadBranch(workspace: Workspace, node: ThreadNode<ThreadState>, narrow: Boolean) {
  if (node.children.isEmpty()) {
    ThreadRow(workspace, node.thread, narrow)
    return
  }
  ThreadRow(workspace, node.thread, narrow) { FoldPill(workspace, node) }
  if (isExpanded(workspace, node)) {
    TreeChildren(workspace, node.children, narrow, 10.dp)
  }
}

private fun isExpanded(workspace: Workspace, node: ThreadNode<ThreadState>): Boolean =
  workspace.expandedThreads[node.thread.id] == true

private fun toggle(workspace: Workspace, node: ThreadNode<ThreadState>) {
  workspace.expandedThreads[node.thread.id] = !isExpanded(workspace, node)
}

/** The fold's color: a warning when a sub thread waits for input, else bright when it hides the active thread. */
@Composable
private fun foldColor(workspace: Workspace, node: ThreadNode<ThreadState>, hovered: Boolean): Color = when {
  node.children.any { child -> child.any { it.approvals.isNotEmpty() } } -> colors.warn
  hovered || (!isExpanded(workspace, node) && node.children.any { child -> child.any { it === workspace.active } }) ->
    colors.fg
  else -> colors.muted
}

/** Sub threads as compact rows hanging off a line under their parent's status icon, which sits at [start] + 7dp. */
@Composable
private fun TreeChildren(workspace: Workspace, children: List<ThreadNode<ThreadState>>, narrow: Boolean, start: Dp) {
  val line = colors.faint.copy(alpha = 0.45f)
  Column(Modifier.padding(start = start)) {
    for ((index, child) in children.withIndex()) {
      val last = index == children.lastIndex
      Column(Modifier.drawBehind {
        val x = 7.dp.toPx()
        val y = 14.dp.toPx()
        this.drawLine(line, Offset(x, 0f), Offset(x, if (last) y else this.size.height), 1.dp.toPx())
        this.drawLine(line, Offset(x, y), Offset(12.dp.toPx(), y), 1.dp.toPx())
      }.padding(start = 14.dp)) {
        SubThreadRow(workspace, child.thread, narrow) {
          if (child.children.isNotEmpty()) {
            FoldPill(workspace, child)
          }
        }
        if (child.children.isNotEmpty() && isExpanded(workspace, child)) {
          TreeChildren(workspace, child.children, narrow, 6.dp)
        }
      }
    }
  }
}

/** A chevron and the number of sub threads, which folds them. */
@Composable
private fun FoldPill(workspace: Workspace, node: ThreadNode<ThreadState>) {
  val (interaction, hovered) = rememberHover()
  val color = foldColor(workspace, node, hovered)
  Tip(t("sidebar.sub.threads", node.children.size)) {
    Row(Modifier.height(18.dp).hoverClick(RoundedCornerShape(6.dp), interaction = interaction) {
      toggle(workspace, node)
    }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      Chevron(isExpanded(workspace, node), size = 10.dp, tint = color)
      Text(node.children.size.toString(), color = color, style = tinyStyle.copy(fontWeight = FontWeight.Medium))
    }
  }
}

/** A sub thread on one line: its status, title and age, with the thread row's actions on hover. */
@Composable
private fun SubThreadRow(workspace: Workspace, thread: ThreadState, narrow: Boolean, extra: @Composable () -> Unit) {
  val status = rememberStatus(workspace, thread)
  val updated by remember(thread) {
    derivedStateOf(structuralEqualityPolicy()) { ago(thread.updatedAt, workspace.now) }
  }
  val isActive = thread === workspace.active
  val (interaction, hovered) = rememberHover()
  Box(Modifier.fillMaxWidth().hoverClick(RoundedCornerShape(8.dp), 0.7f, isActive, interaction = interaction) {
    workspace.open(thread)
    if (narrow) {
      workspace.setSidebar(false)
    }
  }) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        StatusIcon(status.kind, thread, statusColor(status.kind))
      }
      val emphasized = isActive || thread.unread
      Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tip(thread.title + "\n" + status.label, Modifier.weight(1f, fill = false)) {
          Text(thread.title, color = if (emphasized) colors.fg else colors.body, style = smallStyle,
            fontWeight = if (emphasized) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        extra()
      }
      Text(updated, Modifier.alpha(if (hovered) 0f else 1f), color = colors.faint, style = tinyStyle)
    }
    if (hovered) {
      RowActions(workspace, thread, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
    }
  }
}

@Composable
private fun BenchGroup(workspace: Workspace, bench: ChatMessage.BenchItem, narrow: Boolean) {
  val runs = bench.threads.mapNotNull { workspace.threads[it] }.filter { !it.archived }
  val collapsed = workspace.collapsedBenches[bench.id] == true
  val (interaction, hovered) = rememberHover()
  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Tip(t("sidebar.bench.title", bench.title, bench.baseCommit)) {
      Row(Modifier.fillMaxWidth().height(26.dp).hoverClick(alpha = 0.6f, interaction = interaction) {
        workspace.collapsedBenches[bench.id] = !collapsed
      }.padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (hovered || (collapsed && runs.any { it === workspace.active })) colors.fg else colors.muted
        Chevron(!collapsed, tint = color)
        Ico(Icons.workflow, size = 14.dp, tint = color)
        Text(bench.title, Modifier.weight(1f), color = color, style = smallStyle.copy(fontWeight = FontWeight.Medium),
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hovered) {
          IconBtn(Icons.usage, t("sidebar.bench.compare"), iconSize = 14.dp) { workspace.openBench(bench.id) }
          IconBtn(Icons.closeSmall, t("sidebar.bench.discard"), iconSize = 14.dp) { confirmDiscard(workspace, bench) }
        } else {
          Text(runs.size.toString(), color = colors.faint, style = smallStyle.copy(fontWeight = FontWeight.Medium))
        }
      }
    }
    if (!collapsed) {
      Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (run in runs) {
          ThreadRow(workspace, run, narrow)
        }
      }
    }
  }
}

fun confirmDiscard(workspace: Workspace, bench: ChatMessage.BenchItem) {
  if (MessageDialogBuilder.yesNo(t("bench.discard"), t("bench.discard.confirm", bench.title)).guessWindowAndAsk()) {
    workspace.service.discardBench(bench.id)
    workspace.shownBench = null
  }
}

@Composable
private fun ThreadRow(workspace: Workspace, thread: ThreadState, narrow: Boolean,
  extra: (@Composable () -> Unit)? = null) {
  val status = rememberStatus(workspace, thread)
  val updated by remember(thread) {
    derivedStateOf(structuralEqualityPolicy()) { ago(thread.updatedAt, workspace.now) }
  }
  val isActive = thread === workspace.active
  val run = workspace.runOf(thread)
  val title = if (run != null) thread.runName(run.second) else thread.title
  val (interaction, hovered) = rememberHover()
  val otherAgent = if (run == null && workspace.agents.isNotEmpty() && thread.agent != workspace.agents[0].id) {
    " · " + thread.agentName
  } else {
    ""
  }
  val sub = (if (thread.archived) t("sidebar.archived") else status.label) + otherAgent +
    (if (run != null && thread.firstTurnMillis > 0) " · " + duration(thread.firstTurnMillis) else "") +
    (if (thread.costUsd > 0) " · " + money(thread.costUsd) else "")
  val statusColor = statusColor(status.kind)
  val subColor = when (status.kind) {
    StatusKind.WORKING -> colors.fg
    StatusKind.INPUT -> colors.warn
    StatusKind.ERROR -> colors.del
    else -> colors.muted
  }
  var menuOpen by remember(thread) { mutableStateOf(false) }
  var canTitle by remember(thread) { mutableStateOf(false) }
  LaunchedEffect(menuOpen) {
    if (menuOpen) {
      canTitle = false
      val shown = thread.turns.isNotEmpty()
      canTitle = withContext(Dispatchers.IO) { workspace.service.canRegenerateTitle(thread.id, shown) }
    }
  }
  Box(Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Press) {
    if (it.buttons.isSecondaryPressed) {
      menuOpen = true
    }
  }.hoverClick(RoundedCornerShape(10.dp), 0.7f, isActive, interaction = interaction) {
    if (thread.archived) {
      workspace.service.setArchived(thread.id, false)
    } else {
      workspace.open(thread)
      if (narrow) {
        workspace.setSidebar(false)
      }
    }
  }.alpha(if (thread.archived) 0.75f else 1f)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.width(18.dp).padding(top = 3.dp), contentAlignment = Alignment.Center) {
        StatusIcon(status.kind, thread, statusColor)
      }
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val emphasized = isActive || thread.unread
          Tip(title, Modifier.weight(1f)) {
            Text(title, color = if (emphasized) colors.fg else colors.body,
              fontWeight = if (emphasized) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          Text(updated, Modifier.alpha(if (hovered) 0f else 1f), color = colors.faint,
            style = tinyStyle)
        }
        if (extra == null) {
          Text(sub, color = subColor, style = tinyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sub, Modifier.weight(1f, fill = false), color = subColor, style = tinyStyle, maxLines = 1,
              overflow = TextOverflow.Ellipsis)
            extra()
          }
        }
        GitLine(thread, isActive)
      }
    }
    if (hovered) {
      RowActions(workspace, thread, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
    }
    if (menuOpen) {
      MenuPopup(listOf(MenuEntry(t("sidebar.regenerate.title"),
        if (thread.titling) t("sidebar.regenerate.title.running") else null, Icons.refresh,
        enabled = canTitle && !thread.titling) { workspace.regenerateTitle(thread) })) { menuOpen = false }
    }
  }
}

/** Archive or restore, and close, shown over a row's end while the mouse is on it. */
@Composable
private fun RowActions(workspace: Workspace, thread: ThreadState, modifier: Modifier) {
  Row(modifier.clip(RoundedCornerShape(8.dp)).background(colors.hover),
    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    if (thread.archived) {
      IconBtn(Icons.restore, t("sidebar.restore"), iconSize = 14.dp) {
        workspace.service.setArchived(thread.id, false)
      }
    } else {
      IconBtn(Icons.archive, t("sidebar.archive", thread.agentName), iconSize = 14.dp) {
        workspace.archive(thread)
      }
    }
    IconBtn(Icons.closeSmall, t("sidebar.close"), iconSize = 14.dp) {
      val isWorking = thread.busy || workspace.sideChatsOf(thread).any { it.busy }
      val confirmed = !isWorking || MessageDialogBuilder.yesNo(t("sidebar.close"),
        t("sidebar.close.confirm", thread.agentName)).guessWindowAndAsk()
      if (confirmed) {
        workspace.service.close(thread.id)
      }
    }
  }
}

@Composable
private fun statusColor(kind: StatusKind): Color = when (kind) {
  StatusKind.WORKING -> colors.primary
  StatusKind.INPUT -> colors.warn
  StatusKind.DONE -> colors.add
  StatusKind.ERROR -> colors.del
  StatusKind.IDLE -> colors.faint
}

@Composable
private fun StatusIcon(kind: StatusKind, thread: ThreadState, color: Color) {
  when (kind) {
    StatusKind.WORKING -> Spinner(14.dp)
    StatusKind.INPUT -> Ico(if (thread.approvals.firstOrNull()?.prompt?.kind == PermissionPrompt.Kind.QUESTION) {
      Icons.question
    } else {
      Icons.warning
    }, size = 14.dp, tint = color)
    StatusKind.ERROR -> Ico(Icons.error, size = 14.dp, tint = color)
    StatusKind.DONE -> if (thread.unread) Dot(color, 8.dp) else Ico(Icons.success, size = 14.dp, tint = color)
    StatusKind.IDLE -> Dot(color.copy(alpha = 0.6f), 6.dp)
  }
}

@Composable
private fun GitLine(thread: ThreadState, isActive: Boolean) {
  val worktree = thread.worktree
  val branch = thread.branch
  if (worktree == null && branch == null) {
    return
  }
  val pr = thread.pullRequest
  val tip = listOfNotNull(worktree?.let { t("header.worktree", it) }, branch?.let { t("header.branch", it) },
    pr?.let { it.number + " " + it.title }).joinToString("\n")
  Tip(tip) {
    Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val color = if (isActive) colors.muted else colors.faint
      if (worktree != null) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp)) {
          Ico(Icons.worktree, size = 12.dp, tint = colors.link)
          Text(worktree, color = colors.link, style = monoTinyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      if (branch != null) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp)) {
          Ico(Icons.branch, size = 12.dp, tint = color)
          Text(branch, color = color, style = monoTinyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      if (pr != null) {
        Text(pr.number, color = prColor(pr.state), style = tinyStyle.copy(fontWeight = FontWeight.Medium))
      }
    }
  }
}

@Composable
fun prColor(state: String): Color = when (state) {
  "open" -> colors.add
  "merged" -> colors.merged
  "closed" -> colors.del
  else -> colors.muted
}
