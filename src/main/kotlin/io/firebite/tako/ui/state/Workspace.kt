package io.firebite.tako.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.ide.util.PropertiesComponent
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.ui.bridge.PageMessage
import io.firebite.tako.ui.bridge.PageOp
import io.firebite.tako.ui.bridge.PageRequest
import io.firebite.tako.ui.samePath
import io.firebite.tako.ui.t

private const val SIDEBAR_KEY = "tako.sidebar"

/** What the chat knows beyond single threads, and what the IDE tells it. Touched on the EDT only. */
class Workspace(val handler: (PageRequest) -> Unit) {
  var projectRoot by mutableStateOf("")
  var projectName by mutableStateOf("")
  var projects by mutableStateOf<List<PageMessage.ProjectItem>>(emptyList())
  /** Folders the person folded in this session, ahead of what the IDE stored. */
  val collapsedProjects = mutableStateMapOf<String, Boolean>()
  var agents by mutableStateOf<List<PageMessage.AgentOption>>(emptyList())
  val threads = mutableStateMapOf<String, ThreadState>()
  var active by mutableStateOf<ThreadState?>(null)
  var includeFile by mutableStateOf(true)
  /** How much of the agent's rate-limit windows is used, e.g. "5h 42% · 7d 10%". */
  var limits by mutableStateOf("")
  /** Whether the settings allow parallel runs. */
  var parallelRuns by mutableStateOf(false)
  var benches by mutableStateOf<List<PageMessage.BenchItem>>(emptyList())
  /** Benches the person folded; only this session remembers. */
  val collapsedBenches = mutableStateMapOf<String, Boolean>()
  /** Each agent's models, as its threads reported them, for picking a run's model before it starts. */
  val agentModels = HashMap<String, List<AgentInfo.Model>>()
  val alerts = mutableStateListOf<Alert>()

  var sidebarOpen by mutableStateOf(PropertiesComponent.getInstance().getBoolean(SIDEBAR_KEY, true))
  var showArchived by mutableStateOf(false)
  /** The sessions to resume, while their menu is open. */
  var sessions by mutableStateOf<List<PageMessage.SessionItem>?>(null)
  var usageOpen by mutableStateOf(false)
  /** Null while the IDE reads the transcripts. */
  var usage by mutableStateOf<PageMessage.Usage?>(null)
  var shownBench by mutableStateOf<String?>(null)
  /** Null while the IDE reads the worktrees. */
  var benchRuns by mutableStateOf<List<PageMessage.RunChanges>?>(null)
  var taskPanel by mutableStateOf<TaskCard?>(null)
  var contextPanelOpen by mutableStateOf(false)
  /** The run whose agent and model are being picked. */
  var runMenu by mutableStateOf<Int?>(null)
  /** The scratchpad's folder and files, while their menu is open. */
  var scratchpadFiles by mutableStateOf<Pair<String, List<String>>?>(null)
  var lightbox by mutableStateOf<io.firebite.tako.agent.ImageAttachment?>(null)
  /** Bumped to focus the composer. */
  var focusComposer by mutableIntStateOf(0)
  /** The clock the elapsed times read; ticks every second. */
  var now by mutableLongStateOf(System.currentTimeMillis())

  var mentionRequest = 0
  val pendingBenches = HashMap<Int, PendingBench>()
  var lastBenchRequest = 0

  fun send(op: PageOp.WorkspaceOp) {
    this.handler(PageRequest(null, op))
  }

  fun setSidebar(open: Boolean) {
    this.sidebarOpen = open
    PropertiesComponent.getInstance().setValue(SIDEBAR_KEY, open, true)
  }

  fun agentName(id: String): String? = this.agents.firstOrNull { it.id == id }?.name

  fun projectList(): List<PageMessage.ProjectItem> {
    if (this.projects.isNotEmpty()) {
      return this.projects
    }
    return listOf(PageMessage.ProjectItem(this.projectRoot, this.projectName.ifEmpty { t("common.project") }, false,
      true, null, null))
  }

  fun isCollapsed(project: PageMessage.ProjectItem): Boolean = this.collapsedProjects[project.path] ?: project.collapsed

  fun projectOf(thread: ThreadState): PageMessage.ProjectItem = this.projectAt(thread.projectPath)

  /** The project folder at the path, or the first one when the path is in none of them. */
  fun projectAt(path: String?): PageMessage.ProjectItem {
    val projects = this.projectList()
    val wanted = path ?: this.projectRoot
    return projects.firstOrNull { samePath(it.path, wanted) } ?: projects[0]
  }

  /** Shows the thread, keeping the one it replaces as it was: its draft, alerts and scroll position. */
  fun activate(thread: ThreadState?) {
    if (thread == null || this.active === thread) {
      return
    }
    this.taskPanel = null
    this.contextPanelOpen = false
    this.active = thread
    thread.unread = false
    this.focusComposer++
  }

  /** Shows the thread and tells the IDE, which starts its agent if needed. */
  fun open(thread: ThreadState) {
    this.activate(thread)
    this.send(PageOp.Select(thread.id))
  }

  fun newThreadHere() {
    this.send(PageOp.NewThread(this.active?.projectPath, null))
  }

  fun receive(message: PageMessage.WorkspaceMessage) {
    when (message) {
      is PageMessage.Project -> {
        this.projectRoot = message.projectRoot ?: ""
        this.projectName = message.projectName
      }
      is PageMessage.Agents -> this.agents = message.items
      is PageMessage.Projects -> this.projects = message.items
      is PageMessage.Threads -> for (item in message.items) {
        this.threads.getOrPut(item.id) { ThreadState(this, item) }
      }
      is PageMessage.ThreadAdded -> this.threads.getOrPut(message.item.id) { ThreadState(this, message.item) }
      is PageMessage.ThreadUpdated -> this.threads.getOrPut(message.item.id) { ThreadState(this, message.item) }
        .update(message.item)
      is PageMessage.ThreadRemoved -> this.removeThread(message.thread)
      is PageMessage.Select -> this.activate(this.threads[message.thread])
      is PageMessage.Sessions -> this.sessions = message.items
      is PageMessage.Usage -> this.usage = message
      is PageMessage.Settings -> this.parallelRuns = message.parallelRuns
      is PageMessage.Benches -> {
        this.benches = message.items
        if (this.shownBench != null && this.benches.none { it.id == this.shownBench }) {
          this.shownBench = null
        }
      }
      is PageMessage.BenchCreated -> this.onBenchCreated(message.request, message.threads)
      is PageMessage.BenchFailed -> this.onBenchFailed(message.request, message.text)
      is PageMessage.BenchComparison -> if (message.bench == this.shownBench) {
        this.benchRuns = message.runs
      }
      is PageMessage.Error -> this.alerts.add(Alert(message.text))
    }
  }

  fun receive(message: PageMessage.ThreadMessage, threadId: String) {
    val thread = this.threads[threadId] ?: return
    when (message) {
      is PageMessage.Init -> thread.onInit(message.info, message.projectRoot)
      is PageMessage.Event -> thread.handleEvent(message.event)
      is PageMessage.Replay -> thread.replay(message.events)
      is PageMessage.Permission -> thread.addPermission(message.requestId, message.prompt)
      is PageMessage.Context -> thread.addContext(message.item, message.prefill)
      is PageMessage.Image -> thread.addImage(message.image)
      is PageMessage.Files -> thread.mentionReply = message.requestId to message.items
      is PageMessage.Scratchpad -> if (thread.isActive) {
        this.scratchpadFiles = message.path to message.files
      }
      is PageMessage.ContextUsage -> thread.contextUsage = message.data
      is PageMessage.Exit -> thread.onExit(message.code, message.stderr)
      is PageMessage.Submit -> thread.startPrompt(message.text, emptyList(), emptyList(), false)
      is PageMessage.Error -> thread.onError(message.text)
    }
  }

  private fun removeThread(id: String) {
    val thread = this.threads.remove(id) ?: return
    if (this.active === thread) {
      this.active = null
    }
    if (this.taskPanel?.thread === thread) {
      this.taskPanel = null
    }
  }

  /** The bench the thread is a run of, with the run's number from 1. */
  fun runOf(thread: ThreadState): Pair<PageMessage.BenchItem, Int>? {
    for (bench in this.benches) {
      val index = bench.threads.indexOf(thread.id)
      if (index >= 0) {
        return bench to index + 1
      }
    }
    return null
  }

  fun openBench(benchId: String) {
    this.shownBench = benchId
    this.benchRuns = null
    this.send(PageOp.CompareBench(benchId))
  }
}
