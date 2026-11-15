package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import io.firebite.tako.ui.t
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.foundation.code.highlighting.NoOpCodeHighlighter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.CustomBlock as CommonMarkBlock
import org.commonmark.ext.gfm.tables.TableBlock as CommonMarkTable
import org.jetbrains.jewel.markdown.MarkdownBlock.BlockQuote
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.MarkdownBlock.CustomBlock
import org.jetbrains.jewel.markdown.MarkdownBlock.Paragraph
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownStyling
import org.jetbrains.jewel.markdown.extensions.MarkdownBlockProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownBlockRendererExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughRendererExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.processing.readInlineMarkdown
import org.jetbrains.jewel.markdown.rendering.DefaultInlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.Text

/** A path, optionally with a line: `src/a.ts`, `C:\x\b.java:12`. */
private val PATH = Regex("""^((?:[A-Za-z]:)?[\w.\-/\\]*[\w\-]\.[A-Za-z0-9]{1,10})(?::(\d+))?$""")
/** Has a folder, or looks like a bare file name, so version numbers like `1.2` are no paths. */
private val PATH_LIKE = Regex("""[/\\]|^[\w\-]+\.[A-Za-z]{1,6}$""")

/** Marks inline code, whose boxes paragraphs draw behind their text. */
private const val CODE_TAG = "tako.code"

/** Whether the blocks being rendered sit inside a quote. */
private val LocalInQuote = staticCompositionLocalOf { false }

/** What code in the agent's answers can do in the IDE. */
interface CodeActions {
  fun copy(text: String)

  fun insert(text: String)

  fun open(path: String, line: Int)

  fun openUrl(url: String)
}

/** Inline code that names a file opens it. */
private class ChatInlineRenderer(extensions: List<MarkdownRendererExtension>, private val actions: CodeActions) :
  DefaultInlineMarkdownRenderer(extensions) {
  override fun AnnotatedString.Builder.renderInlineCode(node: InlineMarkdown.Code, styling: InlinesStyling,
    enabled: Boolean, currentTextStyle: TextStyle) {
    val match = PATH.matchEntire(node.content.trim())
    val isPath = match != null && PATH_LIKE.containsMatchIn(match.groupValues[1])
    if (match != null && isPath) {
      val path = match.groupValues[1]
      val line = match.groupValues[2].toIntOrNull() ?: 1
      this.pushLink(LinkAnnotation.Clickable(path, TextLinkStyles(hoveredStyle = SpanStyle(color = styling.link.color))) {
        this@ChatInlineRenderer.actions.open(path, line)
      })
    }
    val padding = styling.textStyle.fontSize * 0.35f
    if (this.length > 0) {
      this.addStyle(SpanStyle(letterSpacing = padding), this.length - 1, this.length)
    }
    this.pushStringAnnotation(CODE_TAG, "")
    this.pushStyle(styling.inlineCode)
    this.append(node.content)
    if (node.content.isNotEmpty()) {
      this.addStyle(SpanStyle(letterSpacing = padding), this.length - 1, this.length)
    }
    this.pop()
    this.pop()
    if (isPath) {
      this.pop()
    }
  }

  /** Drops inline HTML tags such as `<kbd>`, keeping the text between them. */
  override fun AnnotatedString.Builder.renderInlineHtml(node: InlineMarkdown.HtmlInline, styling: InlinesStyling,
    enabled: Boolean, currentTextStyle: TextStyle) {
  }

  /** Keeps single line breaks, as GitHub does in comments. */
  override fun AnnotatedString.Builder.renderSoftLineBreak(styling: InlinesStyling, currentTextStyle: TextStyle) {
    this.append('\n')
  }
}

/** Code blocks get a head with their language and buttons to copy them or insert them at the editor's caret. */
private class ChatBlockRenderer(
  styling: MarkdownStyling,
  extensions: List<MarkdownRendererExtension>,
  private val actions: CodeActions,
  private val quoted: MarkdownStyling.Paragraph,
) : DefaultMarkdownBlockRenderer(styling, extensions, ChatInlineRenderer(extensions, actions)) {
  @Composable
  override fun RenderFencedCodeBlock(block: FencedCodeBlock, styling: MarkdownStyling.Code.Fenced, enabled: Boolean,
    modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val (interaction, hovered) = rememberHover()
    Column(modifier.fillMaxWidth().clip(shape).background(colors.editorBg).border(1.dp, colors.divider, shape)
      .hoverable(interaction)) {
      Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(block.language?.ifEmpty { null } ?: "text", color = colors.muted, style = monoTinyStyle)
        Spacer(Modifier.weight(1f))
        Row(Modifier.alpha(if (hovered) 1f else 0.6f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          IconBtn(Icons.insert, t("markdown.insert"), iconSize = 14.dp) {
            this@ChatBlockRenderer.actions.insert(block.content)
          }
          IconBtn(Icons.copy, t("common.copy"), iconSize = 14.dp) { this@ChatBlockRenderer.actions.copy(block.content) }
        }
      }
      super.RenderFencedCodeBlock(block, styling, enabled, Modifier)
    }
  }

  @Composable
  override fun RenderBlockQuote(block: BlockQuote, styling: MarkdownStyling.BlockQuote, enabled: Boolean,
    onUrlClick: (String) -> Unit, modifier: Modifier) {
    val bar = colors.softBorder
    val rem = textStyle.fontSize.value.dp
    Column(modifier.drawBehind { this.drawRoundRect(bar, size = Size(2.dp.toPx(), this.size.height),
      cornerRadius = CornerRadius(1.dp.toPx())) }.padding(start = rem * 0.8f),
      verticalArrangement = Arrangement.spacedBy(this.rootStyling.blockVerticalSpacing)) {
      CompositionLocalProvider(LocalInQuote provides true) {
        this@ChatBlockRenderer.RenderBlocks(block.children, enabled, onUrlClick, Modifier)
      }
    }
  }

  @Composable
  override fun RenderParagraph(block: Paragraph, styling: MarkdownStyling.Paragraph, enabled: Boolean,
    onUrlClick: (String) -> Unit, onTextLayout: (TextLayoutResult) -> Unit, modifier: Modifier, overflow: TextOverflow,
    softWrap: Boolean, maxLines: Int) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fontSize = styling.inlinesStyling.textStyle.fontSize
    val fill = colors.card
    val border = colors.softBorder
    super.RenderParagraph(block, if (LocalInQuote.current) this.quoted else styling, enabled, onUrlClick, {
      layout = it
      onTextLayout(it)
    }, modifier.drawBehind { layout?.let { this.drawCodeBoxes(it, fontSize, fill, border) } }, overflow, softWrap,
      maxLines)
  }
}

/** Boxes inline code like the page's CSS did: one box per line the code wraps onto, padded at its ends. */
private fun DrawScope.drawCodeBoxes(layout: TextLayoutResult, fontSize: TextUnit, fill: Color, border: Color) {
  val text = layout.layoutInput.text
  val codes = text.getStringAnnotations(CODE_TAG, 0, text.length)
  if (codes.isEmpty()) {
    return
  }
  val codeSize = fontSize.toPx() * 0.86f
  val paddingX = fontSize.toPx() * 0.35f
  val paddingY = fontSize.toPx() * 0.05f
  val radius = CornerRadius(6.dp.toPx())
  val stroke = 1.dp.toPx()
  for (code in codes) {
    var start = code.start
    while (start < code.end) {
      val line = layout.getLineForOffset(start)
      val end = minOf(code.end, layout.getLineEnd(line, visibleEnd = true))
      if (end > start) {
        val left = layout.getBoundingBox(start).left - if (start == code.start) paddingX else 0f
        val right = layout.getBoundingBox(end - 1).right
        val baseline = layout.getLineBaseline(line)
        val top = baseline - codeSize * 1.02f - paddingY
        val bottom = baseline + codeSize * 0.3f + paddingY
        this.drawRoundRect(fill, Offset(left, top), Size(right - left, bottom - top), radius)
        this.drawRoundRect(border, Offset(left + stroke / 2, top + stroke / 2),
          Size(right - left - stroke, bottom - top - stroke), radius, style = Stroke(stroke))
      }
      start = maxOf(layout.getLineEnd(line), start + 1)
    }
  }
}

/** A GFM table, its head row first. Jewel's own table blocks are internal to it. */
private data class ChatTable(val rows: List<List<ChatTableCell>>, val columns: Int) : CustomBlock

private data class ChatTableCell(val content: List<InlineMarkdown>, val alignment: TableCell.Alignment?)

/** Parses GFM tables into [ChatTable]s, with Jewel's table syntax. */
private object ChatTableProcessor : MarkdownProcessorExtension, MarkdownBlockProcessorExtension {
  override val parserExtension = GitHubTableProcessorExtension.parserExtension
  override val textRendererExtension = GitHubTableProcessorExtension.textRendererExtension
  override val blockProcessorExtension: MarkdownBlockProcessorExtension
    get() = this
  override val allowsMergingWithNextBlock: Boolean
    get() = true

  override fun canProcess(block: CommonMarkBlock): Boolean = block is CommonMarkTable

  override fun processMarkdownBlock(block: CommonMarkBlock, processor: MarkdownProcessor): CustomBlock? {
    val rows = ArrayList<List<ChatTableCell>>()
    var section = block.firstChild
    while (section != null) {
      var row = section.firstChild
      while (row != null) {
        if (row is TableRow) {
          val cells = ArrayList<ChatTableCell>()
          var cell = row.firstChild
          while (cell != null) {
            if (cell is TableCell) {
              cells.add(ChatTableCell(cell.readInlineMarkdown(processor), cell.alignment))
            }
            cell = cell.next
          }
          rows.add(cells)
        }
        row = row.next
      }
      section = section.next
    }
    return if (rows.isEmpty()) null else ChatTable(rows, rows.maxOf { it.size })
  }
}

/** Tables as the page drew them: small text, a rule under each row, as wide as their content and scrolling sideways. */
@JvmDefaultWithoutCompatibility
private class ChatTables(
  private val cell: MarkdownStyling.Paragraph,
  private val header: MarkdownStyling.Paragraph,
  private val rule: Color,
) : MarkdownRendererExtension, MarkdownBlockRendererExtension {
  override val blockRenderer: MarkdownBlockRendererExtension
    get() = this

  override fun canRender(block: CustomBlock): Boolean = block is ChatTable

  @Composable
  override fun RenderCustomBlock(block: CustomBlock, blockRenderer: MarkdownBlockRenderer,
    inlineRenderer: InlineMarkdownRenderer, enabled: Boolean, modifier: Modifier, onUrlClick: (String) -> Unit) {
    val table = block as ChatTable
    val columns = table.columns
    BoxWithConstraints(modifier) {
      val width = this.maxWidth
      TableGrid(columns, width, Modifier.horizontalScroll(rememberScrollState())) {
        for ((index, row) in table.rows.withIndex()) {
          for (column in 0 until columns) {
            val cell = row.getOrNull(column)
            val alignment = when (cell?.alignment) {
              TableCell.Alignment.CENTER -> Alignment.TopCenter
              TableCell.Alignment.RIGHT -> Alignment.TopEnd
              else -> Alignment.TopStart
            }
            Box(Modifier.drawBehind {
              val stroke = 1.dp.toPx()
              val y = this.size.height - stroke / 2
              this.drawLine(this@ChatTables.rule, Offset(0f, y), Offset(this.size.width, y), stroke)
            }.padding(end = 10.dp, top = 4.dp, bottom = 4.dp), contentAlignment = alignment) {
              if (cell != null) {
                blockRenderer.RenderParagraph(Paragraph(cell.content),
                  if (index == 0) this@ChatTables.header else this@ChatTables.cell, enabled, onUrlClick, Modifier)
              }
            }
          }
        }
      }
    }
  }
}

/** Lays cells out row by row; columns take their content's width and shrink towards their longest word when space runs out. */
@Composable
private fun TableGrid(columns: Int, available: Dp, modifier: Modifier, content: @Composable () -> Unit) {
  Layout(content, modifier) { measurables, _ ->
    val minWidths = IntArray(columns)
    val maxWidths = IntArray(columns)
    for ((index, measurable) in measurables.withIndex()) {
      val column = index % columns
      minWidths[column] = maxOf(minWidths[column], measurable.minIntrinsicWidth(Constraints.Infinity))
      maxWidths[column] = maxOf(maxWidths[column], measurable.maxIntrinsicWidth(Constraints.Infinity))
    }
    val room = available.roundToPx()
    val minSum = minWidths.sum()
    val maxSum = maxWidths.sum()
    val widths = when {
      maxSum <= room -> maxWidths
      minSum >= room -> minWidths
      else -> IntArray(columns) {
        minWidths[it] + ((maxWidths[it] - minWidths[it]).toLong() * (room - minSum) / (maxSum - minSum)).toInt()
      }
    }
    val rows = measurables.size / columns
    val heights = IntArray(rows)
    for ((index, measurable) in measurables.withIndex()) {
      val row = index / columns
      heights[row] = maxOf(heights[row], measurable.maxIntrinsicHeight(widths[index % columns]))
    }
    val placeables = measurables.mapIndexed { index, measurable ->
      measurable.measure(Constraints.fixed(widths[index % columns], heights[index / columns]))
    }
    layout(widths.sum(), heights.sum()) {
      var y = 0
      for (row in 0 until rows) {
        var x = 0
        for (column in 0 until columns) {
          placeables[row * columns + column].place(x, y)
          x += widths[column]
        }
        y += heights[row]
      }
    }
  }
}

/** How the chat renders markdown: the IDE's styling and code highlighting, sized for a conversation. */
class ChatMarkdown(val processor: MarkdownProcessor, val renderer: MarkdownBlockRenderer)

@Composable
fun rememberChatMarkdown(actions: CodeActions): ChatMarkdown {
  val base = textStyle.copy(color = colors.body, lineHeight = textStyle.fontSize * 1.625)
  val editor = JewelTheme.editorTextStyle
  val theme = JewelTheme.instanceUuid
  val c = colors
  return remember(theme, base, editor) {
    val rem = base.fontSize.value.dp
    val padding = PaddingValues(top = rem * 0.6f)
    fun level(scale: Double) = InlinesStyling.create(base.copy(fontSize = base.fontSize * scale,
      lineHeight = base.fontSize * scale * 1.3, fontWeight = FontWeight.SemiBold, color = c.fg))
    val heading = MarkdownStyling.Heading.create(
      base,
      h1 = MarkdownStyling.Heading.H1.create(base, level(1.25), 0.dp, Color.Unspecified, 0.dp, padding),
      h2 = MarkdownStyling.Heading.H2.create(base, level(1.125), 0.dp, Color.Unspecified, 0.dp, padding),
      h3 = MarkdownStyling.Heading.H3.create(base, level(1.0), 0.dp, Color.Unspecified, 0.dp, padding),
      h4 = MarkdownStyling.Heading.H4.create(base, level(0.875), 0.dp, Color.Unspecified, 0.dp, padding),
      h5 = MarkdownStyling.Heading.H5.create(base, level(0.875), 0.dp, Color.Unspecified, 0.dp, padding),
      h6 = MarkdownStyling.Heading.H6.create(base, level(0.875), 0.dp, Color.Unspecified, 0.dp, padding),
    )
    fun inlines(style: TextStyle) = InlinesStyling.create(style,
      inlineCode = SpanStyle(fontFamily = editor.fontFamily, fontSize = 0.86.em),
      strongEmphasis = SpanStyle(fontWeight = FontWeight.SemiBold, color = c.fg))
    val inlines = inlines(base)
    val marker = rem * 0.85f
    val gap = rem * 0.4f
    val list = MarkdownStyling.List.create(base,
      ordered = MarkdownStyling.List.Ordered.create(numberStyle = base, numberContentGap = gap, numberMinWidth = marker,
        itemVerticalSpacing = rem * 0.65f, itemVerticalSpacingTight = rem * 0.25f, padding = PaddingValues()),
      unordered = MarkdownStyling.List.Unordered.create(bulletStyle = base, bulletContentGap = gap,
        itemVerticalSpacing = rem * 0.65f, itemVerticalSpacingTight = rem * 0.25f, padding = PaddingValues(),
        markerMinWidth = marker))
    val code = MarkdownStyling.Code.create(fenced = MarkdownStyling.Code.Fenced.create(
      textStyle = editor.copy(lineHeight = editor.fontSize * 1.45),
      padding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), background = Color.Transparent))
    val styling = MarkdownStyling.create(base, inlinesStyling = inlines, blockVerticalSpacing = rem * 0.65f,
      paragraph = MarkdownStyling.Paragraph.create(inlines), heading = heading, code = code, list = list,
      thematicBreak = MarkdownStyling.ThematicBreak.create(padding = PaddingValues(vertical = rem * 0.35f),
        lineWidth = 1.dp, lineColor = c.divider))
    val small = (base.fontSize.value - 2).sp
    val cells = base.copy(fontSize = small, lineHeight = small * 1.625)
    val tables = ChatTables(MarkdownStyling.Paragraph.create(inlines(cells)),
      MarkdownStyling.Paragraph.create(inlines(cells.copy(color = c.fg, fontWeight = FontWeight.SemiBold))), c.divider)
    val extensions = listOf(tables, GitHubStrikethroughRendererExtension)
    val processor = MarkdownProcessor(listOf(ChatTableProcessor, GitHubStrikethroughProcessorExtension(),
      AutolinkProcessorExtension))
    val quoted = MarkdownStyling.Paragraph.create(inlines(base.copy(color = c.body.copy(alpha = c.body.alpha * 0.75f))))
    ChatMarkdown(processor, ChatBlockRenderer(styling, extensions, actions, quoted))
  }
}

/** Gives markdown the IDE's code highlighting. */
@Composable
fun ProvideChatMarkdown(project: Project, content: @Composable () -> Unit) {
  ProvideMarkdownStyling(project, content = content)
}

/**
 * The agent's markdown, selectable.
 *
 * @param isFinal highlights code; skipped while text still streams in
 */
@Composable
fun MarkdownText(markdown: ChatMarkdown, text: String, isFinal: Boolean, actions: CodeActions,
  modifier: Modifier = Modifier) {
  val highlighter = if (isFinal) LocalCodeHighlighter.current else NoOpCodeHighlighter
  // Older Jewel copies the renderer into a plain DefaultMarkdownBlockRenderer unless the local styling is its own.
  CompositionLocalProvider(LocalCodeHighlighter provides highlighter,
    LocalMarkdownStyling provides markdown.renderer.rootStyling) {
    Markdown(text, modifier, selectable = true, onUrlClick = actions::openUrl, processor = markdown.processor,
      blockRenderer = markdown.renderer)
  }
}
