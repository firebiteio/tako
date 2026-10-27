package io.firebite.tako.ui.state

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.ContextUsage
import com.intellij.openapi.diagnostic.logger
import io.firebite.tako.chat.store.PromptStash
import io.firebite.tako.chat.thread.ChatController
import io.firebite.tako.chat.thread.ThreadTitles
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ide.project.GitInfo
import io.firebite.tako.ui.bridge.ChatMessage

/** A thread as the chat shows it: the IDE's record of it, its conversation and its composer. */
private val LOG = logger<ThreadState>()

class ThreadState(val workspace: Workspace, item: ChatMessage.ThreadItem) {
  val id: String = item.id
  /** The thread whose agent created this one, which the list nests it under. */
  val parentId: String? = item.parentId
  var title by mutableStateOf(item.title)
  /** Whether the agent writes the thread a new title. */
  var titling by mutableStateOf(false)
  var archived by mutableStateOf(item.archived)
  var updatedAt by mutableLongStateOf(if (item.updatedAt > 0) item.updatedAt else System.currentTimeMillis())
  var costUsd by mutableStateOf(item.costUsd)
  var tokens by mutableLongStateOf(item.tokens)
  var branch by mutableStateOf(item.branch)
  var worktree by mutableStateOf(item.worktree)
  var pullRequest by mutableStateOf<GitInfo.PullRequest?>(item.pullRequest)
  var projectPath by mutableStateOf(item.projectPath)
  /** The id of the agent the thread runs. */
  var agent by mutableStateOf(item.agent)
  var hasSession by mutableStateOf(item.hasSession)
  /** How long the first turn took, 0 until it ended. */
  var firstTurnMillis by mutableLongStateOf(item.firstTurnMillis)
  /** The folder the agent works in, once it started. */
  var projectRoot by mutableStateOf<String?>(null)

  val turns = mutableStateListOf<Turn>()
  /** The chat's number for the last prompt sent. */
  var turnNo = 0
  var turn: Turn? = null
  /** Prompts sent while a turn ran, for agents that take one turn at a time. */
  val queued = mutableStateListOf<QueuedPrompt>()
  val entries = HashMap<String, Entry>()
  val declined = HashSet<String>()
  val live = HashMap<String?, MutableList<LiveText>>()
  val liveByIndex = HashMap<String, LiveText>()
  val thinkingByIndex = HashMap<String, TurnItem.Thinking>()
  var currentMessageId: String? = null
  var replaying = false

  val contexts = mutableStateListOf<ContextItem>()
  val images = mutableStateListOf<PendingImage>()
  val approvals = mutableStateListOf<Approval>()
  var question by mutableStateOf<QuestionState?>(null)
  var busy by mutableStateOf(false)
  var startedAt by mutableLongStateOf(0)
  var contextUsage by mutableStateOf<ContextUsage?>(null)
  var liveContext by mutableStateOf<LiveContext?>(null)
  var cache by mutableStateOf<PromptCache?>(null)
  /** USD per input token, learned from the session's reported cost. */
  var inputRate by mutableStateOf<Double?>(null)
  /** The model's context size, when the agent reports it. */
  var contextWindow by mutableStateOf<Long?>(null)
  val tasks = mutableStateMapOf<String, Task>()
  var finished by mutableStateOf(false)
  var unread by mutableStateOf(false)
  var failed by mutableStateOf(false)
  var model by mutableStateOf<String?>(null)
  var mode by mutableStateOf("default")
  var effort by mutableStateOf<String?>(null)
  /** The runs the next prompt starts, each in its own worktree; fewer than two sends to this thread. */
  val runs = mutableStateListOf<RunPlan>()
  /** Whether a bench started from this thread waits for its worktrees. */
  var startingBench = false
  /** What the agent offers, once it started. */
  var info by mutableStateOf<AgentInfo?>(null)
  /** The agent's process ended or was stopped. The next prompt starts it again. */
  var stopped by mutableStateOf(false)
  var commands by mutableStateOf<List<AgentInfo.Command>>(emptyList())
  /** The folder the agent keeps its scratch files in, if it has one. */
  var scratchpad by mutableStateOf<String?>(null)
  val alerts = mutableStateListOf<Alert>()
  /** The stashed drafts to pick from, while their menu is open. */
  var stashMenu by mutableStateOf<List<PromptStash.Entry>?>(null)

  val input = TextFieldState()
  val scrollState = ScrollState(0)
  /** Whether the timeline keeps following its end, until the person scrolls away. */
  var stick by mutableStateOf(true)
  /** Bumped to scroll the timeline to its end, e.g. after sending. */
  var scrollToEnd by mutableIntStateOf(0)

  fun update(item: ChatMessage.ThreadItem) {
    this.title = item.title.ifEmpty { this.title }
    this.archived = item.archived
    if (item.updatedAt > 0) {
      this.updatedAt = item.updatedAt
    }
    this.costUsd = item.costUsd
    this.tokens = item.tokens
    this.branch = item.branch
    this.worktree = item.worktree
    this.projectPath = item.projectPath
    this.pullRequest = item.pullRequest
    this.hasSession = item.hasSession
    this.firstTurnMillis = item.firstTurnMillis
    if (item.agent.isNotEmpty() && item.agent != this.agent) {
      this.agent = item.agent
      this.info = null
      this.model = null
      this.effort = null
      this.contextWindow = null
    }
  }

  /** The thread's controller in the IDE, while the thread is open there. */
  val controller: ChatController?
    get() = this.workspace.service.controller(this.id) ?: null.also { LOG.warn("No controller for thread " + this.id) }

  val isActive: Boolean
    get() = this.workspace.active === this

  /** The folder paths are shown relative to. */
  val root: String
    get() = this.projectRoot ?: this.projectPath ?: this.workspace.projectRoot

  /** The path relative to the thread's folder, else as given. */
  fun relative(path: String?): String {
    if (path.isNullOrEmpty()) {
      return ""
    }
    val normalized = path.replace('\\', '/')
    val root = this.root.replace('\\', '/')
    if (root.isNotEmpty() && normalized.lowercase().startsWith(root.lowercase() + "/")) {
      return normalized.substring(root.length + 1)
    }
    return normalized
  }

  /** The agent can only change before the thread has a session. */
  val canChangeAgent: Boolean
    get() = this.turns.isEmpty() && !this.hasSession

  val agentName: String
    get() = this.workspace.agentName(this.agent) ?: this.info?.agentName ?: this.agent

  val models: List<AgentInfo.Model>
    get() = this.info?.models ?: emptyList()

  val currentModel: AgentInfo.Model?
    get() = this.models.firstOrNull { it.value == this.model }

  /** Whether a prompt or answer the chat holds contains the query, ignoring case. */
  fun mentions(query: String): Boolean = this.turns.any { turn ->
    turn.prompt?.text?.contains(query, true) == true ||
      turn.items.any {
        it is TurnItem.Text && it.text.text.contains(query, true) ||
          it is TurnItem.FollowUp && it.prompt.text.contains(query, true)
      }
  }

  /** The prompts and answers the chat holds, in order. */
  fun conversation(): List<ThreadTitles.Message> {
    val messages = ArrayList<ThreadTitles.Message>()
    for (turn in this.turns) {
      turn.prompt?.let { messages.add(ThreadTitles.Message(true, it.text)) }
      for (item in turn.items) {
        when (item) {
          is TurnItem.Text -> messages.add(ThreadTitles.Message(false, item.text.text))
          is TurnItem.FollowUp -> messages.add(ThreadTitles.Message(true, item.prompt.text))
          else -> {}
        }
      }
    }
    return messages
  }

  fun showAlert(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    this.alerts.add(Alert(text, actionLabel, action))
  }
}
