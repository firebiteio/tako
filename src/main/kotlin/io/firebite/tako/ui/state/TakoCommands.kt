package io.firebite.tako.ui.state

import androidx.compose.foundation.text.input.clearText
import com.intellij.openapi.diagnostic.logger
import io.firebite.tako.chat.Orchestration
import io.firebite.tako.chat.thread.ChatController
import io.firebite.tako.ui.bridge.message.thread.Submit
import io.firebite.tako.ui.t
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<TakoCommand>()
const val MAX_SLASH_ITEMS = 50
/** Most choices an alert names. */
private const val MAX_LISTED_CHOICES = 10
/** Most characters of a typed argument an alert quotes. */
private const val MAX_QUOTED = 40
private const val NO_MATCH = Int.MAX_VALUE
private val SLASH_QUERY = Regex("""/(\S*)""")
private val ARGUMENT_QUERY = Regex("""/(\S+)[ \t]+(.*)""")

/**
 * A command Tako runs itself instead of sending it to the agent. Its name is [id], typed after a `/`.
 *
 * @param takesArgument it reads an argument: a choice, or the new thread's prompt
 */
enum class TakoCommand(val id: String, val takesArgument: Boolean) {
  MODEL("model", true),
  EFFORT("effort", true),
  MODE("mode", true),
  AGENT("agent", true),
  NEW("new", true),
  ORCHESTRATE("orchestrate", true),
  FORK("fork", false),
  ARCHIVE("archive", false),
  TITLE("title", false),
  RESTART("restart", false),
  USAGE("usage", false),
  SIDE("side", true),
  BTW("btw", true);

  val hint: String
    get() = when (this) {
      MODEL -> t("slash.model.hint")
      EFFORT -> t("slash.effort.hint")
      MODE -> t("slash.mode.hint")
      AGENT -> t("slash.agent.hint")
      NEW -> t("slash.new.hint")
      ORCHESTRATE -> t("slash.orchestrate.hint")
      SIDE, BTW -> t("slash.side.hint")
      else -> ""
    }

  val description: String
    get() = when (this) {
      MODEL -> t("slash.model.description")
      EFFORT -> t("slash.effort.description")
      MODE -> t("slash.mode.description")
      AGENT -> t("slash.agent.description")
      NEW -> t("slash.new.description")
      ORCHESTRATE -> t("slash.orchestrate.description")
      FORK -> t("slash.fork.description")
      ARCHIVE -> t("slash.archive.description")
      TITLE -> t("slash.title.description")
      RESTART -> t("slash.restart.description")
      USAGE -> t("slash.usage.description")
      SIDE, BTW -> t("slash.side.description")
    }

  companion object {
    fun of(name: String): TakoCommand? = entries.firstOrNull { it.id.equals(name, true) }
  }
}

/** A Tako command as typed: the command and the trimmed text after its name. */
data class TakoInvocation(val command: TakoCommand, val argument: String)

/** The Tako command [text] starts with: `/`, a Tako command's name in any case, then blanks or the end. */
fun parseTakoCommand(text: String): TakoInvocation? {
  if (!text.startsWith("/")) {
    return null
  }
  val blank = text.indexOfFirst { it.isWhitespace() }
  val end = if (blank < 0) text.length else blank
  val command = TakoCommand.of(text.substring(1, end)) ?: return null
  return TakoInvocation(command, text.substring(end).trim())
}

/** For `//` before a Tako command, the text to send the agent as typed with one `/` less; null for other text. */
fun escapedTakoCommand(text: String): String? =
  if (text.startsWith("//") && parseTakoCommand(text.substring(1)) != null) text.substring(1) else null

/** One value a Tako command's argument can pick. */
data class Choice(val value: String, val label: String, val description: String?)

/** What the command picks from; null for commands that pick nothing. */
fun ThreadState.choices(command: TakoCommand): List<Choice>? = when (command) {
  TakoCommand.MODEL -> this.models.map { Choice(it.value, it.displayName.ifEmpty { it.value }, it.description) }
  TakoCommand.EFFORT -> this.currentModel?.efforts?.map { Choice(it, effortLabel(it), effortDescription(it)) }
    ?: emptyList()
  TakoCommand.MODE -> this.info?.modes?.map { Choice(it.value, it.label, it.description) } ?: emptyList()
  TakoCommand.AGENT -> this.workspace.agents.map { Choice(it.id, it.name, null) }
  else -> null
}

/** The value the command's argument picked last. */
private fun ThreadState.current(command: TakoCommand): String? = when (command) {
  TakoCommand.MODEL -> this.model
  TakoCommand.EFFORT -> this.effort
  TakoCommand.MODE -> this.mode
  TakoCommand.AGENT -> this.agent
  else -> null
}

private val ThreadState.offersAgentSwitch: Boolean
  get() = this.workspace.agents.size > 1 && this.canSwitchAgent

/** How well the choice fits the query, lower is better: exact value, exact label, prefix, contains. */
private fun rank(choice: Choice, query: String): Int = when {
  choice.value.equals(query, true) -> 0
  choice.label.equals(query, true) -> 1
  choice.value.startsWith(query, true) || choice.label.startsWith(query, true) -> 2
  choice.value.contains(query, true) || choice.label.contains(query, true) -> 3
  else -> NO_MATCH
}

/** The choices of the best fit [rank] that any has; several mean the argument is ambiguous. */
fun matchChoices(choices: List<Choice>, argument: String): List<Choice> {
  val ranks = choices.map { rank(it, argument) }
  val best = ranks.minOrNull() ?: return emptyList()
  return if (best == NO_MATCH) emptyList() else choices.filterIndexed { index, _ -> ranks[index] == best }
}

/** What a Tako command does once checked against the thread. */
sealed interface TakoAction {
  /** Picks [value] for a model, effort, mode or agent command. */
  data class Select(val command: TakoCommand, val value: String) : TakoAction

  /** @param prompt sent in the new thread unless empty */
  data class NewThread(val prompt: String) : TakoAction

  /** @param task sent in this thread with the orchestrator instructions attached */
  data class Orchestrate(val task: String) : TakoAction

  /** @param prompts how many prompts the fork keeps */
  data class Fork(val prompts: Int) : TakoAction

  data object Archive : TakoAction

  data object Title : TakoAction

  data object Restart : TakoAction

  data object Usage : TakoAction

  /** @param question sent in the new side chat unless empty */
  data class SideChat(val question: String) : TakoAction

  /** The command cannot run; [message] says why. */
  data class Fail(val message: String) : TakoAction
}

/** Checks the command against the thread, without doing anything yet. */
fun ThreadState.resolve(invocation: TakoInvocation): TakoAction {
  val command = invocation.command
  if (!command.takesArgument && invocation.argument.isNotEmpty()) {
    return TakoAction.Fail(t("slash.error.argument", command.id))
  }
  if (this.sideChat && !this.offersInSideChat(command)) {
    return TakoAction.Fail(t("slash.error.side.chat", command.id))
  }
  return when (command) {
    TakoCommand.MODEL, TakoCommand.EFFORT, TakoCommand.MODE, TakoCommand.AGENT ->
      this.resolveChoice(command, invocation.argument)
    TakoCommand.NEW -> TakoAction.NewThread(invocation.argument)
    TakoCommand.ORCHESTRATE -> when {
      invocation.argument.isEmpty() -> TakoAction.Fail(t("slash.error.orchestrate.missing"))
      invocation.argument.startsWith("/") -> TakoAction.Fail(t("slash.error.orchestrate.slash"))
      this.isBench -> TakoAction.Fail(t("slash.error.orchestrate.bench"))
      else -> TakoAction.Orchestrate(invocation.argument)
    }
    TakoCommand.FORK -> this.resolveFork()
    TakoCommand.ARCHIVE -> TakoAction.Archive
    TakoCommand.TITLE -> when {
      this.titling -> TakoAction.Fail(t("sidebar.regenerate.title.running"))
      this.turns.isEmpty() && !this.hasSession -> TakoAction.Fail(t("slash.error.title.empty"))
      else -> TakoAction.Title
    }
    TakoCommand.RESTART -> this.resolveRestart()
    TakoCommand.USAGE -> TakoAction.Usage
    TakoCommand.SIDE, TakoCommand.BTW ->
      if (this.workspace.mainOf(this).startingSideChat) TakoAction.Fail(t("slash.error.side.starting"))
      else TakoAction.SideChat(invocation.argument)
  }
}

private fun ThreadState.resolveChoice(command: TakoCommand, argument: String): TakoAction {
  if (command == TakoCommand.AGENT) {
    if (!this.canSwitchAgent) {
      return TakoAction.Fail(t("slash.error.agent.locked"))
    }
    if (this.workspace.agents.size < 2) {
      return TakoAction.Fail(t("slash.error.agent.single"))
    }
  }
  val choices = this.choices(command) ?: emptyList()
  if (choices.isEmpty()) {
    return TakoAction.Fail(if (this.info == null) t("slash.error.not.started", this.agentName) else
      t("slash.error.no.choices", command.id, this.agentName))
  }
  if (argument.isEmpty()) {
    return TakoAction.Fail(t("slash.error.choice.missing", command.id, listed(choices)))
  }
  val found = matchChoices(choices, argument)
  return when (found.size) {
    0 -> TakoAction.Fail(t("slash.error.choice.unknown", command.id, quoted(argument), listed(choices)))
    1 -> TakoAction.Select(command, found[0].value)
    else -> TakoAction.Fail(t("slash.error.choice.ambiguous", command.id, quoted(argument), listed(found)))
  }
}

private fun ThreadState.resolveFork(): TakoAction {
  val info = this.info ?: return TakoAction.Fail(t("slash.error.not.started", this.agentName))
  if (!info.capabilities.fork) {
    return TakoAction.Fail(t("slash.error.fork.unsupported", this.agentName))
  }
  val prompts = this.forkPrompts()
  if (prompts == null || !this.hasSession) {
    return TakoAction.Fail(t("slash.error.fork.empty"))
  }
  return TakoAction.Fork(prompts)
}

private fun ThreadState.resolveRestart(): TakoAction {
  val info = this.info ?: return TakoAction.Fail(t("slash.error.not.started", this.agentName))
  val state = this.controller?.state()
  val isIdle = !this.busy && this.approvals.isEmpty() && (state == null || state == ChatController.State.IDLE ||
    state == ChatController.State.STOPPED)
  return when {
    !info.capabilities.resume -> TakoAction.Fail(t("slash.error.restart.unsupported", this.agentName))
    !isIdle -> TakoAction.Fail(t("slash.error.restart.busy", this.agentName))
    this.stopped -> TakoAction.Fail(t("slash.error.restart.stopped", this.agentName))
    else -> TakoAction.Restart
  }
}

private fun listed(choices: List<Choice>): String =
  choices.take(MAX_LISTED_CHOICES).joinToString(", ") { it.value } +
    if (choices.size > MAX_LISTED_CHOICES) ", …" else ""

private fun quoted(argument: String): String = ChatController.cut(argument.replace('\n', ' '), MAX_QUOTED)

/** How many prompts a fork after the last finished answer keeps, as the timeline's fork button counts them. */
fun ThreadState.forkPrompts(): Int? {
  var prompts = 0
  var last: Int? = null
  for (turn in this.sessionTurns()) {
    if (turn.prompt != null) {
      prompts++
    }
    if (turn.done && prompts > 0 && turn.items.any { it is TurnItem.Text }) {
      last = prompts
    }
  }
  return last
}

fun ThreadState.selectModel(value: String) {
  this.model = value
  this.controller?.setModel(value)
}

fun ThreadState.selectEffort(level: String) {
  this.effort = level
  this.controller?.setEffort(level)
}

fun ThreadState.selectMode(value: String) {
  this.mode = value
  this.controller?.setMode(value)
}

fun ThreadState.perform(action: TakoAction) {
  when (action) {
    is TakoAction.Select -> when (action.command) {
      TakoCommand.MODEL -> this.selectModel(action.value)
      TakoCommand.EFFORT -> this.selectEffort(action.value)
      TakoCommand.MODE -> this.selectMode(action.value)
      TakoCommand.AGENT -> this.workspace.service.setAgent(this.id, action.value)
      else -> {}
    }
    is TakoAction.NewThread -> this.openNewThread(action.prompt)
    is TakoAction.Orchestrate -> this.orchestrate(action.task)
    is TakoAction.Fork -> this.workspace.service.fork(this.id, action.prompts)
    TakoAction.Archive -> this.workspace.archive(this)
    TakoAction.Title -> this.regenerateTitle()
    TakoAction.Restart -> this.controller?.restartSession()
    TakoAction.Usage -> this.workspace.showUsage()
    is TakoAction.SideChat -> if (this.startSideChat(action.question, this.contexts.toList(), this.images.map { it.image })) {
      this.contexts.clear()
      this.images.clear()
    }
    is TakoAction.Fail -> this.showAlert(action.message)
  }
}

/** Opens a thread in this one's folder; a prompt or images go there with the attached context. */
private fun ThreadState.openNewThread(prompt: String) {
  val images = this.images.map { it.image }
  var submit: Submit? = null
  if (prompt.isNotEmpty() || images.isNotEmpty()) {
    submit = Submit(prompt, this.contexts.toList(), images)
    this.contexts.clear()
    this.images.clear()
  }
  this.workspace.service.newThread(this.projectPath, null, submit)
}

/** Sends the task here as a prompt would go: queued or steered while a turn runs, with the attached context. */
private fun ThreadState.orchestrate(task: String) {
  val contexts = listOf(Orchestration.context(t("slash.orchestrate.chip"))) + this.contexts
  this.startPrompt(task, contexts, this.images.map { it.image }, this.followUpDelivery(false))
  this.contexts.clear()
  this.images.clear()
}

/**
 * Has the agent write a title if it can; asking whether it can looks for its CLI, so off the EDT. Counts as writing
 * from the start, so a second `/title` meanwhile is turned down.
 */
private fun ThreadState.regenerateTitle() {
  val shown = this.turns.isNotEmpty()
  this.titling = true
  this.workspace.scope.launch {
    val workspace = this@regenerateTitle.workspace
    val canTitle = try {
      withContext(Dispatchers.IO) { workspace.service.titles().canRegenerate(this@regenerateTitle.id, shown) }
    } catch (e: CancellationException) {
      this@regenerateTitle.titling = false
      throw e
    } catch (e: RuntimeException) {
      LOG.warn("Could not tell whether the agent can title thread " + this@regenerateTitle.id, e)
      false
    }
    if (canTitle) {
      workspace.regenerateTitle(this@regenerateTitle)
    } else {
      this@regenerateTitle.titling = false
      this@regenerateTitle.showAlert(t("slash.error.title.unavailable", this@regenerateTitle.agentName))
    }
  }
}

/**
 * Runs [text] if it is a Tako command, clearing the box unless it cannot run, which an alert explains.
 *
 * @return whether it was a Tako command, which then never reaches the agent
 */
fun ThreadState.runTakoCommand(text: String): Boolean {
  val invocation = parseTakoCommand(text) ?: return false
  val action = this.resolve(invocation)
  if (action !is TakoAction.Fail) {
    this.input.clearText()
  }
  this.perform(action)
  return true
}

/**
 * One row of the composer's `/` menu.
 *
 * @param text what choosing it puts in the box
 * @param runs choosing it with Enter or a click also sends the box
 * @param argument it is a value for a Tako command's argument rather than a command
 */
data class SlashItem(val name: String, val hint: String, val description: String, val tako: Boolean, val text: String,
  val runs: Boolean, val argument: Boolean = false)

/** The `/` menu's rows and the one to highlight first. */
data class SlashMenu(val items: List<SlashItem>, val selected: Int)

private val NO_MENU = SlashMenu(emptyList(), 0)

/**
 * The `/` menu for the box's text: Tako's and the agent's commands while a name is typed, or the values of a Tako
 * command's argument after its name. Empty for other text. Leading blanks don't count, as sending drops them too.
 */
fun ThreadState.slashMenu(box: String): SlashMenu {
  val text = box.trimStart()
  SLASH_QUERY.matchEntire(text)?.let { return SlashMenu(this.commandItems(it.groupValues[1]), 0) }
  val match = ARGUMENT_QUERY.matchEntire(text) ?: return NO_MENU
  val command = TakoCommand.of(match.groupValues[1]) ?: return NO_MENU
  if (command == TakoCommand.AGENT && !this.offersAgentSwitch) {
    return NO_MENU
  }
  val choices = this.choices(command) ?: return NO_MENU
  val query = match.groupValues[2].trim()
  val ranked = choices.map { it to rank(it, query) }.filter { it.second != NO_MATCH }.sortedBy { it.second }
    .take(MAX_SLASH_ITEMS).map { it.first }
  val items = ranked.map {
    SlashItem(it.label, "", it.description ?: (if (it.label != it.value) it.value else ""), true,
      "/" + command.id + " " + it.value, runs = true, argument = true)
  }
  val current = if (query.isEmpty()) ranked.indexOfFirst { it.value == this.current(command) } else 0
  return SlashMenu(items, max(0, current))
}

/** Whether the menu lists the command for this thread; typed, it is Tako's either way. */
private fun ThreadState.offers(command: TakoCommand): Boolean = when {
  this.sideChat && !this.offersInSideChat(command) -> false
  command == TakoCommand.AGENT -> this.offersAgentSwitch
  command == TakoCommand.FORK -> this.info?.capabilities?.fork == true
  command == TakoCommand.RESTART -> this.info?.capabilities?.resume == true
  else -> true
}

/** A side chat goes with its main thread: it doesn't fork, archive or take another agent on its own. */
private fun ThreadState.offersInSideChat(command: TakoCommand): Boolean =
  command != TakoCommand.FORK && command != TakoCommand.ARCHIVE && command != TakoCommand.AGENT
    && command != TakoCommand.ORCHESTRATE

/** Commands whose name has the query: exact name first, then those starting with it, Tako's before the agent's. */
private fun ThreadState.commandItems(query: String): List<SlashItem> {
  val items = ArrayList<SlashItem>()
  for (command in TakoCommand.entries) {
    if (this.offers(command)) {
      val text = "/" + command.id + if (command.takesArgument) " " else ""
      items.add(SlashItem(command.id, command.hint, command.description, true, text,
        !command.takesArgument && command.id.equals(query, true)))
    }
  }
  for (command in this.commands) {
    if (TakoCommand.of(command.name) == null) {
      items.add(SlashItem(command.name, command.hint, command.description, false, "/" + command.name + " ", false))
    }
  }
  return items.filter { it.name.contains(query, true) }.sortedBy {
    when {
      it.name.equals(query, true) -> 0
      it.name.startsWith(query, true) -> 1
      else -> 2
    }
  }.take(MAX_SLASH_ITEMS)
}
