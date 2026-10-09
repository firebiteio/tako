package io.firebite.tako.ui.state

import com.google.gson.JsonObject
import io.firebite.tako.agent.AgentEvent
import io.firebite.tako.ui.t

enum class TaskState { RUNNING, DONE, ERROR }

private val TASK_DONE = setOf("completed", "done", "success")
private val TASK_FAILED = setOf("failed", "error", "killed", "stopped", "cancelled")

fun Task.taskType(): TaskType = when (this.type) {
  "shell" -> TaskType.SHELL
  "workflow" -> TaskType.WORKFLOW
  else -> TaskType.AGENT
}

fun TaskType.kind(): String = when (this) {
  TaskType.AGENT -> t("tasks.agent")
  TaskType.SHELL -> t("tasks.shell")
  TaskType.WORKFLOW -> t("tasks.workflow")
}

fun TaskType.title(): String = when (this) {
  TaskType.AGENT -> t("tasks.agent.title")
  TaskType.SHELL -> t("tasks.shell.title")
  TaskType.WORKFLOW -> t("tasks.workflow.title")
}

/**
 * A task's state from its events; a foreground task (not backgrounded) is also over once its tool result came, since
 * the CLI may send the final event late or never.
 */
fun taskState(task: Task?, card: Entry?): TaskState {
  if (task != null && task.status in TASK_DONE) {
    return TaskState.DONE
  }
  if (task != null && task.status in TASK_FAILED) {
    return TaskState.ERROR
  }
  val resultCame = card != null && card.status != EntryStatus.RUNNING
  if (task != null && !(resultCame && task.backgrounded != true)) {
    return TaskState.RUNNING
  }
  return when (card?.status) {
    null, EntryStatus.RUNNING -> TaskState.RUNNING
    EntryStatus.ERROR -> TaskState.ERROR
    else -> TaskState.DONE
  }
}

fun ThreadState.cardState(card: TaskCard): TaskState = taskState(card.task, card)

/** The entry id of a task's card: its tool call, or a made-up one for tasks the stream showed no call for. */
private fun cardId(task: Task): String = task.toolUseId ?: ("task-" + task.id)

fun ThreadState.cardOf(task: Task): TaskCard? = this.entries[cardId(task)] as? TaskCard

fun ThreadState.runningTasks(): List<Task> =
  this.tasks.values.filter { taskState(it, this.entries[cardId(it)]) == TaskState.RUNNING }

/** The process ended: nothing it started runs anymore. */
fun ThreadState.stopAllTasks() {
  for (task in this.tasks.values) {
    if (taskState(task, this.entries[cardId(task)]) != TaskState.RUNNING) {
      continue
    }
    task.status = "stopped"
    task.endedAt = task.endedAt ?: System.currentTimeMillis()
    this.cardOf(task)?.let { settleSteps(it) }
  }
  for (entry in this.entries.values) {
    if (entry is TaskCard && entry.status == EntryStatus.RUNNING && entry.task == null) {
      entry.status = EntryStatus.ERROR
    }
  }
}

/** Steps of a finished task that never got their result are over too. */
private fun settleSteps(card: TaskCard) {
  for (step in card.steps) {
    if (step is ToolEntry && step.status == EntryStatus.RUNNING) {
      step.status = EntryStatus.DONE
    }
  }
}

/** The task's tool call returned. */
fun ThreadState.onTaskResult(card: TaskCard) {
  if (this.cardState(card) != TaskState.RUNNING) {
    settleSteps(card)
  }
}

fun TaskCard.kind(): String = this.taskType.kind()

fun TaskCard.taskTitle(): String = this.task?.description
  ?: this.input.string("description")
  ?: this.input.string("title")
  ?: this.input.string("name")
  ?: this.taskType.title()

/** The task's card; tasks the stream never showed a tool call for get one of their own. */
private fun ThreadState.taskCardFor(task: Task): TaskCard {
  this.cardOf(task)?.let { return it }
  val input = JsonObject()
  task.description?.let { input.addProperty("description", it) }
  task.subagentType?.let { input.addProperty("subagent_type", it) }
  task.prompt?.let { input.addProperty("prompt", it) }
  val type = task.taskType()
  val card = TaskCard(this, cardId(task), type.kind(), input, null, type)
  this.entries[card.id] = card
  this.addTaskToTurn(card)
  return card
}

fun ThreadState.onTaskEvent(event: AgentEvent.TaskUpdated) {
  val id = event.taskId
  if (id.isEmpty()) {
    return
  }
  val task = this.tasks.getOrPut(id) { Task(id, System.currentTimeMillis()) }
  event.toolUseId?.let { task.toolUseId = it }
  event.taskType?.let { task.type = it }
  event.description?.let { task.description = it }
  event.subagentType?.let { task.subagentType = it }
  event.prompt?.let { task.prompt = it }
  event.backgrounded?.let { task.backgrounded = it }
  event.status?.let { task.status = it }
  event.summary?.let { task.summary = it }
  event.toolUses?.let { task.toolUses = it }
  event.totalTokens?.let { task.totalTokens = it }
  event.lastTool?.let { task.lastTool = it }
  event.endedAt?.let { task.endedAt = it }
  if (event.notified && task.endedAt == null) {
    task.endedAt = System.currentTimeMillis()
  }
  for (agent in event.agents) {
    task.agents[agent.index] = merge(task.agents[agent.index], agent)
  }
  for (log in event.logs) {
    if (task.seenLogs.add(log)) {
      task.logs.add(log)
    }
  }
  if (task.taskType() == TaskType.AGENT && task.toolUseId == null) {
    return
  }
  val card = this.taskCardFor(task)
  card.task = task
  val finished = this.cardState(card) != TaskState.RUNNING
  if (finished) {
    settleSteps(card)
  }
  val turn = card.turn
  if (finished && card.completion == null && turn != null && turn.done) {
    val completion = TurnItem.Completion(card)
    card.completion = completion
    this.currentTurn().items.add(completion)
  }
}

/** A workflow agent's update keeps what it does not repeat. */
private fun merge(old: AgentEvent.WorkflowAgent?, new: AgentEvent.WorkflowAgent): AgentEvent.WorkflowAgent {
  if (old == null) {
    return new
  }
  return AgentEvent.WorkflowAgent(new.index, new.label ?: old.label, new.state ?: old.state, new.model ?: old.model,
    new.toolCalls ?: old.toolCalls, new.tokens ?: old.tokens, new.phaseIndex ?: old.phaseIndex,
    new.phaseTitle ?: old.phaseTitle, new.error ?: old.error, new.resultPreview ?: old.resultPreview,
    new.promptPreview ?: old.promptPreview)
}

/** A string member of a tool call's arguments. */
fun JsonObject.string(name: String): String? {
  val value = this.get(name)
  return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
}
