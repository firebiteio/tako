package io.firebite.tako.ui.view

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ide.actions.RevealFileAction
import io.firebite.tako.ide.editor.IdeFiles
import io.firebite.tako.ui.money
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.t
import io.firebite.tako.ui.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import java.nio.file.Path

/**
 * The project and thread names, the thread's cost, the rate-limit windows, and its worktree, branch and pull request.
 * @param windowWidth the whole chat's width, which decides which git chips fit
 */
@Composable
fun Header(workspace: Workspace, windowWidth: Dp) {
  val thread = workspace.active
  Column {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Box {
        val waiting = waitingThreads(workspace)
        IconBtn(Icons.sidebar, if (waiting > 0) t("sidebar.threads.attention", waiting) else t("sidebar.threads"),
          color = if (waiting > 0) colors.warn else colors.muted) {
          workspace.setSidebar(!workspace.sidebarOpen)
        }
        if (waiting > 0) {
          Dot(colors.warn, 6.dp, Modifier.align(Alignment.TopEnd).offset((-5).dp, 5.dp))
        }
        if (workspace.sessions != null && !workspace.sidebarOpen) {
          SessionsMenu(workspace)
        }
      }
      val project = thread?.let { workspace.projectOf(it) }
      BoxWithConstraints(Modifier.weight(1f)) {
        val crumbWidth = this.maxWidth
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Tip(project?.path) {
            Row(Modifier.widthIn(max = crumbWidth * 0.4f), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              if (project?.icon != null) {
                ProjectIcon(project)
              }
              Text(project?.name ?: workspace.projectName, color = colors.muted, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          Text("/", color = colors.faint, fontWeight = FontWeight.Medium)
          Text(thread?.title ?: t("common.new.chat"), Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      if (thread != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          val worktree = thread.worktree
          val branch = thread.branch
          val pr = thread.pullRequest
          if (!workspace.sidebarOpen && windowWidth > 520.dp) {
            if (worktree != null && (windowWidth > 760.dp || (branch == null && pr == null))) {
              GitChip(Icons.worktree, worktree, t("header.worktree", worktree))
            }
            if (branch != null && (windowWidth > 760.dp || worktree != null || pr == null)) {
              GitChip(Icons.branch, branch, t("header.branch", branch))
            }
          }
          if (pr != null) {
            GitChip(if (pr.state == "merged") Icons.merged else Icons.pullRequest, pr.number,
              t("header.pull.request", pr.title, pr.state), prColor(pr.state)) {
              browse(pr.url)
            }
          }
        }
      }
      val meta = listOf(thread?.costUsd?.takeIf { it > 0 }?.let { money(it) } ?: "", workspace.limits)
        .filter { it.isNotEmpty() }.joinToString(" · ")
      if (meta.isNotEmpty()) {
        val tokenCount = thread?.tokens ?: 0
        Tip(if (tokenCount > 0) t("header.meta.tokens", tokens(tokenCount)) else t("header.meta")) {
          Text(meta, color = colors.muted, style = tinyStyle, maxLines = 1)
        }
      }
      thread?.scratchpad?.let { path ->
        Box {
          val scope = rememberCoroutineScope()
          var shown by remember(thread) { mutableStateOf<List<String>?>(null) }
          IconBtn(Icons.scratchpad, t("header.scratchpad.tooltip")) {
            val controller = thread.controller ?: return@IconBtn
            scope.launch { shown = withContext(Dispatchers.IO) { controller.scratchpadFiles() } }
          }
          shown?.let { files ->
            val entries = files.map { file ->
              val slash = file.lastIndexOf('/')
              MenuEntry(file.substring(slash + 1), if (slash < 0) null else file.substring(0, slash), Icons.file) {
                IdeFiles.open(workspace.project, "$path/$file", 1)
              }
            } + MenuEntry(t("header.scratchpad.reveal"), icon = Icons.folderOpen) {
              RevealFileAction.openDirectory(Path.of(path))
            }
            MenuPopup(entries, t("header.scratchpad"), Alignment.End) { shown = null }
          }
        }
      }
      if (thread?.info != null) {
        RestartButton(thread)
      }
      IconBtn(Icons.add, t("common.new.thread")) { workspace.newThreadHere() }
    }
    Rule()
  }
}

/** Stops the agent so the next prompt resumes the session in a new process. Only while idle, for agents that resume. */
@Composable
private fun RestartButton(thread: ThreadState) {
  val canResume = thread.info?.capabilities?.resume == true
  val isIdle = !thread.busy && thread.approvals.isEmpty()
  val tip = when {
    !canResume -> t("header.restart.unsupported", thread.agentName)
    !isIdle -> t("header.restart.busy", thread.agentName)
    thread.stopped -> t("header.restart.stopped", thread.agentName)
    else -> t("header.restart", thread.agentName)
  }
  IconBtn(Icons.refresh, tip, enabled = canResume && isIdle && !thread.stopped) { thread.controller?.restartSession() }
}

@Composable
private fun GitChip(icon: IconKey, text: String, tip: String, color: Color = colors.muted, onClick: (() -> Unit)? = null) {
  Tip(tip) {
    val shape = RoundedCornerShape(50)
    val isPr = onClick != null
    val border = if (isPr) color.copy(alpha = 0.4f) else colors.softBorder
    Row(Modifier.height(22.dp).clip(shape).border(1.dp, border, shape)
      .let { if (onClick != null) it.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else it }
      .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Ico(icon, size = 12.dp, tint = color)
      val style = if (isPr) tinyStyle.copy(fontWeight = FontWeight.Medium) else monoTinyStyle
      Text(text, if (isPr) Modifier else Modifier.widthIn(max = (16 * style.fontSize.value).dp), color = color,
        style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** Headlines for an empty thread, each with and without the project's name. */
private val HEROES: List<Pair<() -> String, (String) -> String>> = listOf(
  { t("header.hero.build") } to { name -> t("header.hero.build.project", name) },
  { t("header.hero.working") } to { name -> t("header.hero.working.project", name) },
  { t("header.hero.start") } to { name -> t("header.hero.start.project", name) },
  { t("header.hero.fix") } to { name -> t("header.hero.fix.project", name) },
  { t("header.hero.next") } to { name -> t("header.hero.next.project", name) },
  { t("header.hero.tackle") } to { name -> t("header.hero.tackle.project", name) },
)

/** A headline picked by the thread's id, so a thread keeps its headline. */
fun heroTitle(threadId: String, name: String): String {
  val (plain, named) = HEROES[Math.floorMod(threadId.hashCode(), HEROES.size)]
  return if (name.isNotEmpty()) named(name) else plain()
}
