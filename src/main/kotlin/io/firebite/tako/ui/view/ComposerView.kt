package io.firebite.tako.ui.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.intellij.ide.PasteProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import io.firebite.tako.agent.AgentInfo
import io.firebite.tako.agent.PermissionPrompt
import io.firebite.tako.agent.PriceRatios
import io.firebite.tako.ide.context.Images
import io.firebite.tako.ide.project.FileIndex
import io.firebite.tako.ui.countdown
import io.firebite.tako.ui.money
import io.firebite.tako.ui.state.CACHE_TTL_1H
import io.firebite.tako.ui.state.CacheKind
import io.firebite.tako.ui.state.CacheState
import io.firebite.tako.ui.state.PromptCache
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.canPlanRuns
import io.firebite.tako.ui.state.cacheState
import io.firebite.tako.ui.state.chooseOption
import io.firebite.tako.ui.state.advanceQuestion
import io.firebite.tako.ui.state.contextNumbers
import io.firebite.tako.ui.state.RunPlan
import io.firebite.tako.ui.state.runLabel
import io.firebite.tako.ui.state.specOf
import io.firebite.tako.ui.state.setRunCount
import io.firebite.tako.ui.state.modelLabel
import io.firebite.tako.ui.state.modelsOf
import io.firebite.tako.ui.state.restoreStash
import io.firebite.tako.ui.state.submit
import io.firebite.tako.ui.state.submitAndBranch
import io.firebite.tako.ui.state.toggleStash
import io.firebite.tako.ui.state.typed
import io.firebite.tako.ui.t
import io.firebite.tako.ui.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.icon.IconKey
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.io.File
import java.net.URI
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.KeyStroke
import kotlin.math.max

private const val MAX_SLASH_ITEMS = 50
/** Waits for typing to pause before asking the IDE for files. */
private const val MENTION_DEBOUNCE_MILLIS = 80L
private val RUN_COUNTS = listOf(2, 3, 4, 5, 6, 8)
private val MENTION = Regex("""(^|[\s(])@("[^"]*|[^\s@"]*)$""")
private val SLASH = Regex("""^/(\S*)$""")
/** The cold cache timer's icon (Lucide). */

/** Reasoning effort levels by their usual names; other levels show as named. */
private fun effortLabel(level: String): String = when (level) {
  "low" -> t("composer.effort.low")
  "medium" -> t("composer.effort.medium")
  "high" -> t("composer.effort.high")
  "xhigh" -> t("composer.effort.xhigh")
  "max" -> t("composer.effort.max")
  else -> level
}

private fun effortDescription(level: String): String? = when (level) {
  "low" -> t("composer.effort.low.description")
  "medium" -> t("composer.effort.medium.description")
  "high" -> t("composer.effort.high.description")
  "xhigh" -> t("composer.effort.xhigh.description")
  "max" -> t("composer.effort.max.description")
  else -> null
}

/** The `@` mention being typed: where it is and what it asks for. */
private data class Mention(val start: Int, val end: Int, val query: String)

/** The suggestions over the composer: slash commands or files for an `@` mention. */
private class Suggestions {
  var commands by mutableStateOf<List<AgentInfo.Command>>(emptyList())
  var files by mutableStateOf<List<FileIndex.Match>>(emptyList())
  var isMention by mutableStateOf(false)
  var index by mutableIntStateOf(0)
  var mention: Mention? = null

  val size: Int
    get() = if (this.isMention) this.files.size else this.commands.size

  fun hide() {
    this.commands = emptyList()
    this.files = emptyList()
    this.index = 0
  }
}

/** Opens above its anchor, inset from its left edge, moving right only as far as the window allows. */
private class AboveAnchor(private val gap: Int, private val inset: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
    popupContentSize: IntSize): IntOffset {
    val x = (anchorBounds.left + this.inset).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
    return IntOffset(x, max(0, anchorBounds.top - this.gap - popupContentSize.height))
  }
}

/** Pastes images and image files as attachments, everything else as text. */
private class ComposerPaste(private val thread: ThreadState) : PasteProvider {
  override fun performPaste(dataContext: DataContext) {
    val clipboard = CopyPasteManager.getInstance()
    if (clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)) {
      @Suppress("UNCHECKED_CAST")
      val files = clipboard.getContents<List<File>>(DataFlavor.javaFileListFlavor) ?: emptyList()
      val images = files.filter { Images.isImageFile(it.name) }.map { it.toPath() }
      if (images.isNotEmpty()) {
        this.thread.controller?.attachFiles(images)
        return
      }
    }
    if (!clipboard.areDataFlavorsAvailable(DataFlavor.stringFlavor) &&
      clipboard.areDataFlavorsAvailable(DataFlavor.imageFlavor)) {
      this.thread.controller?.pasteImage()
      return
    }
    val text = clipboard.getContents<String>(DataFlavor.stringFlavor) ?: return
    this.thread.input.edit {
      val start = this.selection.min
      this.replace(start, this.selection.max, text)
      this.selection = TextRange(start + text.length)
    }
  }

  override fun isPastePossible(dataContext: DataContext): Boolean = true

  override fun isPasteEnabled(dataContext: DataContext): Boolean = true

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** The thread whose composer has focus. */
private val COMPOSER: DataKey<ThreadState> = DataKey.create("tako.composer")

/**
 * Ctrl+S (Cmd+S on macOS) in the composer stashes the draft or brings one back. Registered on the chat panel, so it
 * comes before the IDE's Save All, and only enabled while the composer has focus.
 */
class StashAction : DumbAwareAction() {
  /** Takes Ctrl+S (Cmd+S on macOS) on the component. */
  fun register(component: JComponent, parent: Disposable) {
    val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
    this.registerCustomShortcutSet(CustomShortcutSet(keyStroke), component, parent)
  }

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = event.getData(COMPOSER) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    event.getData(COMPOSER)?.toggleStash()
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** What sits under the conversation: running tasks, the waiting prompt's drawer and the composer. */
@Composable
fun Dock(thread: ThreadState, markdown: ChatMarkdown, actions: CodeActions) {
  Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
    contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = LANE).fillMaxWidth()) {
      TasksTray(thread)
      thread.approvals.firstOrNull()?.let { Drawer(thread, it) }
      Composer(thread)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(thread: ThreadState) {
  val workspace = thread.workspace
  val suggestions = remember(thread) { Suggestions() }
  val focus = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  var dragging by remember { mutableStateOf(false) }
  var width by remember { mutableIntStateOf(0) }
  var top by remember { mutableFloatStateOf(0f) }
  var cacheIconOnly by remember { mutableStateOf(false) }
  val paste = remember(thread) { ComposerPaste(thread) }
  LaunchedEffect(workspace.focusComposer, thread) {
    try {
      focus.requestFocus()
    } catch (ignored: IllegalStateException) {
    }
  }
  LaunchedEffect(thread) {
    snapshotFlow { thread.input.text.toString() to thread.input.selection }.collectLatest { (text, selection) ->
      updateSlash(thread, suggestions, text)
      updateMention(thread, suggestions, text, selection)
    }
  }
  val dropTarget = remember(thread) {
    object : DragAndDropTarget {
      override fun onDrop(event: DragAndDropEvent): Boolean {
        dragging = false
        val data = event.dragData() as? DragData.FilesList ?: return false
        val paths = data.readFiles().map { Path.of(URI(it)) }.filter { Images.isImageFile(it.toString()) }
        if (paths.isEmpty()) {
          return false
        }
        thread.controller?.attachFiles(paths)
        return true
      }

      override fun onEntered(event: DragAndDropEvent) {
        dragging = true
      }

      override fun onExited(event: DragAndDropEvent) {
        dragging = false
      }

      override fun onEnded(event: DragAndDropEvent) {
        dragging = false
      }
    }
  }
  val shape = RoundedCornerShape(22.dp)
  val border = when {
    dragging -> colors.primary
    focused -> lerpColor(colors.softBorder, colors.primary, 0.45f)
    else -> colors.softBorder
  }
  val ring = colors.primary.copy(alpha = 0.25f)
  val density = LocalDensity.current
  val narrow = width > 0 && with(density) { width.toDp() } < NARROW_COMPOSER
  Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }
    .onGloballyPositioned { top = it.positionInWindow().y }) {
    Column(Modifier.fillMaxWidth().drawBehind {
      if (dragging) {
        val spread = 3.dp.toPx()
        this.drawRoundRect(ring, Offset(-spread / 2, -spread / 2),
          Size(this.size.width + spread, this.size.height + spread), CornerRadius(22.dp.toPx() + spread / 2),
          style = Stroke(spread))
      }
    }.shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
      .clip(shape).background(lerpColor(colors.bg, colors.card, 0.92f)).border(1.dp, border, shape)
      .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = dropTarget)) {
      if (thread.images.isNotEmpty()) {
        FlowRow(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for (pending in thread.images.toList()) {
            ImageThumb(pending.image, 64.dp, { workspace.lightbox = pending.image }) { thread.images.remove(pending) }
          }
        }
      }
      if (thread.contexts.isNotEmpty()) {
        FlowRow(Modifier.padding(start = 14.dp, end = 14.dp, top = if (thread.images.isEmpty()) 12.dp else 8.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          for (item in thread.contexts.toList()) {
            MentionChip(item) { thread.contexts.remove(item) }
          }
        }
      }
      val inputStyle = textStyle.copy(lineHeight = 1.6.em)
      TextArea(thread.input, Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 208.dp).focusRequester(focus)
        .onFocusChanged { focused = it.isFocused }
        .provideData {
          this.set(PlatformDataKeys.PASTE_PROVIDER.name, paste)
          this.set(COMPOSER.name, thread)
        }
        .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(thread, suggestions, event) },
        undecorated = true, textStyle = inputStyle, lineLimits = TextFieldLineLimits.MultiLine(),
        placeholder = { Text(placeholder(thread), color = colors.muted.copy(alpha = 0.75f), style = inputStyle) },
        decorationBoxModifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
      Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Controls(thread, narrow, cacheIconOnly, { cacheIconOnly = it }, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          CacheTimer(thread, cacheIconOnly)
          ContextMeter(thread)
          val info = thread.info
          if (info == null || info.capabilities.images) {
            IconBtn(Icons.attach, t("composer.attach")) { thread.controller?.pickImages() }
          }
          SendButton(thread)
        }
      }
    }
    val maxHeight = with(density) { maxOf(80.dp, top.toDp() - 14.dp) }
    if (suggestions.size > 0) {
      SuggestionPopup(thread, suggestions, with(density) { width.toDp() - 16.dp }, maxHeight)
    }
    if (workspace.contextPanelOpen) {
      ContextPanel(thread, maxHeight)
    }
    thread.stashMenu?.let { entries ->
      MenuPopup(entries.map { entry -> MenuEntry(entry.label()) { thread.restoreStash(entry) } }, t("stash.menu")) {
        thread.stashMenu = null
        workspace.focusComposer++
      }
    }
  }
}

private fun placeholder(thread: ThreadState): String {
  val approval = thread.approvals.firstOrNull() ?: return t("composer.placeholder")
  return when (approval.prompt.kind) {
    PermissionPrompt.Kind.QUESTION -> t("approvals.question.placeholder")
    PermissionPrompt.Kind.PLAN -> t("approvals.plan.placeholder")
    else -> t("approvals.placeholder")
  }
}

private fun updateSlash(thread: ThreadState, suggestions: Suggestions, text: String) {
  val match = SLASH.matchEntire(text)
  if (match == null || thread.commands.isEmpty()) {
    if (!suggestions.isMention) {
      suggestions.hide()
    }
    return
  }
  val query = match.groupValues[1].lowercase()
  val items = thread.commands.filter { it.name.lowercase().contains(query) }
    .sortedBy { !it.name.lowercase().startsWith(query) }.take(MAX_SLASH_ITEMS)
  suggestions.isMention = false
  suggestions.commands = items
  suggestions.index = suggestions.index.coerceAtMost(max(0, items.size - 1))
}

/** An `@` before the caret asks the IDE for matching files. */
private suspend fun updateMention(thread: ThreadState, suggestions: Suggestions, text: String, selection: TextRange) {
  val before = text.substring(0, selection.start.coerceAtMost(text.length))
  val match = MENTION.find(before)
  if (match == null) {
    if (suggestions.isMention) {
      suggestions.hide()
      suggestions.isMention = false
    }
    suggestions.mention = null
    return
  }
  val raw = match.groupValues[2]
  val query = raw.removePrefix("\"")
  suggestions.mention = Mention(before.length - raw.length - 1, selection.start, query)
  delay(MENTION_DEBOUNCE_MILLIS)
  val controller = thread.controller ?: return
  val files = withContext(Dispatchers.IO) { controller.findFiles(query) }
  suggestions.isMention = true
  suggestions.files = files
  suggestions.index = suggestions.index.coerceAtMost(max(0, files.size - 1))
}

private fun chooseSuggestion(thread: ThreadState, suggestions: Suggestions, index: Int) {
  if (suggestions.isMention) {
    val item = suggestions.files.getOrNull(index) ?: return
    val mention = suggestions.mention ?: return
    val path = item.path + if (item.isDirectory) "/" else ""
    val token = if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"
    val insert = token + if (item.isDirectory) "" else " "
    thread.input.edit {
      this.replace(mention.start, mention.end.coerceAtMost(this.length), insert)
      this.selection = TextRange(mention.start + insert.length)
    }
    suggestions.hide()
    suggestions.isMention = false
    return
  }
  val command = suggestions.commands.getOrNull(index) ?: return
  thread.input.setTextAndPlaceCursorAtEnd("/" + command.name + " ")
  suggestions.hide()
}

private fun onKey(thread: ThreadState, suggestions: Suggestions, event: androidx.compose.ui.input.key.KeyEvent): Boolean {
  val count = suggestions.size
  if (count > 0 && (event.key == Key.DirectionDown || event.key == Key.DirectionUp)) {
    suggestions.index = (suggestions.index + (if (event.key == Key.DirectionDown) 1 else -1) + count) % count
    return true
  }
  if (count > 0 && (event.key == Key.Tab || (event.key == Key.Enter && !event.isShiftPressed))) {
    chooseSuggestion(thread, suggestions, suggestions.index)
    return true
  }
  val digit = event.key.keyCode.toInt() - Key.One.keyCode.toInt()
  if (thread.question != null && thread.input.text.isEmpty() && digit in 0..8 && !event.isCtrlPressed) {
    if (thread.chooseOption(digit)) {
      thread.advanceQuestion()
    }
    return true
  }
  if (event.key == Key.Enter && event.isShiftPressed && (event.isCtrlPressed || event.isMetaPressed)) {
    suggestions.hide()
    thread.submitAndBranch()
    return true
  }
  if (event.key == Key.Enter && !event.isShiftPressed) {
    suggestions.hide()
    thread.submit()
    return true
  }
  if (event.key == Key.Escape) {
    if (count > 0) {
      suggestions.hide()
      return true
    }
    if (thread.busy) {
      thread.controller?.interrupt()
      return true
    }
  }
  return false
}

@Composable
private fun SuggestionPopup(thread: ThreadState, suggestions: Suggestions, width: Dp, maxHeight: Dp) {
  val density = LocalDensity.current
  val provider = remember(density) { with(density) { AboveAnchor(6.dp.roundToPx(), 8.dp.roundToPx()) } }
  PopupContainer(onDismissRequest = { suggestions.hide() }, horizontalAlignment = Alignment.Start,
    modifier = Modifier.width(width), style = menuPopupStyle(), popupProperties = PopupProperties(focusable = false),
    popupPositionProvider = provider) {
    Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(4.dp)) {
      for (index in 0 until suggestions.size) {
        val active = index == suggestions.index
        Row(Modifier.fillMaxWidth().hoverClick(selected = active) { chooseSuggestion(thread, suggestions, index) }
          .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (suggestions.isMention) {
            val item = suggestions.files[index]
            Ico(if (item.isDirectory) Icons.folder else Icons.file, size = 14.dp, tint = colors.muted)
            val slash = item.path.lastIndexOf('/')
            Column(Modifier.weight(1f)) {
              Text(item.path.substring(slash + 1) + if (item.isDirectory) "/" else "", color = colors.fg,
                style = smallStyle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              if (slash > 0) {
                Text(item.path.substring(0, slash), color = colors.muted, style = tinyStyle, maxLines = 1,
                  overflow = TextOverflow.StartEllipsis)
              }
            }
          } else {
            val command = suggestions.commands[index]
            Column(Modifier.weight(1f)) {
              Text("/" + command.name + if (command.hint.isNotEmpty()) "  " + command.hint else "", color = colors.fg,
                style = smallStyle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              if (command.description.isNotEmpty()) {
                Text(command.description, color = colors.muted, style = tinyStyle, maxLines = 1,
                  overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }
  }
}

/** One of the composer's pickers. */
private class Control(val key: String, val icon: IconKey, val label: String, val tooltip: String,
  val chevron: Boolean, val highlighted: Boolean = false, val menu: () -> List<MenuEntry>, val menuTitle: String? = null)

/** Controls whose label gives way to just the icon when the composer is too narrow, least needed first. */
private val SHRINKING = listOf(CACHE_TIMER, "effort", "mode", "model", "agent", "runs")

/** The cache timer's key among the shrinking controls; it sits beside the send button but gives way in turn. */
private const val CACHE_TIMER = "cache"

/** Below this width the controls show no labels, only their icons and chevrons. */
private val NARROW_COMPOSER = 396.dp

/**
 * The composer's pickers. Labels drop to icons until the row fits, the cache timer's included: [cacheIconOnly] is
 * what it shows now, so the space it frees counts as the row's.
 */
@Composable
private fun Controls(thread: ThreadState, narrow: Boolean, cacheIconOnly: Boolean, onCacheIconOnly: (Boolean) -> Unit,
  modifier: Modifier) {
  val workspace = thread.workspace
  val controls = ArrayList<Control>()
  if (thread.canPlanRuns) {
    val count = max(1, thread.runs.size)
    controls.add(Control("runs", Icons.workflow, if (count > 1) t("runs.count", count) else t("runs.single"),
      if (count > 1) t("runs.title.on", count) else t("runs.title"), true, highlighted = count > 1,
      menu = { runsMenu(thread) }, menuTitle = t("runs.menu")))
  }
  if (workspace.agents.size > 1 && thread.canChangeAgent) {
    controls.add(Control("agent", Icons.agent, thread.agentName, t("composer.agent"), true, menu = {
      workspace.agents.map { agent ->
        MenuEntry(agent.name, checked = agent.id == thread.agent) {
          if (agent.id != thread.agent) {
            workspace.service.setAgent(thread.id, agent.id)
          }
        }
      }
    }, menuTitle = t("composer.agent")))
  }
  val model = thread.currentModel
  controls.add(Control("model", Icons.model, model?.displayName?.ifEmpty { null } ?: model?.value ?: t("composer.model"),
    model?.let { listOfNotNull(it.displayName.ifEmpty { it.value }, it.description).joinToString(": ") }
      ?: t("composer.model"), true, menu = {
      thread.models.map { option ->
        MenuEntry(option.displayName.ifEmpty { option.value }, option.description, checked = option.value == thread.model) {
          thread.model = option.value
          thread.controller?.setModel(option.value)
        }
      }
    }, menuTitle = t("composer.model")))
  if (model != null && model.efforts.isNotEmpty()) {
    val effort = thread.effort?.let { effortLabel(it) }
    controls.add(Control("effort", Icons.effort, effort ?: t("composer.effort"),
      if (effort != null) t("composer.effort.title.level", effort) else t("composer.effort.title"), true, menu = {
        model.efforts.map { level ->
          MenuEntry(effortLabel(level), effortDescription(level), checked = level == thread.effort) {
            thread.effort = level
            thread.controller?.setEffort(level)
          }
        }
      }, menuTitle = t("composer.effort")))
  }
  val modes = thread.info?.modes ?: emptyList()
  if (modes.isNotEmpty()) {
    val mode = modes.firstOrNull { it.value == thread.mode }
    controls.add(Control("mode", Icons.mode(thread.mode), mode?.label ?: thread.mode,
      if (mode != null) mode.label + ": " + mode.description else t("composer.mode"), true, menu = {
        modes.map { option ->
          MenuEntry(option.label, option.description, Icons.mode(option.value), option.value == thread.mode) {
            thread.mode = option.value
            thread.controller?.setMode(option.value)
          }
        }
      }, menuTitle = t("composer.permissions")))
  }

  val measurer = rememberTextMeasurer()
  val style = smallStyle.copy(fontWeight = FontWeight.Medium)
  val density = LocalDensity.current
  val cacheText = thread.cache?.let { cacheLabel(thread.cacheState(it, workspace.now)) }
  BoxWithConstraints(modifier) {
    val labels = HashMap<String, Float>()
    for (control in controls) {
      val label = if (narrow) 0f else measurer.measure(control.label, style).size.width + with(density) { 6.dp.toPx() }
      labels[control.key] = label + if (control.chevron) with(density) { 18.dp.toPx() } else 0f
    }
    if (cacheText != null) {
      labels[CACHE_TIMER] = measurer.measure(cacheText, tinyStyle).size.width + with(density) { 4.dp.toPx() }
    }
    val freed = if (cacheIconOnly) 0f else labels[CACHE_TIMER] ?: 0f
    val available = with(density) { this@BoxWithConstraints.maxWidth.toPx() } + freed
    val base = with(density) { (controls.size * (16 + 14) + (controls.size - 1) * 9).dp.toPx() }
    var total = base + labels.values.sum()
    val iconOnly = HashSet<String>()
    for (key in SHRINKING) {
      if (total <= available) {
        break
      }
      labels[key]?.let {
        iconOnly.add(key)
        total -= it
      }
    }
    val hidesCache = CACHE_TIMER in iconOnly
    SideEffect {
      if (hidesCache != cacheIconOnly) {
        onCacheIconOnly(hidesCache)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      controls.forEachIndexed { index, control ->
        if (index > 0) {
          Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(16.dp).background(colors.divider))
        }
        ControlButton(control, control.key in iconOnly, narrow, style) {
          val run = workspace.runMenu
          if (control.key == "runs" && run != null) {
            RunMenu(thread, run) { workspace.runMenu = null }
          }
        }
      }
    }
  }
}

@Composable
private fun ControlButton(control: Control, iconOnly: Boolean, narrow: Boolean, style: TextStyle,
  popups: @Composable () -> Unit) {
  var open by remember { mutableStateOf(false) }
  val (interaction, hovered) = rememberHover()
  val color = when {
    control.highlighted -> colors.primary
    hovered -> colors.fg
    else -> colors.muted
  }
  Box {
    Tip(control.tooltip) {
      Row(Modifier.height(28.dp).hoverClick(RoundedCornerShape(10.dp), interaction = interaction) { open = true }
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(control.icon, size = 14.dp, tint = color)
        if (!iconOnly) {
          if (!narrow) {
            Text(control.label, Modifier.padding(start = 6.dp), color = color, style = style, maxLines = 1)
          }
          if (control.chevron) {
            Ico(Icons.chevronDown, Modifier.padding(start = 4.dp), size = 14.dp, tint = colors.faint)
          }
        }
      }
    }
    if (open) {
      MenuPopup(control.menu(), control.menuTitle) { open = false }
    }
    popups()
  }
}

private fun runsMenu(thread: ThreadState): List<MenuEntry> {
  val count = thread.runs.size
  val entries = ArrayList<MenuEntry>()
  entries.add(MenuEntry(t("runs.single"), t("runs.single.description"), checked = count <= 1) { thread.setRunCount(1) })
  for (option in RUN_COUNTS) {
    entries.add(MenuEntry(t("runs.count", option), checked = option == count) { thread.setRunCount(option) })
  }
  thread.runs.forEachIndexed { index, plan ->
    entries.add(MenuEntry(t("runs.run", index + 1, thread.runLabel(plan)), t("runs.run.change"), Icons.agent) {
      thread.workspace.runMenu = index
    })
  }
  return entries
}

/** Picks one run's agent and model: the models known for each agent, or its default. */
@Composable
private fun RunMenu(thread: ThreadState, index: Int, onDismiss: () -> Unit) {
  val plan = thread.runs.getOrNull(index) ?: return
  val current = thread.specOf(plan)
  val entries = ArrayList<MenuEntry>()
  for (agent in thread.workspace.agents) {
    val models = thread.modelsOf(agent.id)
    val choices = if (models.isEmpty()) listOf("") else models.map { it.value }
    for (model in choices) {
      entries.add(MenuEntry(agent.name + " · " + thread.modelLabel(agent.id, model),
        checked = current.agent == agent.id && current.model == model) {
        thread.runs[index] = RunPlan(agent.id, model)
      })
    }
  }
  MenuPopup(entries, t("runs.run.menu", index + 1), onDismiss = onDismiss)
}

@Composable
private fun SendButton(thread: ThreadState) {
  val hasText = thread.typed.isNotEmpty() || thread.images.isNotEmpty()
  val isStop = thread.busy && !hasText && thread.approvals.isEmpty()
  val enabled = isStop || hasText || (thread.question?.selected?.isNotEmpty() == true)
  val tip = when {
    isStop -> t("composer.stop")
    thread.busy -> t("composer.queue.title")
    else -> t("composer.send.title")
  }
  val (interaction, hovered) = rememberHover()
  val scale by animateFloatAsState(if (hovered && enabled) 1.05f else 1f, tween(120))
  Tip(tip) {
    Box(Modifier.size(32.dp).scale(scale).alpha(if (enabled) 1f else 0.4f).clip(CircleShape)
      .background(if (isStop) colors.del.copy(alpha = 0.9f) else colors.primary).hoverable(interaction)
      .clickable(enabled) { thread.submit() }, contentAlignment = Alignment.Center) {
      if (isStop) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.5.dp)).background(Color.White))
      } else {
        SendArrow(colors.primaryFg)
      }
    }
  }
}

/** The send button's arrow, drawn like the old page's 14px one rather than a Lucide icon. */
@Composable
private fun SendArrow(color: Color) {
  Canvas(Modifier.size(14.dp)) {
    val unit = this.size.width / 14f
    val arrow = androidx.compose.ui.graphics.Path()
    arrow.moveTo(7 * unit, 11.5f * unit)
    arrow.lineTo(7 * unit, 2.5f * unit)
    arrow.moveTo(3 * unit, 6.5f * unit)
    arrow.lineTo(7 * unit, 2.5f * unit)
    arrow.lineTo(11 * unit, 6.5f * unit)
    this.drawPath(arrow, color, style = Stroke(1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

@Composable
private fun ContextMeter(thread: ThreadState) {
  val numbers = thread.contextNumbers() ?: return
  val share = numbers.used.toDouble() / (numbers.threshold ?: numbers.max)
  val (interaction, hovered) = rememberHover()
  val fill = when {
    share >= 0.95 -> colors.del
    share >= 0.8 -> colors.warn
    else -> colors.muted
  }
  val color = if (share < 0.8 && hovered) colors.fg else fill
  val used = tokens(numbers.used)
  val max = tokens(numbers.max)
  val tip = numbers.threshold?.let { t("context.meter.threshold", used, max, numbers.percent, tokens(it)) }
    ?: t("context.meter", used, max, numbers.percent)
  Tip(tip) {
    Row(Modifier.height(28.dp).hoverClick(interaction = interaction) { toggleContextPanel(thread) }
      .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      val track = colors.divider
      Canvas(Modifier.size(18.dp)) {
        val stroke = 2.5.dp.toPx()
        val inset = 2.dp.toPx()
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        this.drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        this.drawArc(fill, -90f, 360f * numbers.percent / 100f, false, Offset(inset, inset), arcSize,
          style = Stroke(stroke, cap = StrokeCap.Round))
      }
      Text(numbers.percent.toString() + "%", color = color, style = tinyStyle)
    }
  }
}

fun toggleContextPanel(thread: ThreadState) {
  val workspace = thread.workspace
  workspace.contextPanelOpen = !workspace.contextPanelOpen
  if (workspace.contextPanelOpen) {
    thread.controller?.refreshContext()
  }
}

private fun cacheLabel(state: CacheState): String = when (state.kind) {
  CacheKind.BUSY -> t("context.timer.busy")
  CacheKind.WARM -> countdown(state.remaining)
  CacheKind.COLD -> t("context.timer.cold")
}

/** What the cached prompt costs the next message, warm against cold; null while busy or without a price. */
fun cacheCostText(thread: ThreadState, cache: PromptCache, state: CacheState): String? {
  val rate = thread.inputRate
  if (rate == null || state.kind == CacheKind.BUSY) {
    return null
  }
  val price = cache.tokens * rate
  val warm = money(price * PriceRatios.CACHE_READ)
  val cold = money(price * if (cache.ttl == CACHE_TTL_1H) PriceRatios.CACHE_WRITE_1H else PriceRatios.CACHE_WRITE_5M)
  return if (state.kind == CacheKind.WARM) t("context.cache.cost.warm", warm, cold) else
    t("context.cache.cost.cold", cold, warm)
}

@Composable
private fun CacheTimer(thread: ThreadState, iconOnly: Boolean) {
  val cache = thread.cache ?: return
  val state = thread.cacheState(cache, thread.workspace.now)
  val color = when (state.kind) {
    CacheKind.BUSY -> colors.primary
    CacheKind.WARM -> if (state.remaining < 60000) colors.warn else colors.add
    CacheKind.COLD -> colors.muted
  }
  val ttl = if (cache.ttl == CACHE_TTL_1H) t("context.timer.hour") else t("context.timer.minutes")
  val tip = if (state.kind == CacheKind.COLD) t("context.timer.expired", ttl, tokens(cache.tokens)) else
    t("context.timer.warm", tokens(cache.tokens), ttl)
  Tip(listOfNotNull(tip, cacheCostText(thread, cache, state)).joinToString("\n")) {
    Row(Modifier.height(28.dp).alpha(if (state.kind == CacheKind.COLD) 0.8f else 1f)
      .hoverClick { toggleContextPanel(thread) }.padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Ico(if (state.kind == CacheKind.COLD) Icons.cold else Icons.timer, size = 12.dp, tint = color)
      if (!iconOnly) {
        Text(cacheLabel(state), color = color, style = tinyStyle, maxLines = 1)
      }
    }
  }
}
