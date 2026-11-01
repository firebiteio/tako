package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.chat.ChatService
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.samePath
import io.firebite.tako.ui.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SIDEBAR_KEY = "tako.sidebar"
private const val ARCHIVED_TOAST_MILLIS = 5000L

/** What the chat knows beyond single threads, and what the IDE tells it. Touched on the EDT only. */
class Workspace(val service: ChatService, val project: Project) {
  /** Runs the IDE lookups whose answers outlive the view that asked; cancelled with the chat. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
  var projectRoot by mutableStateOf("")
  var projectName by mutableStateOf("")
  var projects by mutableStateOf<List<ChatMessage.ProjectItem>>(emptyList())
  /** Folders the person folded in this session, ahead of what the IDE stored. */
  val collapsedProjects = mutableStateMapOf<String, Boolean>()
  var agents by mutableStateOf<List<ChatMessage.AgentOption>>(emptyList())
  val threads = mutableStateMapOf<String, ThreadState>()
  var active by mutableStateOf<ThreadState?>(null)
  /** How much of the agent's rate-limit windows is used, e.g. "5h 42% · 7d 10%". */
  var limits by mutableStateOf("")
  /** Whether the settings allow parallel runs. */
  var parallelRuns by mutableStateOf(false)
  var benches by mutableStateOf<List<ChatMessage.BenchItem>>(emptyList())
  /** Benches the person folded; only this session remembers. */
  val collapsedBenches = mutableStateMapOf<String, Boolean>()
  /** Threads whose sub threads the list shows; folded by default, only this session remembers. */
  val expandedThreads = mutableStateMapOf<String, Boolean>()
  /** Each agent's models, as its threads reported them, for picking a run's model before it starts. */
  val agentModels = HashMap<String, List<AgentInfo.Model>>()
  val alerts = mutableStateListOf<Alert>()

  var sidebarOpen by mutableStateOf(PropertiesComponent.getInstance().getBoolean(SIDEBAR_KEY, true))
  var showArchived by mutableStateOf(false)
  /** The thread last archived, while the toast offering to undo it shows. */
  var archivedToast by mutableStateOf<ChatService.Archived?>(null)
  /** Whether the thread list shows the search field. */
  var searching by mutableStateOf(false)
  val searchInput = TextFieldState()
  /** The threads whose sessions the IDE found mention a query, with that query. */
  var searchHits by mutableStateOf<Pair<String, Set<String>>>("" to emptySet())
  /** The sessions to resume, while their menu is open. */
  var sessions by mutableStateOf<List<ChatMessage.SessionItem>?>(null)
  var usageOpen by mutableStateOf(false)
  /** Null while the IDE reads the transcripts. */
  var usage by mutableStateOf<ChatMessage.Usage?>(null)
  var shownBench by mutableStateOf<String?>(null)
  /** Bumped to read the shown bench's worktrees again. */
  var benchLoads by mutableIntStateOf(0)
  var taskPanel by mutableStateOf<TaskCard?>(null)
  var contextPanelOpen by mutableStateOf(false)
  /** The run whose agent and model are being picked. */
  var runMenu by mutableStateOf<Int?>(null)
  var lightbox by mutableStateOf<io.firebite.tako.agent.ImageAttachment?>(null)
  /** Bumped to focus the composer. */
  var focusComposer by mutableIntStateOf(0)
  /** The clock the elapsed times read; ticks every second. */
  var now by mutableLongStateOf(System.currentTimeMillis())

  fun setSidebar(open: Boolean) {
    this.sidebarOpen = open
    PropertiesComponent.getInstance().setValue(SIDEBAR_KEY, open, true)
  }

  fun agentName(id: String): String? = this.agents.firstOrNull { it.id == id }?.name

  fun projectList(): List<ChatMessage.ProjectItem> {
    if (this.projects.isNotEmpty()) {
      return this.projects
    }
    return listOf(ChatMessage.ProjectItem(this.projectRoot, this.projectName.ifEmpty { t("common.project") }, false,
      true, null, null))
  }

  fun isCollapsed(project: ChatMessage.ProjectItem): Boolean = this.collapsedProjects[project.path] ?: project.collapsed

  fun projectOf(thread: ThreadState): ChatMessage.ProjectItem = this.projectAt(thread.projectPath)

  /** The project folder at the path, or the first one when the path is in none of them. */
  fun projectAt(path: String?): ChatMessage.ProjectItem {
    val projects = this.projectList()
    val wanted = path ?: this.projectRoot
    return projects.firstOrNull { samePath(it.path, wanted) } ?: projects[0]
  }

  /**
   * Shows the thread, keeping the one it replaces as it was: its draft, alerts and scroll position. Unfolds the
   * threads it is nested under, so the list shows it.
   */
  fun activate(thread: ThreadState?) {
    if (thread == null || this.active === thread) {
      return
    }
    this.taskPanel = null
    this.contextPanelOpen = false
    this.active = thread
    thread.unread = false
    this.focusComposer++
    var parent = thread.parentId?.let { this.threads[it] }
    while (parent != null) {
      this.expandedThreads[parent.id] = true
      parent = parent.parentId?.let { this.threads[it] }
    }
  }

  /** Shows the thread and tells the IDE, which starts its agent if needed. */
  fun open(thread: ThreadState) {
    this.activate(thread)
    this.service.select(thread.id)
  }

  /**
   * Archives the thread and offers to undo it for a few seconds, replacing the offer before. Not while its agent works,
   * as archiving stops it and undoing would not bring that work back.
   */
  fun archive(thread: ThreadState) {
    val isWorking = thread.busy || thread.runningTasks().isNotEmpty()
    val archived = this.service.archive(thread.id)
    val toast = if (isWorking) null else archived
    this.archivedToast = toast
    if (toast != null) {
      this.scope.launch {
        delay(ARCHIVED_TOAST_MILLIS)
        if (this@Workspace.archivedToast === toast) {
          this@Workspace.archivedToast = null
        }
      }
    }
  }

  /**
   * Lists the thread last archived again. Shows it if the thread shown in its place still is, and closes that one if it
   * was made for it and never used.
   */
  fun undoArchive() {
    val archived = this.archivedToast ?: return
    this.archivedToast = null
    this.service.restore(archived.thread)
    val replacement = archived.replacement?.let { this.threads[it] } ?: return
    if (this.active === replacement) {
      this.threads[archived.thread]?.let { this.open(it) }
    }
    val isUnused = replacement.turns.isEmpty() && !replacement.busy && replacement.input.text.isEmpty()
    if (archived.isNewReplacement && isUnused) {
      this.service.close(replacement.id)
    }
  }

  /** Opens the menu of sessions to resume once the IDE found them. */
  fun showHistory() {
    this.scope.launch {
      this@Workspace.sessions = withContext(Dispatchers.IO) { this@Workspace.service.history() }
    }
  }

  fun closeSearch() {
    this.searching = false
    this.searchInput.clearText()
    this.searchHits = "" to emptySet()
  }

  /** Asks the IDE which threads' sessions mention the query. */
  suspend fun searchSessions(query: String) {
    val hits = withContext(Dispatchers.IO) { this@Workspace.service.search(query) }
    this.searchHits = query to hits
  }

  /** Opens the usage view and reads the transcripts again. */
  fun showUsage() {
    this.usage = null
    this.usageOpen = true
    this.scope.launch { this@Workspace.usage = withContext(Dispatchers.IO) { this@Workspace.service.usage() } }
  }

  fun newThreadHere() {
    this.service.newThread(this.active?.projectPath, null)
  }

  /** Has the thread's agent write a title from the conversation the chat holds. */
  fun regenerateTitle(thread: ThreadState) {
    thread.titling = true
    this.service.regenerateTitle(thread.id, thread.conversation()).thenRun { thread.titling = false }
  }

  fun receive(message: ChatMessage.WorkspaceMessage) {
    when (message) {
      is ChatMessage.Project -> {
        this.projectRoot = message.projectRoot ?: ""
        this.projectName = message.projectName
      }
      is ChatMessage.Agents -> this.agents = message.items
      is ChatMessage.Projects -> this.projects = message.items
      is ChatMessage.Threads -> for (item in message.items) {
        this.threads.getOrPut(item.id) { ThreadState(this, item) }
      }
      is ChatMessage.ThreadAdded -> this.threads.getOrPut(message.item.id) { ThreadState(this, message.item) }
      is ChatMessage.ThreadUpdated -> this.threads.getOrPut(message.item.id) { ThreadState(this, message.item) }
        .update(message.item)
      is ChatMessage.ThreadRemoved -> this.removeThread(message.thread)
      is ChatMessage.Select -> this.activate(this.threads[message.thread])
      is ChatMessage.Settings -> this.parallelRuns = message.parallelRuns
      is ChatMessage.Benches -> {
        this.benches = message.items
        if (this.shownBench != null && this.benches.none { it.id == this.shownBench }) {
          this.shownBench = null
        }
      }
      is ChatMessage.Error -> this.alerts.add(Alert(message.text))
    }
  }

  fun receive(message: ChatMessage.ThreadMessage, threadId: String) {
    val thread = this.threads[threadId] ?: return
    when (message) {
      is ChatMessage.Init -> thread.onInit(message.info, message.projectRoot)
      is ChatMessage.Event -> thread.handleEvent(message.event)
      is ChatMessage.Replay -> thread.replay(message.events)
      is ChatMessage.Permission -> thread.addPermission(message.requestId, message.prompt)
      is ChatMessage.Context -> thread.addContext(message.item, message.prefill)
      is ChatMessage.Image -> thread.addImage(message.image)
      is ChatMessage.ContextUsage -> thread.contextUsage = message.data
      is ChatMessage.Exit -> thread.onExit(message.code, message.stderr)
      is ChatMessage.Submit -> thread.startPrompt(message.text, message.context, message.images)
      is ChatMessage.BenchFailed -> thread.onBenchFailed(message.prompt, message.error)
      is ChatMessage.Error -> thread.onError(message.text)
      is ChatMessage.Notice -> thread.showAlert(message.text)
      is ChatMessage.Rewound -> thread.onRewound(message.rewound, message.error)
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
  fun runOf(thread: ThreadState): Pair<ChatMessage.BenchItem, Int>? {
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
    this.benchLoads++
  }
}
