package io.firebite.tako.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.ImageAttachment
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.chat.thread.QueuedPrompts
import io.firebite.tako.ide.context.ContextItem

enum class EntryStatus { RUNNING, DONE, ERROR, DECLINED }

/** An assistant text block. It streams in until [done], when its code gets highlighted. */
class AssistantText(text: String, done: Boolean) {
  var text by mutableStateOf(text)
  var done by mutableStateOf(done)
}

/** A text block arriving as stream deltas. */
class LiveText(val row: AssistantText) {
  var done = false
}

/** What a subagent's step list holds: its tool calls and the text it wrote. */
sealed interface Step

class SubText(val text: String) : Step

/** One tool call, or a task's card. */
sealed class Entry(
  val thread: ThreadState,
  val id: String,
  val name: String,
  val input: JsonObject,
  val category: ToolKind,
  /** The agent's own label for the call, when it gives one. */
  val title: String?,
) : Step {
  var status by mutableStateOf(EntryStatus.RUNNING)
  /** A subagent's own calls and texts. */
  val steps = mutableStateListOf<Step>()
}

class ToolEntry(thread: ThreadState, id: String, name: String, input: JsonObject, category: ToolKind, title: String?) :
  Entry(thread, id, name, input, category, title) {
  var isNew by mutableStateOf(false)
  var added by mutableIntStateOf(0)
  var removed by mutableIntStateOf(0)
  var output by mutableStateOf<String?>(null)
  var outputIsError by mutableStateOf(false)
  var open by mutableStateOf(category == ToolKind.TODO)
}

/** What runs as a task, as `TaskUpdated.taskType` names it. */
enum class TaskType { AGENT, SHELL, WORKFLOW }

/** A tool call that runs as a task: a subagent, a workflow or a background shell. */
class TaskCard(
  thread: ThreadState,
  id: String,
  name: String,
  input: JsonObject,
  title: String?,
  val taskType: TaskType,
) : Entry(thread, id, name, input, ToolKind.AGENT, title) {
  var task by mutableStateOf<Task?>(null)
  var resultText by mutableStateOf("")
  var completion: TurnItem.Completion? = null
  var turn: Turn? = null
}

/** A task as its `TaskUpdated` events describe it. */
class Task(val id: String, val startedAt: Long) {
  val agents = mutableStateMapOf<Int, AgentEvent.WorkflowAgent>()
  val logs = mutableStateListOf<String>()
  val seenLogs = HashSet<String>()
  var status by mutableStateOf("running")
  var endedAt by mutableStateOf<Long?>(null)
  var toolUseId by mutableStateOf<String?>(null)
  var type by mutableStateOf<String?>(null)
  var description by mutableStateOf<String?>(null)
  var subagentType by mutableStateOf<String?>(null)
  var prompt by mutableStateOf<String?>(null)
  var backgrounded by mutableStateOf<Boolean?>(null)
  var summary by mutableStateOf<String?>(null)
  var toolUses by mutableStateOf<Int?>(null)
  var totalTokens by mutableStateOf<Long?>(null)
  var lastTool by mutableStateOf<String?>(null)
}

/** Consecutive tool calls of a turn, shown as one collapsible row. */
class WorkGroup(val thread: ThreadState) {
  val entries = mutableStateListOf<ToolEntry>()
  var collapsed by mutableStateOf(true)
}

/** What a turn shows below its prompt, in order. */
sealed interface TurnItem {
  class Text(val text: AssistantText) : TurnItem

  class Thinking : TurnItem

  class Note(val text: String) : TurnItem

  /** Todos and plans stand on their own, outside work groups. */
  class Single(val entry: ToolEntry) : TurnItem

  class Group(val group: WorkGroup) : TurnItem

  class Task(val card: TaskCard) : TurnItem

  /** A task that ended after its turn, said at the end of the conversation. */
  class Completion(val card: TaskCard) : TurnItem

  /** A plan the agent proposes. */
  class Plan(val plan: String) : TurnItem

  /** A prompt sent while the turn ran, which the agent took into it. */
  class FollowUp(val prompt: Prompt) : TurnItem
}

/** The prompt that started a turn. */
class Prompt(val text: String, val contexts: List<ContextItem>, val images: List<ImageAttachment>) {
  val at = System.currentTimeMillis()
}

/** A prompt the agent has not taken yet; [no] is the chat's number for the turn it starts. */
class QueuedPrompt(val no: Int, val prompt: Prompt, status: QueuedPrompts.Status = QueuedPrompts.Status.HELD) {
  var status by mutableStateOf(status)
}

/** A finished turn's work up to its end or a follow-up, folded under "Worked for …". */
class Fold(val label: String, val items: Set<TurnItem>) {
  val first = items.first()
  val last = items.last()
  var open by mutableStateOf(false)
}

class Turn(
  /** The chat's number for the prompt that started the turn; the IDE keeps the turn's changes under it. */
  val no: Int,
  val prompt: Prompt?,
  val replayed: Boolean,
) {
  val items = mutableStateListOf<TurnItem>()
  var group: WorkGroup? = null
  val changes = LinkedHashMap<String, AgentEvent.Change>()
  var changeList by mutableStateOf<List<AgentEvent.Change>>(emptyList())
  val startedAt = System.currentTimeMillis()
  var done by mutableStateOf(false)
  var stopped = false
  var folds by mutableStateOf<List<Fold>>(emptyList())
  var error by mutableStateOf<String?>(null)
}

class Approval(val requestId: String, val prompt: PermissionPrompt)

/** Progress through an `AskUserQuestion` prompt. */
class QuestionState(val requestId: String) {
  var index by mutableIntStateOf(0)
  val answers = LinkedHashMap<String, String>()
  val selected = mutableStateListOf<String>()
}

data class PromptCache(val at: Long, val ttl: Long, val tokens: Long)

data class LiveContext(val used: Long, val at: Long)

class PendingImage(val image: ImageAttachment)

/** A dismissible alert above a thread, or above the whole chat. */
class Alert(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** A run of a bench to start; absent fields follow the thread's own agent and model. */
data class RunPlan(val agent: String? = null, val model: String? = null)
