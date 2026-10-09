package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import io.firebite.tako.ui.countdown
import io.firebite.tako.ui.state.CACHE_TTL_1H
import io.firebite.tako.ui.state.CacheKind
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.cacheState
import io.firebite.tako.ui.state.contextNumbers
import io.firebite.tako.ui.state.sendCommand
import io.firebite.tako.ui.t
import io.firebite.tako.ui.textLocale
import io.firebite.tako.ui.tokens
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.PopupContainerColors
import org.jetbrains.jewel.ui.component.styling.PopupContainerMetrics
import org.jetbrains.jewel.ui.component.styling.PopupContainerStyle
import org.jetbrains.jewel.ui.theme.popupContainerStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Chart series per context category, so each keeps its color. */
private val CATEGORY_SLOTS = mapOf("System prompt" to 0, "System tools" to 1, "MCP tools" to 2, "Custom agents" to 3,
  "Memory files" to 4, "Skills" to 5, "Messages" to 6)
private const val DEFERRED_SUFFIX = " (deferred)"

/** Opens above its anchor, its right edge at the anchor's, keeping a margin to the window's sides. */
private class AboveEnd(private val gap: Int, private val margin: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
    popupContentSize: IntSize): IntOffset {
    val x = (anchorBounds.right - popupContentSize.width)
      .coerceIn(this.margin, max(this.margin, windowSize.width - popupContentSize.width - this.margin))
    return IntOffset(x, max(0, anchorBounds.top - this.gap - popupContentSize.height))
  }
}

/** The old page's menus: card background, soft border and 12px corners. */
@Composable
fun menuPopupStyle(): PopupContainerStyle {
  val base = JewelTheme.popupContainerStyle
  val metrics = base.metrics
  return PopupContainerStyle(base.isDark,
    PopupContainerColors(background = colors.card, border = colors.softBorder, shadow = base.colors.shadow),
    PopupContainerMetrics(cornerSize = CornerSize(12.dp), menuMargin = metrics.menuMargin,
      contentPadding = metrics.contentPadding, offset = metrics.offset, shadowSize = metrics.shadowSize,
      borderWidth = 1.dp))
}

@Composable
private fun slotColor(name: String): Color = colors.series[CATEGORY_SLOTS[name.removeSuffix(DEFERRED_SUFFIX)] ?: 7]

/** The context window's breakdown and the prompt cache's state, over the composer's right edge. */
@Composable
fun ContextPanel(thread: ThreadState, maxHeight: Dp) {
  val workspace = thread.workspace
  val density = LocalDensity.current
  val provider = remember(density) { with(density) { AboveEnd(6.dp.roundToPx(), 8.dp.roundToPx()) } }
  PopupContainer(onDismissRequest = { workspace.contextPanelOpen = false }, horizontalAlignment = Alignment.End,
    modifier = Modifier.width(360.dp), style = menuPopupStyle(), popupProperties = PopupProperties(focusable = true),
    popupPositionProvider = provider) {
    Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 12.dp)) {
      Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t("context.title"), Modifier.weight(1f), color = colors.fg, fontWeight = FontWeight.Bold)
        IconBtn(Icons.refresh, t("context.refresh"), iconSize = 14.dp) { thread.controller?.refreshContext() }
        IconBtn(Icons.closeSmall, t("common.close"), iconSize = 14.dp) { workspace.contextPanelOpen = false }
      }
      ContextBreakdown(thread)
    }
  }
}

@Composable
private fun ContextBreakdown(thread: ThreadState) {
  val numbers = thread.contextNumbers()
  if (numbers == null) {
    Note(t("context.no.data", thread.agentName))
    return
  }
  val usage = thread.contextUsage
  val small = smallStyle
  Text(t("context.total", tokens(numbers.used), tokens(numbers.max), numbers.percent), color = colors.fg,
    style = small.copy(fontSize = small.fontSize * 1.15f), fontWeight = FontWeight.SemiBold)
  numbers.threshold?.let {
    val percent = (it * 100.0 / numbers.max).roundToInt()
    Note(t("context.threshold", tokens(it), percent, tokens(max(0, it - numbers.used))))
  }
  val categories = usage?.categories ?: emptyList()
  val used = categories.filter { it.kind == "used" && it.tokens > 0 }
  BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp).height(10.dp)) {
    val width = this.maxWidth
    Row(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(colors.divider),
      horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      if (used.isEmpty()) {
        Box(Modifier.width(width * (numbers.percent / 100f)).fillMaxHeight().background(colors.series[0]))
      }
      for (category in used) {
        Tip(category.name + ": " + tokens(category.tokens)) {
          Box(Modifier.width(maxOf(2.dp, width * (category.tokens.toFloat() / numbers.max))).fillMaxHeight()
            .background(slotColor(category.name)))
        }
      }
    }
    numbers.threshold?.let {
      Tip(t("context.threshold.marker")) {
        Box(Modifier.offset(x = width * (it.toFloat() / numbers.max)).width(2.dp).requiredHeight(16.dp).alpha(0.6f)
          .clip(RoundedCornerShape(1.dp)).background(colors.fg))
      }
    }
  }
  if (usage != null) {
    if (thread.busy) {
      Note(t("context.stale"))
    }
    for (category in used) {
      ContextRow(slotColor(category.name), category.name, category.tokens, numbers.max)
    }
    categories.firstOrNull { it.kind == "free" }?.let { ContextRow(null, t("context.free"), it.tokens, numbers.max, true) }
    val deferred = categories.filter { it.kind == "deferred" && it.tokens > 0 }
    if (deferred.isNotEmpty()) {
      Note(t("context.deferred", deferred.joinToString(", ") {
        it.name.removeSuffix(DEFERRED_SUFFIX) + " " + tokens(it.tokens)
      }))
    }
    val messages = usage.messages
    if (messages != null) {
      val parts = listOf(t("context.messages.tool.results") to messages.toolResultTokens,
        t("context.messages.tool.calls") to messages.toolCallTokens, t("context.messages.user") to messages.userMessageTokens,
        t("context.messages.assistant") to messages.assistantMessageTokens,
        t("context.messages.attachments") to messages.attachmentTokens,
        t("context.messages.other") to messages.unattributedTokens + messages.redirectedContextTokens)
        .filter { it.second > 0 }
      if (parts.isNotEmpty()) {
        Section(t("context.messages"))
        for ((name, value) in parts) {
          ContextRow(null, name, value, numbers.max, true)
        }
      }
    }
    if (usage.memoryFiles.isNotEmpty()) {
      Section(t("context.memory.files"))
      for (file in usage.memoryFiles) {
        Tip(file.path) {
          ContextRow(null, file.path.substringAfterLast('/').substringAfterLast('\\') + " (" + (file.type ?: "") + ")",
            file.tokens, numbers.max, true)
        }
      }
    }
    if (usage.mcpServers.isNotEmpty()) {
      Section(t("context.mcp.servers"))
      for (server in usage.mcpServers) {
        Tip(t("context.mcp.server.title", tokens(server.loadedTokens), tokens(server.deferredTokens))) {
          ContextRow(null, t("context.mcp.server", server.name, server.tools), server.loadedTokens, numbers.max, true)
        }
      }
    }
  }
  thread.cache?.let { cache ->
    val state = thread.cacheState(cache, thread.workspace.now)
    val ttl = if (cache.ttl == CACHE_TTL_1H) t("context.cache.hour") else t("context.cache.minutes")
    val size = tokens(cache.tokens)
    Section(t("context.cache"))
    val note = when (state.kind) {
      CacheKind.BUSY -> t("context.cache.busy", ttl, size)
      CacheKind.WARM -> t("context.cache.warm", countdown(state.remaining), ttl, size)
      CacheKind.COLD -> t("context.cache.expired", size)
    }
    Note(listOfNotNull(note, cacheCostText(thread, cache, state)).joinToString(" "))
  }
  if (thread.info?.capabilities?.compact != false) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
      OutlineButton(t("context.compact")) {
        thread.workspace.contextPanelOpen = false
        thread.sendCommand("/compact")
      }
    }
  }
}

@Composable
private fun Note(text: String) {
  Text(text, Modifier.padding(vertical = 4.dp), color = colors.muted, style = tinyStyle.copy(lineHeight = 1.5.em))
}

@Composable
private fun Section(title: String) {
  Text(title.uppercase(textLocale), Modifier.padding(top = 10.dp, bottom = 2.dp), color = colors.muted,
    style = tinyStyle.copy(letterSpacing = 0.04.em), fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ContextRow(color: Color?, name: String, value: Long, max: Long, muted: Boolean = false) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color ?: Color.Transparent))
    Text(name, Modifier.weight(1f), color = if (muted) colors.body else colors.fg, style = smallStyle, maxLines = 1,
      overflow = TextOverflow.Ellipsis)
    Text(tokens(value), color = colors.muted, style = smallStyle)
    Text(if (max > 0) String.format(Locale.ROOT, "%.1f%%", value * 100.0 / max) else "", Modifier.width(44.dp),
      color = colors.muted, style = smallStyle, textAlign = androidx.compose.ui.text.style.TextAlign.End)
  }
}
