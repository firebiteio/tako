package io.firebite.tako.ui.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.firebite.tako.agent.ToolKind
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey

/** The chat's colors, taken from the IDE theme and recomputed when it changes. */
class TakoColors(
  val bg: Color,
  val fg: Color,
  val body: Color,
  val muted: Color,
  val faint: Color,
  val divider: Color,
  val softBorder: Color,
  val hover: Color,
  val card: Color,
  val message: Color,
  val editorBg: Color,
  val link: Color,
  val primary: Color,
  val primaryFg: Color,
  val warn: Color,
  val add: Color,
  val del: Color,
  val added: Color,
  val removed: Color,
  val merged: Color,
  /** Categorical chart colors in fixed order, validated for color-vision deficiency on light and dark surfaces. */
  val series: List<Color>,
)

private val SERIES_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF4A3AA7,
  0xFFE34948)
private val SERIES_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9,
  0xFFE66767)

val LocalTakoColors = staticCompositionLocalOf<TakoColors> { error("No Tako colors") }

val colors: TakoColors
  @Composable @ReadOnlyComposable get() = LocalTakoColors.current

@Composable
fun ProvideTakoColors(content: @Composable () -> Unit) {
  val isDark = JewelTheme.isDark
  val theme = JewelTheme.instanceUuid
  val global = JewelTheme.globalColors
  val takoColors = remember(theme, isDark) {
    val bg = global.panelBackground
    val fg = global.text.normal
    val muted = UIUtil.getContextHelpForeground().toComposeColor()
    val white = Color.White
    val black = Color.Black
    val softBorder = if (isDark) lerp(bg, white, 0.09f) else lerp(bg, black, 0.1f)
    TakoColors(
      bg = bg,
      fg = fg,
      body = fg.copy(alpha = 0.86f),
      muted = muted,
      faint = muted.copy(alpha = 0.75f),
      divider = softBorder.copy(alpha = 0.7f),
      softBorder = softBorder,
      hover = if (isDark) lerp(bg, white, 0.07f) else lerp(bg, black, 0.05f),
      card = if (isDark) lerp(bg, white, 0.04f) else white,
      message = if (isDark) lerp(bg, white, 0.06f) else lerp(bg, black, 0.05f),
      editorBg = EditorColorsManager.getInstance().globalScheme.defaultBackground.toComposeColor(),
      link = JBUI.CurrentTheme.Link.Foreground.ENABLED.toComposeColor(),
      primary = JBUI.CurrentTheme.Button.defaultButtonColorStart().toComposeColor(),
      primaryFg = JBUI.CurrentTheme.Button.defaultButtonForeground().toComposeColor(),
      warn = Color(if (isDark) 0xFFD9A343 else 0xFFA86B00),
      add = Color(if (isDark) 0xFF4ADE80 else 0xFF16A34A),
      del = Color(if (isDark) 0xFFF87171 else 0xFFDC2626),
      added = Color(if (isDark) 0xFF294436 else 0xFFE6F4EA),
      removed = Color(if (isDark) 0xFF4A2B2B else 0xFFFCE8E6),
      merged = Color(0xFFA371F7),
      series = (if (isDark) SERIES_DARK else SERIES_LIGHT).map { Color(it) },
    )
  }
  val ide = JewelTheme.defaultTextStyle
  CompositionLocalProvider(LocalTakoColors provides takoColors, LocalBaseSize provides ide.fontSize,
    LocalTextStyle provides ide.sized(ide.fontSize.value + 1), content = content)
}

private val LocalBaseSize = staticCompositionLocalOf<TextUnit> { error("No base size") }

/** The IDE's label font size, which the chat's text sizes step from. */
val baseSize: TextUnit
  @Composable @ReadOnlyComposable get() = LocalBaseSize.current

/** Body text, a step above the IDE's labels; the default style inside the chat. */
val textStyle: TextStyle
  @Composable @ReadOnlyComposable get() = JewelTheme.defaultTextStyle

val smallStyle: TextStyle
  @Composable @ReadOnlyComposable get() = JewelTheme.defaultTextStyle.sized(baseSize.value - 1)

val tinyStyle: TextStyle
  @Composable @ReadOnlyComposable get() = JewelTheme.defaultTextStyle.sized(baseSize.value - 2)

/** The IDE's label size, for tooltips. */
val labelStyle: TextStyle
  @Composable @ReadOnlyComposable get() = JewelTheme.defaultTextStyle.sized(baseSize.value)

val monoStyle: TextStyle
  @Composable @ReadOnlyComposable get() = JewelTheme.editorTextStyle.sized(baseSize.value - 1)

val monoTinyStyle: TextStyle
  @Composable @ReadOnlyComposable get() = JewelTheme.editorTextStyle.sized(baseSize.value - 2)

/** This style at another size, its line height scaled along. */
private fun TextStyle.sized(size: Float): TextStyle {
  val scaled = this.lineHeight.isSp && this.fontSize.isSp
  return this.copy(fontSize = size.sp,
    lineHeight = if (scaled) (this.lineHeight.value * size / this.fontSize.value).sp else this.lineHeight)
}

/** Lucide icons (lucide-static 1.51.0, ISC License, https://lucide.dev) for what the chat shows. */
object Icons {
  val sidebar: IconKey = lucide("panel-left")
  val attach: IconKey = lucide("paperclip")
  val usage: IconKey = lucide("chart-column")
  val history: IconKey = lucide("history")
  val addProject: IconKey = lucide("folder-plus")
  val add: IconKey = lucide("plus")
  val scratchpad: IconKey = lucide("notebook-pen")
  val arrowDown: IconKey = lucide("arrow-down")
  val close: IconKey = lucide("x")
  val closeSmall: IconKey = lucide("x")
  val chevronRight: IconKey = lucide("chevron-right")
  val chevronDown: IconKey = lucide("chevron-down")
  val copy: IconKey = lucide("copy")
  val insert: IconKey = lucide("square-pen")
  val diff: IconKey = lucide("file-diff")
  val file: IconKey = lucide("file-text")
  val folder: IconKey = lucide("folder")
  val folderOpen: IconKey = lucide("folder-open")
  val error: IconKey = lucide("circle-alert")
  val warning: IconKey = lucide("circle-alert")
  val success: IconKey = lucide("circle-check")
  val check: IconKey = lucide("check")
  val question: IconKey = lucide("message-circle")
  val thread: IconKey = lucide("message-circle")
  val plan: IconKey = lucide("book-open")
  val approval: IconKey = lucide("shield")
  val group: IconKey = lucide("hammer")
  val agent: IconKey = lucide("bot")
  val shell: IconKey = lucide("square-terminal")
  val workflow: IconKey = lucide("workflow")
  val archive: IconKey = lucide("archive")
  val restore: IconKey = lucide("archive-restore")
  val remove: IconKey = lucide("folder-minus")
  val worktree: IconKey = lucide("folder-git-2")
  val branch: IconKey = lucide("git-branch")
  val fork: IconKey = lucide("git-fork")
  val search: IconKey = lucide("search")
  val pullRequest: IconKey = lucide("git-pull-request")
  val merged: IconKey = lucide("git-merge")
  val eye: IconKey = lucide("eye")
  val refresh: IconKey = lucide("refresh-cw")
  val more: IconKey = lucide("ellipsis")
  val model: IconKey = lucide("sparkles")
  val effort: IconKey = lucide("brain")
  val selection: IconKey = lucide("file-code")
  val timer: IconKey = lucide("timer")
  val cold: IconKey = lucide("snowflake")
  val loader: IconKey = lucide("loader-circle")
  val queued: IconKey = lucide("circle-dashed")
  val todoDone: IconKey = lucide("square-check-big")
  val todoActive: IconKey = lucide("circle-dot")
  val todoPending: IconKey = lucide("circle")

  fun category(kind: ToolKind): IconKey = when (kind) {
    ToolKind.READ -> lucide("eye")
    ToolKind.EDIT -> this.insert
    ToolKind.COMMAND -> lucide("terminal")
    ToolKind.SEARCH -> lucide("search")
    ToolKind.WEB -> lucide("globe")
    ToolKind.AGENT -> this.agent
    ToolKind.TODO -> lucide("list-todo")
    ToolKind.QUESTION -> this.question
    ToolKind.PLAN -> this.plan
    ToolKind.OTHER -> lucide("wrench")
  }

  /** Icons of the modes the agents offer; other modes show a lock. */
  fun mode(mode: String): IconKey = when (mode) {
    "acceptEdits" -> lucide("pen-line")
    "plan" -> lucide("pencil-ruler")
    "auto" -> this.model
    "dontAsk" -> this.approval
    "bypassPermissions", "full-access" -> lucide("lock-open")
    else -> lucide("lock")
  }

  private fun lucide(name: String): IconKey = PathIconKey("icons/lucide/$name.svg", Icons::class.java)
}
