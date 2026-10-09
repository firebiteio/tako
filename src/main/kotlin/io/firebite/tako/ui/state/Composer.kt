package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.ImageAttachment
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ui.bridge.PageOp
import io.firebite.tako.ui.t
import kotlin.math.min
import kotlin.math.roundToInt

const val CACHE_TTL_5M = 5 * 60 * 1000L
const val CACHE_TTL_1H = 60 * 60 * 1000L
/** Without a reported window size, assume the common one. */
private const val DEFAULT_MAX_TOKENS = 200000L

/** The typed text, without surrounding blanks. */
val ThreadState.typed: String
  get() = this.input.text.toString().trim()

/** Takes the typed text, e.g. as feedback for a declined approval, and clears the box. */
fun ThreadState.takeFeedback(): String {
  val text = this.typed
  this.input.clearText()
  return text
}

/** Sends what is typed; with the agent waiting, it answers the question or declines the approval instead. */
fun ThreadState.submit() {
  if (this.question != null) {
    this.advanceQuestion()
    return
  }
  val approval = this.approvals.firstOrNull()
  if (approval != null) {
    this.declineWithFeedback(approval)
    return
  }
  val text = this.typed
  val images = this.images.map { it.image }
  if (text.isEmpty() && images.isEmpty()) {
    if (this.busy) {
      this.send(PageOp.Interrupt())
    }
    return
  }
  if (this.isBench) {
    if (!this.startBench(text)) {
      return
    }
  } else {
    this.startPrompt(text, this.contexts.toList(), images, this.workspace.includeFile)
  }
  this.contexts.clear()
  this.images.clear()
  this.input.clearText()
}

/** Sends, then opens a new thread in the same folder. */
fun ThreadState.submitAndBranch() {
  if (this.question != null || this.approvals.isNotEmpty() || (this.typed.isEmpty() && this.images.isEmpty())) {
    return
  }
  this.submit()
  this.workspace.send(PageOp.NewThread(null, null))
}

/** Sends a command as if typed, e.g. `/compact`. */
fun ThreadState.sendCommand(command: String) {
  this.input.setTextAndPlaceCursorAtEnd(command)
  this.submit()
}

/** IDE context attached from an editor action; a proposed prompt fills an empty box. */
fun ThreadState.addContext(item: ContextItem, prefill: String?) {
  this.contexts.add(item)
  if (!this.isActive) {
    return
  }
  if (prefill != null && this.typed.isEmpty()) {
    this.input.setTextAndPlaceCursorAtEnd(prefill)
  }
  this.workspace.focusComposer++
}

fun ThreadState.addImage(image: ImageAttachment) {
  this.images.add(PendingImage(image))
  if (this.isActive) {
    this.workspace.focusComposer++
  }
}

fun ThreadState.addPermission(requestId: String, prompt: PermissionPrompt) {
  this.approvals.add(Approval(requestId, prompt))
  if (prompt.kind == PermissionPrompt.Kind.PLAN) {
    this.currentTurn().items.add(TurnItem.Plan(prompt.plan ?: ""))
  }
  this.scrollToEnd++
}

fun ThreadState.removeApproval(requestId: String) {
  this.approvals.removeAll { it.requestId == requestId }
  if (this.question?.requestId == requestId) {
    this.question = null
  }
}

fun ThreadState.cancelAllApprovals() {
  this.approvals.clear()
  this.question = null
}

/** Answers the waiting prompt. @param suggestion the index of the suggestion approved with */
fun ThreadState.answer(approval: Approval, allow: Boolean, message: String? = null, interrupt: Boolean = false,
  answers: Map<String, String>? = null, suggestion: Int? = null) {
  this.send(PageOp.Permission(approval.requestId, allow, message, interrupt, answers, suggestion))
  val prompt = approval.prompt
  val toolUseId = prompt.toolUseId
  if (!allow && toolUseId != null) {
    this.declined.add(toolUseId)
  }
  val mode = suggestion?.let { prompt.suggestions.getOrNull(it)?.mode }
  if (allow && mode != null) {
    this.mode = mode
  }
  this.removeApproval(approval.requestId)
}

/** Declines with the typed text as the reason, or the fallback without one. */
fun ThreadState.deny(approval: Approval, fallback: String, interrupt: Boolean = false) {
  this.answer(approval, false, this.takeFeedback().ifEmpty { fallback }, interrupt)
}

/** The typed text declines the waiting approval, or refines the plan. */
private fun ThreadState.declineWithFeedback(approval: Approval) {
  if (this.typed.isEmpty()) {
    return
  }
  this.deny(approval, if (approval.prompt.kind == PermissionPrompt.Kind.PLAN) "Keep planning." else "Declined.")
}

/** The question being answered, starting it when the waiting prompt asks one. */
fun ThreadState.questionFor(approval: Approval): QuestionState {
  val current = this.question
  if (current != null && current.requestId == approval.requestId) {
    return current
  }
  return QuestionState(approval.requestId).also { this.question = it }
}

/** Picks an option of the shown question; a single choice also moves on. @return whether to move on */
fun ThreadState.chooseOption(index: Int): Boolean {
  val q = this.question ?: return false
  val question = this.approvals.firstOrNull()?.prompt?.questions?.getOrNull(q.index) ?: return false
  val option = question.options.getOrNull(index) ?: return false
  if (question.multiSelect) {
    if (!q.selected.remove(option.label)) {
      q.selected.add(option.label)
    }
    return false
  }
  q.selected.clear()
  q.selected.add(option.label)
  return true
}

/** Takes the selected options and typed text as the answer, then shows the next question or answers the agent. */
fun ThreadState.advanceQuestion() {
  val q = this.question ?: return
  val approval = this.approvals.firstOrNull() ?: return
  if (approval.requestId != q.requestId) {
    return
  }
  val questions = approval.prompt.questions
  val values = q.selected.toMutableList()
  val typed = this.takeFeedback()
  if (typed.isNotEmpty()) {
    values.add(typed)
  }
  if (values.isEmpty()) {
    return
  }
  q.answers[questions[q.index].question] = values.joinToString(", ")
  q.selected.clear()
  if (q.index < questions.size - 1) {
    q.index++
    return
  }
  this.answer(approval, true, answers = q.answers)
}

/** Runs can be set up on a thread that has not started, while the settings allow it. */
val ThreadState.canPlanRuns: Boolean
  get() = this.workspace.parallelRuns && this.canChangeAgent

val ThreadState.isBench: Boolean
  get() = this.canPlanRuns && this.runs.size > 1

fun ThreadState.modelsOf(agent: String): List<io.firebite.tako.agent.AgentInfo.Model> {
  val info = this.info
  if (agent == this.agent && info != null) {
    return info.models
  }
  return this.workspace.agentModels[agent] ?: emptyList()
}

fun ThreadState.modelLabel(agent: String, model: String): String {
  if (model.isEmpty()) {
    return t("runs.default.model")
  }
  val match = this.modelsOf(agent).firstOrNull { it.value == model }
  return match?.displayName?.ifEmpty { null } ?: match?.value ?: model
}

/** What a run starts with: its own choices, else the thread's when it runs the thread's agent. */
fun ThreadState.specOf(plan: RunPlan): PageOp.RunSpec {
  val agent = plan.agent ?: this.agent
  val isSameAgent = agent == this.agent
  return PageOp.RunSpec(agent, plan.model ?: (if (isSameAgent) this.model ?: "" else ""),
    if (isSameAgent && this.info != null) this.mode else "", if (isSameAgent) this.effort ?: "" else "")
}

fun ThreadState.runLabel(plan: RunPlan): String {
  val spec = this.specOf(plan)
  return (this.workspace.agentName(spec.agent) ?: spec.agent) + " · " + this.modelLabel(spec.agent, spec.model)
}

fun ThreadState.setRunCount(count: Int) {
  if (count <= 1) {
    this.runs.clear()
    return
  }
  while (this.runs.size > count) {
    this.runs.removeAt(this.runs.size - 1)
  }
  while (this.runs.size < count) {
    this.runs.add(RunPlan())
  }
}

/** A run's name: its number, agent and model. */
fun ThreadState.runName(index: Int): String {
  val model = this.model?.let { this.modelLabel(this.agent, it) } ?: ""
  return "#" + index + " · " + this.agentName + (if (model.isNotEmpty()) " · $model" else "")
}

/** A prompt waiting for its bench's threads. */
class PendingBench(val source: ThreadState, val text: String, val contexts: List<ContextItem>,
  val images: List<PendingImage>, val includeFile: Boolean)

/**
 * Asks the IDE for a worktree and thread per run; the prompt goes out once they exist.
 *
 * @return false while the thread still waits for an earlier bench
 */
private fun ThreadState.startBench(text: String): Boolean {
  val workspace = this.workspace
  if (workspace.pendingBenches.values.any { it.source === this }) {
    return false
  }
  val request = ++workspace.lastBenchRequest
  workspace.pendingBenches[request] = PendingBench(this, text, this.contexts.toList(), this.images.toList(),
    workspace.includeFile)
  workspace.send(PageOp.CreateBench(request, text, this.projectPath, this.id, this.runs.map { this.specOf(it) }))
  this.setBusy(true)
  return true
}

/** Sends the waiting prompt to each run's thread. */
fun Workspace.onBenchCreated(request: Int, threadIds: List<String>) {
  val prompt = this.pendingBenches.remove(request) ?: return
  if (this.threads.containsKey(prompt.source.id)) {
    prompt.source.setBusy(false)
  }
  for (id in threadIds) {
    this.threads[id]?.startPrompt(prompt.text, prompt.contexts, prompt.images.map { it.image }, prompt.includeFile)
  }
}

/** Gives the prompt back to the thread it was typed in. An empty text means the person cancelled. */
fun Workspace.onBenchFailed(request: Int, text: String) {
  val prompt = this.pendingBenches.remove(request) ?: return
  val source = prompt.source
  if (!this.threads.containsKey(source.id)) {
    return
  }
  source.setBusy(false)
  source.contexts.clear()
  source.contexts.addAll(prompt.contexts)
  source.images.clear()
  source.images.addAll(prompt.images)
  if (source.typed.isEmpty()) {
    source.input.setTextAndPlaceCursorAtEnd(prompt.text)
  }
  if (text.isNotEmpty()) {
    source.showAlert(text)
  }
}

data class ContextNumbers(val used: Long, val max: Long, val threshold: Long?, val percent: Int)

enum class CacheKind { BUSY, WARM, COLD }

data class CacheState(val kind: CacheKind, val remaining: Long)

/** Notes a request's token usage: the context it filled and the prompt cache it read or wrote. */
fun ThreadState.noteUsage(usage: AgentEvent.Usage, at: Long) {
  usage.contextWindow?.let { if (it > 0) this.contextWindow = it }
  val read = usage.cacheRead
  val written = usage.cacheWrite
  val prompt = usage.input + read + written
  if (prompt == 0L) {
    return
  }
  var ttl = this.cache?.ttl ?: CACHE_TTL_5M
  if (usage.cacheWrite1h > 0) {
    ttl = CACHE_TTL_1H
  } else if (usage.cacheWrite5m > 0) {
    ttl = CACHE_TTL_5M
  }
  this.liveContext = LiveContext(prompt + usage.output, at)
  if (read + written > 0) {
    this.cache = PromptCache(at, ttl, read + written)
  }
}

/** The last breakdown's numbers, or the live request's total while the agent works. */
fun ThreadState.contextNumbers(): ContextNumbers? {
  val usage = this.contextUsage
  val max = usage?.maxTokens?.takeIf { it > 0 } ?: this.contextWindow ?: DEFAULT_MAX_TOKENS
  var used = usage?.totalTokens
  val live = this.liveContext
  if (live != null && (usage == null || this.busy || used == null)) {
    used = live.used
  }
  if (used == null) {
    return null
  }
  val threshold = if (usage != null && usage.isAutoCompactEnabled != false) usage.autoCompactThreshold else null
  return ContextNumbers(used, max, threshold?.takeIf { it > 0 }, min(100, (used * 100.0 / max).roundToInt()))
}

fun ThreadState.cacheState(cache: PromptCache, now: Long): CacheState {
  if (this.busy) {
    return CacheState(CacheKind.BUSY, cache.ttl)
  }
  val remaining = cache.at + cache.ttl - now
  return CacheState(if (remaining > 0) CacheKind.WARM else CacheKind.COLD, remaining)
}
