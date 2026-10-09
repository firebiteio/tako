package io.firebite.tako.ui.state

import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.ImageAttachment
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ui.bridge.PageOp
import io.firebite.tako.ui.duration
import io.firebite.tako.ui.t

/** How much tool output a panel shows. */
const val OUTPUT_LIMIT = 20000

/** Turns the agent's events into the thread's timeline, live or replayed from its past. */
fun ThreadState.handleEvent(event: AgentEvent) {
  when (event) {
    is AgentEvent.SessionStarted -> {
      event.mode?.let { this.mode = it }
      event.model?.let { this.selectResolvedModel(it) }
      this.scratchpad = event.scratchpadPath
    }
    is AgentEvent.ModeChanged -> this.mode = event.mode
    is AgentEvent.CommandsChanged -> this.commands = event.commands
    is AgentEvent.Busy -> if (!this.replaying) {
      this.setBusy(true)
    }
    is AgentEvent.MessageStarted -> {
      this.currentMessageId = event.messageId
      this.liveByIndex.clear()
    }
    is AgentEvent.TextStarted -> this.startLiveText(event.blockId)
    is AgentEvent.TextDelta -> this.liveByIndex[event.blockId]?.let { it.row.text += event.text }
    is AgentEvent.ThinkingStarted -> {
      this.currentTurn()
      this.addThinking(event.blockId)
    }
    is AgentEvent.BlockEnded -> this.thinkingByIndex.remove(event.blockId)?.let { this.turn?.items?.remove(it) }
    is AgentEvent.Text -> this.onText(event.messageId, event.text, event.parentId)
    is AgentEvent.ToolStarted -> this.addToolUse(event)
    is AgentEvent.ToolFinished -> this.attachResult(event)
    is AgentEvent.Prompt -> {
      val contexts = if (event.hasIdeContext) {
        listOf(ContextItem("", ContextItem.Kind.FILE, t("stream.ide.context"), ""))
      } else {
        emptyList()
      }
      this.startTurn(Prompt(event.text, contexts, event.images), true)
    }
    is AgentEvent.Interrupted -> this.turn?.stopped = true
    is AgentEvent.Usage -> {
      val at = event.at ?: System.currentTimeMillis()
      val live = this.liveContext
      if (event.at == null || this.replaying || live == null || at >= live.at) {
        this.noteUsage(event, at)
      }
    }
    is AgentEvent.TurnEnded -> this.onTurnEnded(event)
    is AgentEvent.RateLimits -> this.workspace.limits =
      event.limits.joinToString(" · ") { it.label + " " + Math.round(it.usedPercent) + "%" }
    is AgentEvent.Retrying -> this.showAlert(retrying(event.attempt, event.message))
    is AgentEvent.Compacted -> this.currentTurn().items.add(TurnItem.Note(t("stream.compacted")))
    is AgentEvent.PermissionCancelled -> this.removeApproval(event.requestId)
    is AgentEvent.TaskUpdated -> this.onTaskEvent(event)
  }
}

private fun retrying(attempt: Int?, message: String?): String = when {
  attempt != null && message != null -> t("stream.retrying.attempt.message", attempt, message)
  attempt != null -> t("stream.retrying.attempt", attempt)
  message != null -> t("stream.retrying.message", message)
  else -> t("stream.retrying")
}

private fun ThreadState.startLiveText(blockId: String) {
  val turn = this.currentTurn()
  this.clearThinking()
  val row = AssistantText("", false)
  turn.items.add(TurnItem.Text(row))
  turn.group = null
  val live = LiveText(row)
  this.live.getOrPut(this.currentMessageId) { ArrayList() }.add(live)
  this.liveByIndex[blockId] = live
}

/** A complete text block: it finishes its streamed block, goes into the task that wrote it, or stands alone. */
private fun ThreadState.onText(messageId: String?, text: String, parentId: String?) {
  if (parentId != null) {
    this.entries[parentId]?.steps?.add(SubText(io.firebite.tako.ui.firstLine(text)))
    return
  }
  val live = this.live[messageId]?.firstOrNull { !it.done }
  if (live != null) {
    live.done = true
    live.row.text = text
    live.row.done = true
  } else {
    this.addAssistantText(text)
  }
}

private fun ThreadState.onTurnEnded(event: AgentEvent.TurnEnded) {
  this.finishTurn(event)
  this.setBusy(false)
  this.finished = true
  if (!this.isActive) {
    this.unread = true
  }
  event.cost?.let { this.costUsd = it.costUsd }
}

fun ThreadState.setBusy(busy: Boolean) {
  this.busy = busy
  if (busy && this.startedAt == 0L) {
    this.startedAt = System.currentTimeMillis()
  }
  if (!busy) {
    this.startedAt = 0
  }
}

fun ThreadState.startTurn(prompt: Prompt?, replayed: Boolean): Turn {
  this.finishTurn(null)
  val turn = Turn(this.turnNo, prompt, replayed)
  this.turns.add(turn)
  this.turn = turn
  return turn
}

/** The running turn, or one without a prompt for output that arrives after the last turn ended. */
fun ThreadState.currentTurn(): Turn {
  val turn = this.turn
  return if (turn != null && !turn.done) turn else this.startTurn(null, this.replaying)
}

/** Adds a finished text block of the agent's to the running turn. */
private fun ThreadState.addAssistantText(text: String) {
  val turn = this.currentTurn()
  this.clearThinking()
  val row = AssistantText(text, true)
  turn.items.add(TurnItem.Text(row))
  turn.group = null
}

/** Thinking rows only show while the agent thinks; output replaces them. */
private fun ThreadState.clearThinking() {
  val turn = this.turn
  for (row in this.thinkingByIndex.values) {
    turn?.items?.remove(row)
  }
  this.thinkingByIndex.clear()
}

private fun ThreadState.addThinking(blockId: String) {
  this.clearThinking()
  val row = TurnItem.Thinking()
  this.currentTurn().items.add(row)
  this.thinkingByIndex[blockId] = row
}

/** Adds a task card to the running turn, outside any work group. */
fun ThreadState.addTaskToTurn(card: TaskCard) {
  val turn = this.currentTurn()
  turn.group = null
  card.turn = turn
  turn.items.add(TurnItem.Task(card))
}

/**
 * Shows a tool call: inside the subagent that made it, as a task card, on its own (todos, plans) or in the turn's work
 * group.
 */
private fun ThreadState.addToolUse(tool: AgentEvent.ToolStarted) {
  val parentId = tool.parentId
  val parent = parentId?.let { this.entries[it] }
  if (tool.kind == ToolKind.AGENT) {
    val type = if (tool.name == Tool.WORKFLOW) TaskType.WORKFLOW else TaskType.AGENT
    val card = TaskCard(this, tool.id, tool.name, tool.input, tool.title, type)
    this.entries[tool.id] = card
    for (task in this.tasks.values) {
      if (task.toolUseId == tool.id) {
        card.task = task
      }
    }
    if (parentId != null) {
      parent?.steps?.add(card)
      return
    }
    this.currentTurn()
    this.clearThinking()
    this.addTaskToTurn(card)
    return
  }
  val entry = ToolEntry(this, tool.id, tool.name, tool.input, tool.kind, tool.title)
  this.entries[tool.id] = entry
  if (parentId != null) {
    parent?.steps?.add(entry)
    return
  }
  val turn = this.currentTurn()
  this.clearThinking()
  if (entry.category == ToolKind.TODO || entry.category == ToolKind.PLAN) {
    turn.group = null
    turn.items.add(TurnItem.Single(entry))
    return
  }
  val group = turn.group ?: WorkGroup(this).also {
    turn.group = it
    turn.items.add(TurnItem.Group(it))
  }
  group.entries.add(entry)
}

private fun ThreadState.attachResult(result: AgentEvent.ToolFinished) {
  val entry = this.entries[result.id] ?: return
  if (entry is TaskCard) {
    entry.status = if (result.isError) EntryStatus.ERROR else EntryStatus.DONE
    entry.resultText = result.output
    this.onTaskResult(entry)
    return
  }
  entry as ToolEntry
  entry.status = when {
    this.declined.contains(result.id) -> EntryStatus.DECLINED
    result.isError -> EntryStatus.ERROR
    else -> EntryStatus.DONE
  }
  val change = result.change
  if (change != null) {
    entry.isNew = change.isNew
    entry.added += change.added
    entry.removed += change.removed
    val turn = this.currentTurn()
    val previous = turn.changes[change.path]
    turn.changes[change.path] = AgentEvent.Change(change.path, (previous?.added ?: 0) + change.added,
      (previous?.removed ?: 0) + change.removed, previous?.isNew ?: change.isNew)
  }
  val text = result.output
  val showsOutput = entry.category != ToolKind.READ && entry.category != ToolKind.EDIT &&
    entry.category != ToolKind.TODO
  if (text.isNotEmpty() && (showsOutput || result.isError)) {
    entry.output = io.firebite.tako.ui.truncate(text, OUTPUT_LIMIT)
    entry.outputIsError = result.isError
  }
}

/**
 * Ends the running turn: settles what still streams or runs, folds the work above the final answer and lists the
 * changed files.
 *
 * @param result how the turn ended, null when the process ended it
 */
fun ThreadState.finishTurn(result: AgentEvent.TurnEnded?) {
  val turn = this.turn
  if (turn == null || turn.done) {
    return
  }
  turn.done = true
  this.clearThinking()
  for (texts in this.live.values) {
    for (live in texts) {
      if (!live.done) {
        live.done = true
        live.row.done = true
      }
    }
  }
  this.live.clear()
  for (entry in this.entries.values) {
    if (entry is ToolEntry && entry.status == EntryStatus.RUNNING) {
      entry.status = if (turn.stopped) EntryStatus.DECLINED else EntryStatus.DONE
    }
  }
  val durationMs = result?.durationMs
  val took = if (durationMs != null && durationMs > 0) durationMs else System.currentTimeMillis() - turn.startedAt
  this.foldWork(turn, if (turn.replayed && result == null) null else took)
  val error = result?.error
  if (error != null && !turn.stopped) {
    turn.error = error
    this.failed = true
  }
  turn.changeList = turn.changes.values.toList()
}

/** Folds a finished turn's work under "Worked for …", leaving the final text visible. */
private fun ThreadState.foldWork(turn: Turn, took: Long?) {
  val items = turn.items.filter { item ->
    item !is TurnItem.Completion && !(item is TurnItem.Task && this.cardState(item.card) == TaskState.RUNNING)
  }
  val foldable = if (items.lastOrNull() is TurnItem.Text) items.dropLast(1) else items
  if (foldable.none { it !is TurnItem.Text }) {
    return
  }
  val label = when {
    turn.stopped -> t("timeline.stopped", duration(took ?: 0))
    took != null -> t("timeline.worked.for", duration(took))
    else -> t("timeline.worked")
  }
  turn.fold = Fold(label, foldable.toSet())
}

/** Rebuilds the timeline from the thread's transcript. */
fun ThreadState.replay(events: List<AgentEvent>) {
  this.turns.clear()
  this.turn = null
  this.turnNo = 0
  this.entries.clear()
  this.declined.clear()
  this.live.clear()
  this.liveByIndex.clear()
  this.thinkingByIndex.clear()
  this.finished = false
  this.failed = false
  this.cancelAllApprovals()
  this.setBusy(false)
  this.replaying = true
  for (event in events) {
    this.handleEvent(event)
  }
  this.finishTurn(null)
  this.replaying = false
  this.finished = false
  this.scrollToEnd++
}

/** The thread's agent is ready: what it can do, and which model and mode it runs with. */
fun ThreadState.onInit(info: AgentInfo, projectRoot: String?) {
  if (projectRoot != null) {
    this.projectRoot = projectRoot
  }
  this.info = info
  this.commands = info.commands
  val wanted = info.model ?: ""
  val models = info.models
  if (models.isNotEmpty()) {
    this.workspace.agentModels[this.agent] = models
  }
  val match = models.firstOrNull { it.value == wanted } ?: models.firstOrNull { it.resolvedModel == wanted }
  if (this.model == null || models.none { it.value == this.model }) {
    this.model = match?.value ?: models.firstOrNull()?.value
  }
  info.mode?.let { this.mode = it }
  info.effort?.let { this.effort = it }
}

/** Selects the model the agent reports it resolved the thread's choice to, unless that is the choice already. */
private fun ThreadState.selectResolvedModel(resolved: String) {
  val current = this.currentModel
  if (current != null && (current.resolvedModel == resolved || current.value == resolved)) {
    return
  }
  this.models.firstOrNull { it.resolvedModel == resolved || it.value == resolved }?.let { this.model = it.value }
}

/** The process ended; a crash offers a restart. */
fun ThreadState.onExit(code: Int, stderr: String) {
  this.finishTurn(null)
  this.setBusy(false)
  this.cancelAllApprovals()
  this.stopAllTasks()
  if (code != 0 && stderr.isNotEmpty()) {
    this.failed = true
    this.showAlert(t("thread.exited", this.agentName, code, stderr.trim()), t("thread.restart")) {
      this.send(PageOp.Restart())
    }
  }
}

fun ThreadState.onError(text: String) {
  this.setBusy(false)
  this.failed = true
  this.showAlert(text)
}

/** Shows the prompt as the thread's next turn and sends it. */
fun ThreadState.startPrompt(text: String, contexts: List<ContextItem>, images: List<ImageAttachment>,
  includeActiveFile: Boolean) {
  this.turnNo++
  this.finished = false
  this.failed = false
  this.startTurn(Prompt(text, contexts, images), false)
  this.send(PageOp.Send(text, contexts, images, includeActiveFile, this.turnNo))
  this.updatedAt = System.currentTimeMillis()
  this.setBusy(true)
  this.scrollToEnd++
}
