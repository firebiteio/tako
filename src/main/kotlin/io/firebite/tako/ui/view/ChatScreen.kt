package io.firebite.tako.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import io.firebite.tako.ide.editor.IdeFiles
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.t
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

/** Below this width the sidebar overlays the chat and closes after picking a thread. */
val NARROW = 640.dp
/** The width conversations and the composer keep to on wide windows, CSS's 46rem. */
val LANE: Dp
  @Composable @ReadOnlyComposable get() = (46 * textStyle.fontSize.value).dp

@Composable
fun ChatScreen(workspace: Workspace) {
  ProvideTakoColors {
    ProvideChatMarkdown(workspace.project) {
      val actions = remember(workspace) { WorkspaceCodeActions(workspace) }
      val markdown = rememberChatMarkdown(actions)
      LaunchedEffect(workspace) {
        while (true) {
          delay(1000)
          workspace.now = System.currentTimeMillis()
        }
      }
      BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg).onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && event.key == Key.Escape && closeOverlay(workspace)
      }) {
        val width = this.maxWidth
        val narrow = width <= NARROW
        Row(Modifier.fillMaxSize()) {
          if (!narrow) {
            if (workspace.sidebarOpen) {
              Sidebar(workspace, narrow = false, modifier = Modifier.width(248.dp).fillMaxHeight())
            } else {
              SidebarRail(workspace, Modifier.width(44.dp).fillMaxHeight())
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
          }
          Main(workspace, markdown, actions, width, Modifier.weight(1f))
        }
        if (workspace.sidebarOpen && narrow) {
          Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, null) { workspace.setSidebar(false) })
          Sidebar(workspace, narrow = true,
            modifier = Modifier.width(minOf(280.dp, this.maxWidth * 0.85f)).fillMaxHeight().shadow(16.dp)
              .background(colors.bg))
        }
        workspace.lightbox?.let { image ->
          Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).padding(24.dp)
            .clickable(remember { MutableInteractionSource() }, null) { workspace.lightbox = null },
            contentAlignment = Alignment.Center) {
            bitmapOf(image)?.let {
              Image(it, image.name, Modifier.shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit)
            }
          }
        }
      }
    }
  }
}

/** Esc closes what floats over the chat, the topmost first. @return whether something closed */
private fun closeOverlay(workspace: Workspace): Boolean {
  when {
    workspace.lightbox != null -> workspace.lightbox = null
    workspace.contextPanelOpen -> workspace.contextPanelOpen = false
    workspace.taskPanel != null -> workspace.taskPanel = null
    workspace.shownBench != null -> workspace.shownBench = null
    workspace.usageOpen -> workspace.usageOpen = false
    workspace.searching -> workspace.closeSearch()
    else -> return false
  }
  return true
}

@Composable
private fun Main(workspace: Workspace, markdown: ChatMarkdown, actions: CodeActions, windowWidth: Dp,
  modifier: Modifier) {
  Box(modifier.fillMaxHeight()) {
    Column(Modifier.fillMaxSize()) {
      Header(workspace, windowWidth)
      val thread = workspace.active
      if (thread != null) {
        ThreadView(thread, markdown, actions, Modifier.weight(1f))
      }
    }
    val alerts = workspace.alerts + (workspace.active?.alerts ?: emptyList())
    if (alerts.isNotEmpty()) {
      val rem = textStyle.fontSize.value
      Column(Modifier.align(Alignment.TopCenter).padding(top = 52.dp, start = rem.dp, end = rem.dp)
        .widthIn(max = (48 * rem).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (alert in alerts) {
          AlertCard(alert) {
            workspace.alerts.remove(alert)
            workspace.active?.alerts?.remove(alert)
          }
        }
      }
    }
    workspace.taskPanel?.let { card ->
      Box(Modifier.align(Alignment.TopEnd).padding(top = 44.dp).fillMaxHeight().widthIn(max = 440.dp)
        .fillMaxWidth()) {
        TaskPanel(card, markdown, actions)
      }
    }
    if (workspace.usageOpen) {
      UsageView(workspace, Modifier.fillMaxSize())
    }
    if (workspace.shownBench != null) {
      BenchView(workspace, Modifier.fillMaxSize())
    }
  }
}

/** Code actions on the shown thread. */
private class WorkspaceCodeActions(private val workspace: Workspace) : CodeActions {
  override fun copy(text: String) {
    copyText(text)
  }

  override fun insert(text: String) {
    IdeFiles.insertAtCaret(this.workspace.project, text)
  }

  override fun open(path: String, line: Int) {
    IdeFiles.open(this.workspace.project, path, line)
  }

  override fun openUrl(url: String) {
    browse(url)
  }
}

fun copyText(text: String) {
  CopyPasteManager.getInstance().setContents(StringSelection(text))
}

/** Opens web links in the browser; other schemes are ignored. */
fun browse(url: String) {
  if (url.startsWith("https://") || url.startsWith("http://")) {
    BrowserUtil.browse(url)
  }
}

/** One entry of a popup menu. */
class MenuEntry(
  val label: String,
  val description: String? = null,
  val icon: IconKey? = null,
  val checked: Boolean = false,
  val run: () -> Unit,
)

/** A menu next to what opened it, below unless only above has room, with an optional title. */
@Composable
fun MenuPopup(entries: List<MenuEntry>, title: String? = null, alignment: Alignment.Horizontal = Alignment.Start,
  onDismiss: () -> Unit) {
  val density = LocalDensity.current
  val position = remember(alignment, density) {
    MenuPosition(alignment == Alignment.End, with(density) { 6.dp.roundToPx() }, with(density) { 8.dp.roundToPx() })
  }
  Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.shadow(12.dp, shape).clip(shape).background(colors.card).border(1.dp, colors.softBorder, shape)
      .widthIn(min = 220.dp, max = 420.dp).width(IntrinsicSize.Max).verticalScroll(rememberScrollState())
      .padding(4.dp)) {
      if (title != null) {
        Text(title, Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), color = colors.muted,
          style = tinyStyle.copy(fontWeight = FontWeight.SemiBold))
      }
      if (entries.isEmpty()) {
        Text(t("menu.empty"), Modifier.padding(8.dp), color = colors.muted, style = smallStyle)
      }
      for (entry in entries) {
        Row(Modifier.fillMaxWidth().hoverClick {
          onDismiss()
          entry.run()
        }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (entry.icon != null) {
            Ico(entry.icon, size = 14.dp, tint = colors.muted)
          }
          Column(Modifier.weight(1f)) {
            Text(entry.label, color = colors.fg, style = smallStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1,
              overflow = TextOverflow.Ellipsis)
            if (!entry.description.isNullOrEmpty()) {
              Text(entry.description, color = colors.muted, style = tinyStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            }
          }
          if (entry.checked) {
            Ico(Icons.check, size = 14.dp, tint = colors.primary)
          }
        }
      }
    }
  }
}

/** Below the anchor unless above has more room, aligned to its start or end, and kept inside the window. */
private class MenuPosition(private val alignEnd: Boolean, private val gap: Int, private val margin: Int) :
  PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
    popupContentSize: IntSize): IntOffset {
    val height = popupContentSize.height
    val above = anchorBounds.top - this.gap - this.margin
    val below = windowSize.height - anchorBounds.bottom - this.gap - this.margin
    val width = popupContentSize.width
    val fitsBelow = height <= below || below >= above
    val y = if (fitsBelow) anchorBounds.bottom + this.gap else anchorBounds.top - this.gap - height
    val x = if (this.alignEnd) anchorBounds.right - width else anchorBounds.left
    return IntOffset(x.coerceIn(this.margin, maxOf(this.margin, windowSize.width - width - this.margin)),
      y.coerceIn(this.margin, maxOf(this.margin, windowSize.height - height - this.margin)))
  }
}
