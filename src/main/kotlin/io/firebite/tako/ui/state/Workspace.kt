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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.chat.ChatService
import io.firebite.tako.chat.git.ThreadGit
import io.firebite.tako.ide.project.GitCommands
import io.firebite.tako.ui.bridge.message.ChatError
import io.firebite.tako.ui.bridge.message.ThreadMessage
import io.firebite.tako.ui.bridge.message.WorkspaceMessage
import io.firebite.tako.ui.bridge.message.thread.BenchFailed
import io.firebite.tako.ui.bridge.message.thread.ChatContextUsage
import io.firebite.tako.ui.bridge.message.thread.ChatImage
import io.firebite.tako.ui.bridge.message.thread.Context
import io.firebite.tako.ui.bridge.message.thread.Event
import io.firebite.tako.ui.bridge.message.thread.Exit
import io.firebite.tako.ui.bridge.message.thread.Init
import io.firebite.tako.ui.bridge.message.thread.Notice
import io.firebite.tako.ui.bridge.message.thread.Permission
import io.firebite.tako.ui.bridge.message.thread.Queue
import io.firebite.tako.ui.bridge.message.thread.QueueStashed
import io.firebite.tako.ui.bridge.message.thread.Replay
import io.firebite.tako.ui.bridge.message.thread.Rewound
import io.firebite.tako.ui.bridge.message.thread.Setup
import io.firebite.tako.ui.bridge.message.thread.Submit
import io.firebite.tako.ui.bridge.message.usage.Usage
import io.firebite.tako.ui.bridge.message.workspace.AgentOption
import io.firebite.tako.ui.bridge.message.workspace.Agents
import io.firebite.tako.ui.bridge.message.workspace.BenchItem
import io.firebite.tako.ui.bridge.message.workspace.ChatBenches
import io.firebite.tako.ui.bridge.message.workspace.ChatProject
import io.firebite.tako.ui.bridge.message.workspace.ProjectItem
import io.firebite.tako.ui.bridge.message.workspace.Projects
import io.firebite.tako.ui.bridge.message.workspace.Select
import io.firebite.tako.ui.bridge.message.workspace.SessionItem
import io.firebite.tako.ui.bridge.message.workspace.Settings
import io.firebite.tako.ui.bridge.message.workspace.ShowSideChat
import io.firebite.tako.ui.bridge.message.workspace.ThreadAdded
import io.firebite.tako.ui.bridge.message.workspace.ThreadRemoved
import io.firebite.tako.ui.bridge.message.workspace.ThreadUpdated
import io.firebite.tako.ui.bridge.message.workspace.Threads
import io.firebite.tako.ui.samePath
import io.firebite.tako.ui.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

private const val SIDEBAR_KEY = "tako.sidebar"
private const val ARCHIVED_TOAST_MILLIS = 5000L

/** What the chat knows beyond single threads, and what the IDE tells it. Touched on the EDT only. */
class Workspace(val service: ChatService, val project: Project) {
  /** Runs the IDE lookups whose answers outlive the view that asked; cancelled with the chat. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
  var projectRoot by mutableStateOf("")
  var projectName by mutableStateOf("")
  var projects by mutableStateOf<List<ProjectItem>>(emptyList())
  /** Folders the person folded in this session, ahead of what the IDE stored. */
  val collapsedProjects = mutableStateMapOf<String, Boolean>()
  var agents by mutableStateOf<List<AgentOption>>(emptyList())
  val threads = mutableStateMapOf<String, ThreadState>()
  var active by mutableStateOf<ThreadState?>(null)
  /** How much of the agent's rate-limit windows is used, e.g. "5h 42% · 7d 10%". */
  var limits by mutableStateOf("")
  /** Whether the settings allow parallel runs. */
  var parallelRuns by mutableStateOf(false)
  /** A prompt sent while the agent works steers its turn by default, instead of queuing. */
  var steers by mutableStateOf(false)
  var benches by mutableStateOf<List<BenchItem>>(emptyList())
  /** Benches the person folded; only this session remembers. */
  val collapsedBenches = mutableStateMapOf<String, Boolean>()
  /** Threads whose sub threads the list shows; folded by default, only this session remembers. */
  val expandedThreads = mutableStateMapOf<String, Boolean>()
  /** Each agent's models, as its threads reported them, for picking a run's model before it starts. */
  val agentModels = HashMap<String, List<AgentInfo.Model>>()
  val alerts = mutableStateListOf<Alert>()

  var sidebarOpen by mutableStateOf(PropertiesComponent.getInstance().getBoolean(SIDEBAR_KEY, true))
  var showArchived by mutableStateOf(false)
  var showSnoozed by mutableStateOf(false)
  /** The thread last archived, while the toast offering to undo it shows. */
  var archivedToast by mutableStateOf<ChatService.Archived?>(null)
  /** Whether the thread list shows the search field. */
  var searching by mutableStateOf(false)
  val searchInput = TextFieldState()
  /** The threads whose sessions the IDE found mention a query, with that query. */
  var searchHits by mutableStateOf<Pair<String, Set<String>>>("" to emptySet())
  /** The sessions to resume, while their menu is open. */
  var sessions by mutableStateOf<List<SessionItem>?>(null)
  var usageOpen by mutableStateOf(false)
  /** Null while the IDE reads the transcripts. */
  var usage by mutableStateOf<Usage?>(null)
  var shownBench by mutableStateOf<String?>(null)
  /** Bumped to read the shown bench's worktrees again. */
  var benchLoads by mutableIntStateOf(0)
  var taskPanel by mutableStateOf<TaskCard?>(null)
  var contextPanelOpen by mutableStateOf(false)
  /** Whether the side chat pane covers the main thread, as the chat is too narrow for both. */
  var sidePaneCovers: Boolean = false
    set(value) {
      field = value
      this.service.setSidePaneCovering(value)
    }
  /** The thread whose composer the context panel opened from. */
  var contextPanelFor by mutableStateOf<String?>(null)
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

  /** The agent of that id, or the default one when the id is unknown, as the IDE picks it. */
  fun agentOf(id: String): AgentOption? = this.agents.firstOrNull { it.id == id } ?: this.agents.firstOrNull()

  fun projectList(): List<ProjectItem> {
    if (this.projects.isNotEmpty()) {
      return this.projects
    }
    return listOf(ProjectItem(this.projectRoot, this.projectName.ifEmpty { t("common.project") }, false,
      true, null, null))
  }

  fun isCollapsed(project: ProjectItem): Boolean = this.collapsedProjects[project.path] ?: project.collapsed

  fun projectOf(thread: ThreadState): ProjectItem = this.projectAt(thread.projectPath)

  /** The project folder at the path, or the first one when the path is in none of them. */
  fun projectAt(path: String?): ProjectItem {
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
    val main = this.mainOf(thread)
    if (main !== thread) {
      this.activate(main)
      this.showSideChat(thread.id, false)
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
    val isWorking = (listOf(thread) + this.sideChatsOf(thread)).any { it.busy || it.runningTasks().isNotEmpty() }
    thread.sideChatShown = null
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
      this@Workspace.sessions = withContext(Dispatchers.IO) { this@Workspace.service.sessions().history() }
    }
  }

  fun closeSearch() {
    this.searching = false
    this.searchInput.clearText()
    this.searchHits = "" to emptySet()
  }

  /** Asks the IDE which threads' sessions mention the query. */
  suspend fun searchSessions(query: String) {
    val hits = withContext(Dispatchers.IO) { this@Workspace.service.sessions().search(query) }
    this.searchHits = query to hits
  }

  /** Opens the usage view and reads the transcripts again. */
  fun showUsage() {
    this.usage = null
    this.usageOpen = true
    this.scope.launch { this@Workspace.usage = withContext(Dispatchers.IO) { this@Workspace.service.sessions().usage() } }
  }

  fun newThreadHere() {
    this.service.newThread(this.active?.projectPath, null)
  }

  /** Asks for the thread's new title once the current event is handled, as the menu that asked is still closing. */
  fun rename(thread: ThreadState) {
    ApplicationManager.getApplication().invokeLater {
      val title = Messages.showInputDialog(this.project, t("sidebar.rename.message"), t("sidebar.rename.title"), null,
        thread.title, NotBlank)
      if (title != null) {
        this.service.rename(thread.id, title)
      }
    }
  }

  fun snooze(thread: ThreadState, preset: SnoozePreset) {
    this.service.snooze(thread.id, snoozeUntil(preset, ZonedDateTime.now()).toInstant().toEpochMilli())
  }

  /** Has the thread's agent write a title from the conversation the chat holds. */
  fun regenerateTitle(thread: ThreadState) {
    thread.titling = true
    this.service.titles().regenerate(thread.id, thread.conversation()).thenRun { thread.titling = false }
  }

  /** Runs a git action from the thread's header. The thread counts as running one until it ended or was cancelled. */
  fun runGitAction(thread: ThreadState, option: ThreadGit.Option, status: GitCommands.Status) {
    val controller = thread.controller ?: return
    thread.gitRunning = true
    try {
      controller.runGitAction(option, status).whenComplete { _, _ -> thread.gitRunning = false }
    } catch (e: RuntimeException) {
      thread.gitRunning = false
      throw e
    }
  }

  fun receive(message: WorkspaceMessage) {
    when (message) {
      is ChatProject -> {
        this.projectRoot = message.projectRoot ?: ""
        this.projectName = message.projectName
      }
      is Agents -> this.agents = message.items
      is Projects -> this.projects = message.items
      is Threads -> for (item in message.items) {
        this.threads.getOrPut(item.id) { ThreadState(this, item) }
      }
      is ThreadAdded -> this.threads.getOrPut(message.item.id) { ThreadState(this, message.item) }
      is ThreadUpdated -> this.threads.getOrPut(message.item.id) { ThreadState(this, message.item) }
        .update(message.item)
      is ThreadRemoved -> this.removeThread(message.thread)
      is Select -> this.activate(this.threads[message.thread])
      is ShowSideChat -> this.showSideChat(message.thread, message.started)
      is Settings -> {
        this.parallelRuns = message.parallelRuns
        this.steers = message.steers
      }
      is ChatBenches -> {
        this.benches = message.items
        if (this.shownBench != null && this.benches.none { it.id == this.shownBench }) {
          this.shownBench = null
        }
      }
      is ChatError -> this.alerts.add(Alert(message.text))
    }
  }

  fun receive(message: ThreadMessage, threadId: String) {
    val thread = this.threads[threadId] ?: return
    when (message) {
      is Init -> thread.onInit(message.info, message.projectRoot)
      is Event -> thread.handleEvent(message.event)
      is Replay -> thread.replay(message.events)
      is Permission -> thread.addPermission(message.requestId, message.prompt)
      is Context -> thread.addContext(message.item, message.prefill)
      is ChatImage -> thread.addImage(message.image)
      is ChatContextUsage -> thread.contextUsage = message.data
      is Exit -> thread.onExit(message.code, message.stderr)
      is Submit -> thread.startPrompt(message.text, message.context, message.images)
      is Queue -> thread.applyQueue(message)
      is QueueStashed -> thread.onQueueStashed(message.count)
      is BenchFailed -> thread.onBenchFailed(message.prompt, message.error)
      is ChatError -> thread.onError(message.text, message.start)
      is Notice -> thread.showAlert(message.text)
      is Setup -> thread.onSetup(message)
      is Rewound -> thread.onRewound(message.rewound, message.error)
    }
  }

  private fun removeThread(id: String) {
    val thread = this.threads[id] ?: return
    val main = this.mainOf(thread)
    this.threads.remove(id)
    if (main !== thread && main.sideChatShown == id) {
      main.sideChatShown = null
      this.sideChatsOf(main).firstOrNull()?.let { this.showSideChat(it.id, false) }
    }
    if (this.active === thread) {
      this.active = null
    }
    if (this.taskPanel?.thread === thread) {
      this.taskPanel = null
    }
  }

  /** The bench the thread is a run of, with the run's number from 1. */
  fun runOf(thread: ThreadState): Pair<BenchItem, Int>? {
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

/** Keeps the rename dialog's OK disabled while the title is blank. */
private object NotBlank : InputValidator {
  override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()

  override fun canClose(inputString: String?): Boolean = this.checkInput(inputString)
}
