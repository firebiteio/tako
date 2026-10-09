package io.firebite.tako.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.firebite.tako.ui.bridge.PageMessage
import io.firebite.tako.ui.bridge.PageOp
import io.firebite.tako.ui.money
import io.firebite.tako.ui.shortDay
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.t
import io.firebite.tako.ui.tokens
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import java.time.LocalDate

/** The chart spans the transcripts' retention, at least a month and at most a quarter. */
private const val MIN_CHART_DAYS = 30
private const val MAX_CHART_DAYS = 90
private const val DEFAULT_RETENTION_DAYS = 30

/** A view over the whole chat: a head with a title and close button, then scrolling content in the lane. */
@Composable
fun OverlayView(icon: IconKey, title: String, onClose: () -> Unit, modifier: Modifier, actions: @Composable () -> Unit = {},
  content: @Composable () -> Unit) {
  VerticallyScrollableContainer(modifier.background(colors.bg)) {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 24.dp), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.widthIn(max = LANE).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Ico(icon, tint = colors.fg)
          Text(title, Modifier.weight(1f), color = colors.fg, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
          actions()
          IconBtn(Icons.close, t("common.close"), iconSize = 14.dp, onClick = onClose)
        }
        Rule(Modifier.padding(bottom = 16.dp))
        content()
      }
    }
  }
}

@Composable
fun ViewNote(text: String) {
  Text(text, Modifier.padding(bottom = 6.dp), color = colors.muted, style = smallStyle,
    lineHeight = smallStyle.fontSize * 1.5f)
}

/** Cost and tokens of every session of the workspace's folders. */
@Composable
fun UsageView(workspace: Workspace, modifier: Modifier) {
  val title = if (workspace.projectName.isNotEmpty()) t("usage.title.project", workspace.projectName) else
    t("usage.title")
  OverlayView(Icons.usage, title, { workspace.usageOpen = false }, modifier) {
    val usage = workspace.usage
    if (usage == null) {
      ViewNote(t("usage.reading"))
      return@OverlayView
    }
    ViewNote(t("usage.note.prices"))
    val retention = if (usage.retentionDays > 0) usage.retentionDays else DEFAULT_RETENTION_DAYS
    val oldest = usage.oldestDay?.let { LocalDate.parse(it) }
    ViewNote(if (oldest != null) t("usage.note.retention.oldest", retention, shortDay(oldest)) else
      t("usage.note.retention", retention))
    if (usage.estimatedSessions > 0) {
      ViewNote(t("usage.note.estimated", usage.estimatedSessions, money(usage.estimatedCost)))
    }
    Tiles(usage)
    DailyChart(usage.days, retention.coerceIn(MIN_CHART_DAYS, MAX_CHART_DAYS))
    Section(t("usage.by.model")) {
      Table(listOf(t("usage.model"), t("usage.input"), t("usage.output"), t("usage.cache.read"), t("usage.cache.write"),
        t("usage.cost")), usage.models.sortedByDescending { it.cost }.map { model ->
        TableRow(listOf(model.model, tokens(model.input), tokens(model.output), tokens(model.cacheRead),
          tokens(model.cacheWrite), money(model.cost)))
      })
    }
    val several = usage.projects.size > 1
    if (several) {
      Section(t("usage.by.project")) {
        Table(listOf(t("usage.project"), t("usage.sessions"), t("usage.tokens"), t("usage.cost")),
          usage.projects.sortedByDescending { it.cost }.map { project ->
            TableRow(listOf(project.name, project.sessions.toString(), tokens(project.tokens), money(project.cost)))
          })
      }
    }
    Section(t("usage.top")) {
      Table(listOf(t("usage.session"), t("usage.tokens"), t("usage.cost")), usage.top.map { item ->
        TableRow(listOf((if (item.archived) t("usage.session.archived", item.title) else item.title) +
          (if (several && item.projectName.isNotEmpty()) " · " + item.projectName else ""), tokens(item.tokens),
          (if (item.estimated) "~" else "") + money(item.cost))) { openSession(workspace, item) }
      })
    }
  }
}

/** Opens a session from the table: its thread, restored if archived, or a new thread resuming it. */
private fun openSession(workspace: Workspace, item: PageMessage.SessionCost) {
  workspace.usageOpen = false
  val thread = item.thread
  when {
    thread == null -> workspace.send(PageOp.Resume(item.sessionId, item.title, item.projectPath, null))
    item.archived -> workspace.send(PageOp.Archive(thread, false))
    else -> {
      workspace.activate(workspace.threads[thread])
      workspace.send(PageOp.Select(thread))
    }
  }
}

/** Tiles in a grid of columns at least [TILE_WIDTH] wide; a row's tiles share its height. */
@Composable
private fun Tiles(usage: PageMessage.Usage) {
  val tiles = listOf(
    Triple(t("usage.tile.cost"), money(usage.totalCost), t("usage.tile.cost.detail")),
    Triple(t("usage.tokens"), tokens(usage.totalTokens), t("usage.tile.tokens.detail")),
    Triple(t("usage.sessions"), usage.sessions.toString(),
      if (usage.sessions > 0) t("usage.tile.sessions.average", money(usage.totalCost / usage.sessions)) else ""),
  )
  BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)) {
    val columns = ((this.maxWidth + 8.dp) / (TILE_WIDTH + 8.dp)).toInt().coerceIn(1, tiles.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (line in tiles.chunked(columns)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (index in 0 until columns) {
            val tile = line.getOrNull(index)
            if (tile == null) {
              Spacer(Modifier.weight(1f))
            } else {
              Tile(tile.first, tile.second, tile.third, Modifier.weight(1f).fillMaxHeight())
            }
          }
        }
      }
    }
  }
}

private val TILE_WIDTH = 130.dp

@Composable
private fun Tile(label: String, value: String, detail: String, modifier: Modifier) {
  val size = textStyle.fontSize.value * 1.5f
  Card(modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
    Text(label, color = colors.muted, style = tinyStyle)
    Text(value, Modifier.padding(top = 2.dp), color = colors.fg, fontWeight = FontWeight.SemiBold,
      style = textStyle.copy(fontSize = size.sp, lineHeight = (size * 1.25f).sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR))
    if (detail.isNotEmpty()) {
      Text(detail, Modifier.padding(top = 2.dp), color = colors.muted, style = tinyStyle)
    }
  }
}

/** Digits of equal width, so numbers line up. */
private const val TABULAR = "tnum"

/** A bordered box on the card color. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Box(Modifier.clip(shape).background(colors.card).border(1.dp, colors.divider, shape).then(modifier)) {
    Column { content() }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(Modifier.padding(bottom = 22.dp)) {
    Text(title, Modifier.padding(bottom = 8.dp), color = colors.muted, style = smallStyle, fontWeight = FontWeight.SemiBold)
    content()
  }
}

@Composable
private fun DailyChart(days: List<PageMessage.DayCost>, span: Int) {
  val byDay = days.associate { it.day to it.cost }
  val today = LocalDate.now()
  val series = (span - 1 downTo 0).map { offset ->
    val date = today.minusDays(offset.toLong())
    date to (byDay[date.toString()] ?: 0.0)
  }
  val max = series.maxOf { it.second }
  Box(Modifier.padding(bottom = 22.dp)) {
    Card(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
      Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(t("usage.chart.title", span), Modifier.weight(1f), color = colors.muted, style = tinyStyle)
        Text(t("usage.chart.max", money(max)), color = colors.muted, style = tinyStyle)
      }
      val divider = colors.divider
      Row(Modifier.fillMaxWidth().height(120.dp).drawBehind {
        for (y in listOf(0f, this.size.height / 2)) {
          this.drawRect(divider, Offset(0f, y), Size(this.size.width, 1.dp.toPx()))
        }
      }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((date, cost) in series) {
          val (interaction, hovered) = rememberHover()
          Tip(shortDay(date) + " · " + money(cost), Modifier.weight(1f).fillMaxHeight()) {
            BoxWithConstraints(Modifier.fillMaxSize().hoverable(interaction),
              contentAlignment = Alignment.BottomCenter) {
              if (cost > 0 && max > 0) {
                val height = this.maxHeight * maxOf(0.02f, (cost / max).toFloat())
                Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                  .background(colors.primary.copy(alpha = if (hovered) 1f else 0.85f)))
              }
            }
          }
        }
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(colors.softBorder))
      Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(shortDay(series[0].first), color = colors.muted, style = tinyStyle)
        Spacer(Modifier.weight(1f))
        Text(shortDay(series[span / 2].first), color = colors.muted, style = tinyStyle)
        Spacer(Modifier.weight(1f))
        Text(t("usage.chart.today"), color = colors.muted, style = tinyStyle)
      }
    }
  }
}

/** A table row's cells, the first one naming it and the others muted when [muted]; clicking it does [onClick] when given. */
class TableRow(val cells: List<String>, val muted: Boolean = false, val actions: (@Composable () -> Unit)? = null,
  val onClick: (() -> Unit)? = null)

/**
 * A table sized like an HTML one: each column as wide as its widest cell, room left over shared out in proportion.
 * The first column names each row and wraps when room runs short; the others are right-aligned. Rows with
 * [TableRow.actions] get a last column for them.
 */
@Composable
fun Table(headers: List<String>, rows: List<TableRow>) {
  val hasActions = rows.any { it.actions != null }
  val columns = headers.size + (if (hasActions) 1 else 0)
  val style = smallStyle.copy(fontFeatureSettings = TABULAR)
  Card(Modifier.fillMaxWidth()) {
    Layout(content = {
      TableLine(true, null)
      headers.forEachIndexed { index, header ->
        Cell(index) {
          Text(header, color = colors.muted, style = style, fontWeight = FontWeight.Medium,
            maxLines = if (index == 0) Int.MAX_VALUE else 1)
        }
      }
      if (hasActions) {
        Cell(columns - 1) {}
      }
      rows.forEachIndexed { line, row ->
        TableLine(line < rows.lastIndex, row.onClick)
        for (index in headers.indices) {
          Cell(index) {
            Text(row.cells.getOrElse(index) { "" }, color = when {
              index == 0 -> colors.fg
              row.muted -> colors.muted
              else -> colors.body
            }, style = style, maxLines = if (index == 0) Int.MAX_VALUE else 1)
          }
        }
        if (hasActions) {
          Cell(columns - 1) { row.actions?.invoke() }
        }
      }
    }) { measurables, constraints ->
      val stride = columns + 1
      val lines = measurables.size / stride
      val widest = IntArray(columns)
      var shortestName = 0
      for (line in 0 until lines) {
        for (column in 0 until columns) {
          val cell = measurables[line * stride + 1 + column]
          widest[column] = maxOf(widest[column], cell.maxIntrinsicWidth(Constraints.Infinity))
          if (column == 0) {
            shortestName = maxOf(shortestName, cell.minIntrinsicWidth(Constraints.Infinity))
          }
        }
      }
      val width = constraints.maxWidth
      val total = widest.sum()
      val widths = IntArray(columns)
      if (total in 1..width) {
        var used = 0
        for (column in 1 until columns) {
          widths[column] = widest[column] + (width - total) * widest[column] / total
          used += widths[column]
        }
        widths[0] = width - used
      } else {
        for (column in 1 until columns) {
          widths[column] = widest[column]
        }
        widths[0] = maxOf(shortestName, width - (total - widest[0]))
      }
      val measured = (0 until lines).map { line ->
        val cells = (0 until columns).map { column ->
          measurables[line * stride + 1 + column].measure(Constraints.fixedWidth(widths[column]))
        }
        val height = cells.maxOf { it.height }
        Triple(measurables[line * stride].measure(Constraints.fixed(width, height)), cells, height)
      }
      layout(width, measured.sumOf { it.third }) {
        var y = 0
        for ((background, cells, height) in measured) {
          background.place(0, y)
          var x = 0
          cells.forEachIndexed { column, cell ->
            cell.place(x, y + (height - cell.height) / 2)
            x += widths[column]
          }
          y += height
        }
      }
    }
  }
}

/** A table line's background: lit under the mouse when it opens something, over a rule unless it is the last. */
@Composable
private fun TableLine(rule: Boolean, onClick: (() -> Unit)?) {
  val divider = colors.divider
  Box(Modifier.let { if (onClick != null) it.hoverClick(RoundedCornerShape(0.dp), onClick = onClick) else it }.drawBehind {
    if (rule) {
      val stroke = 1.dp.toPx()
      this.drawRect(divider, Offset(0f, this.size.height - stroke), Size(this.size.width, stroke))
    }
  })
}

@Composable
private fun Cell(column: Int, content: @Composable () -> Unit) {
  Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    contentAlignment = if (column == 0) Alignment.CenterStart else Alignment.CenterEnd) {
    content()
  }
}
