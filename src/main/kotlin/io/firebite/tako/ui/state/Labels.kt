package io.firebite.tako.ui.state

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.ToolKind
import io.firebite.tako.json.Json
import io.firebite.tako.ui.conjunction
import io.firebite.tako.ui.firstLine
import io.firebite.tako.ui.t

/** Tool names some labels and task cards single out; any agent may use them. */
object Tool {
  const val WRITE = "Write"
  const val POWERSHELL = "PowerShell"
  const val GLOB = "Glob"
  const val LS = "LS"
  const val WEB_SEARCH = "WebSearch"
  const val WORKFLOW = "Workflow"
  const val EXIT_PLAN_MODE = "ExitPlanMode"
}

/** A label is text with monospace runs, e.g. Ran `npm test`. */
sealed interface LabelPart {
  val text: String

  data class Plain(override val text: String) : LabelPart

  data class Mono(override val text: String) : LabelPart
}

/** Stands in for the monospace run while a label's text is translated. */
private const val MONO = "\u0001"

private val PRETTY = GsonBuilder().setPrettyPrinting().create()

/** A translated label whose argument {0} shows in monospace. */
private fun withMono(text: String, mono: String): List<LabelPart> {
  val before = text.substringBefore(MONO)
  val after = text.substringAfter(MONO, "")
  return listOf(LabelPart.Plain(before), LabelPart.Mono(mono), LabelPart.Plain(after))
}

fun entryLabel(entry: ToolEntry): List<LabelPart> {
  val input = entry.input
  val status = entry.status
  val running = status == EntryStatus.RUNNING
  val relative = entry.thread::relative
  return when (entry.category) {
    ToolKind.COMMAND -> {
      val command = firstLine(input.string("command") ?: input.string("description") ?: entry.name)
      val text = when {
        status == EntryStatus.DECLINED -> t("tools.command.declined", MONO)
        running -> t("tools.command.running", MONO)
        status == EntryStatus.ERROR -> t("tools.command.failed", MONO)
        else -> t("tools.command.ran", MONO)
      }
      withMono(text, command)
    }
    ToolKind.READ -> {
      val path = relative(input.string("file_path") ?: input.string("notebook_path"))
      listOf(LabelPart.Plain(if (running) t("tools.read.running", path) else t("tools.read", path)))
    }
    ToolKind.EDIT -> {
      val path = relative(input.string("file_path") ?: input.string("notebook_path"))
      val text = when {
        status == EntryStatus.DECLINED -> t("tools.edit.declined", path)
        entry.name == Tool.WRITE && entry.isNew -> if (running) t("tools.edit.creating", path) else
          t("tools.edit.created", path)
        running -> t("tools.edit.editing", path)
        else -> t("tools.edit.edited", path)
      }
      listOf(LabelPart.Plain(text))
    }
    ToolKind.SEARCH -> {
      val pattern = input.string("pattern") ?: ""
      val path = input.string("path")
      when {
        entry.title != null -> listOf(LabelPart.Plain(entry.title))
        entry.name == Tool.GLOB -> withMono(t("tools.search.files", MONO), pattern)
        entry.name == Tool.LS -> listOf(LabelPart.Plain(t("tools.search.listed", relative(path))))
        path != null -> withMono(t("tools.search.in", MONO, relative(path)), pattern)
        else -> withMono(t("tools.search", MONO), pattern)
      }
    }
    ToolKind.WEB -> listOf(LabelPart.Plain(when {
      entry.title != null -> entry.title
      entry.name == Tool.WEB_SEARCH -> t("tools.web.search", input.string("query") ?: "")
      else -> t("tools.web.fetch", input.string("url") ?: "")
    }))
    ToolKind.AGENT -> listOf(LabelPart.Plain(t("tools.agent", input.string("description")
      ?: input.string("subagent_type") ?: t("tools.agent.task"))))
    ToolKind.TODO -> {
      val todos = todos(input)
      listOf(LabelPart.Plain(t("tools.todo", todos.count { it.status == "completed" }, todos.size)))
    }
    ToolKind.QUESTION -> {
      val first = Json.objects(input, "questions").firstOrNull()?.string("question") ?: ""
      listOf(LabelPart.Plain(t("tools.question", first)))
    }
    ToolKind.PLAN -> listOf(LabelPart.Plain(t("tools.plan")))
    ToolKind.OTHER -> listOf(LabelPart.Plain(when {
      entry.title != null -> entry.title
      entry.name.startsWith("mcp__") -> entry.name.split("__").drop(1).joinToString(" · ")
      else -> {
        val first = input.entrySet().firstOrNull { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
        entry.name + (if (first != null) " · " + firstLine(first.value.asString) else "")
      }
    }))
  }
}

/** E.g. "Read 2 files, ran a command, and used 1 tool". */
fun groupSummary(group: WorkGroup): String {
  val counts = group.entries.groupingBy { it.category }.eachCount()
  val parts = ArrayList<String>()
  counts[ToolKind.READ]?.let { parts.add(t("timeline.group.read", it)) }
  counts[ToolKind.EDIT]?.let { parts.add(t("timeline.group.edit", it)) }
  counts[ToolKind.COMMAND]?.let { parts.add(t("timeline.group.command", it)) }
  counts[ToolKind.SEARCH]?.let { parts.add(t("timeline.group.search", it)) }
  counts[ToolKind.WEB]?.let { parts.add(t("timeline.group.web", it)) }
  val others = listOf(ToolKind.AGENT, ToolKind.TODO, ToolKind.QUESTION, ToolKind.PLAN, ToolKind.OTHER)
    .sumOf { counts[it] ?: 0 }
  if (others > 0) {
    parts.add(t("timeline.group.other", others))
  }
  val summary = conjunction(parts)
  return summary.replaceFirstChar { it.uppercase() }
}

data class Todo(val content: String, val status: String, val activeForm: String?)

fun todos(input: JsonObject): List<Todo> {
  return Json.objects(input, "todos").map {
    Todo(it.string("content") ?: "", it.string("status") ?: "pending", it.string("activeForm"))
  }
}

fun prettyJson(input: JsonObject): String = PRETTY.toJson(input)

fun approvalLabel(prompt: PermissionPrompt): String = when (prompt.category) {
  ToolKind.COMMAND -> t("tools.approval.command")
  ToolKind.EDIT -> t("tools.approval.edit")
  ToolKind.READ -> t("tools.approval.read")
  ToolKind.WEB -> t("tools.approval.web")
  else -> t("tools.approval", prompt.label ?: prompt.toolName)
}

fun ThreadState.approvalDetail(prompt: PermissionPrompt): String {
  val input = prompt.input
  input.string("command")?.let { return it }
  input.string("file_path")?.let { return this.relative(it) }
  input.string("url")?.let { return it }
  input.string("pattern")?.let { return it }
  return prettyJson(input)
}
