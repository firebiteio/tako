package io.firebite.tako.ui.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.firebite.tako.agent.ImageAttachment
import io.firebite.tako.ide.context.ContextItem
import io.firebite.tako.ide.project.ProjectIcons
import io.firebite.tako.ui.bridge.ChatMessage
import io.firebite.tako.ui.state.Alert
import io.firebite.tako.ui.t
import io.firebite.tako.ui.textLocale
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import java.nio.ByteBuffer
import java.util.Base64

@Composable
fun Ico(key: IconKey, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = Color.Unspecified) {
  Icon(key, null, modifier.size(size), tint = tint)
}

/** A borderless icon button that lights up under the mouse, with a tooltip; small icons get the compact size. */
@Composable
fun IconBtn(key: IconKey, tooltip: String, modifier: Modifier = Modifier, iconSize: Dp = 16.dp,
  color: Color = colors.muted, onClick: () -> Unit) {
  val isSmall = iconSize < 16.dp
  val shape = RoundedCornerShape(if (isSmall) 6.dp else 8.dp)
  val (interaction, hovered) = rememberHover()
  Tip(tooltip, modifier) {
    Box(Modifier.size(if (isSmall) 22.dp else 28.dp).clip(shape)
      .background(if (hovered) colors.hover else Color.Transparent).hoverable(interaction)
      .clickable(interaction, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
      contentAlignment = Alignment.Center) {
      Ico(key, size = iconSize, tint = if (hovered) colors.fg else color)
    }
  }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  TextButton(text, modifier, null, colors.primaryFg, colors.primary, brighter(colors.primary), null, onClick)
}

/** An outlined button; a [color] makes it a danger-style one tinted in that color. */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, icon: IconKey? = null, color: Color = Color.Unspecified,
  onClick: () -> Unit) {
  val isTinted = color != Color.Unspecified
  TextButton(text, modifier, icon, if (isTinted) color else colors.fg, colors.bg,
    if (isTinted) lerpColor(colors.bg, color, 0.1f) else colors.hover, colors.softBorder, onClick)
}

@Composable
private fun TextButton(text: String, modifier: Modifier, icon: IconKey?, color: Color, background: Color,
  hoverBackground: Color, border: Color?, onClick: () -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  val (interaction, hovered) = rememberHover()
  Row(modifier.height(26.dp).clip(shape).background(if (hovered) hoverBackground else background)
    .let { if (border != null) it.border(1.dp, border, shape) else it }.hoverable(interaction)
    .clickable(interaction, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (icon != null) {
      Ico(icon, size = 14.dp, tint = color)
    }
    Text(text, color = color, style = smallStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1)
  }
}

/** The color as CSS's brightness(1.1) shows it. */
private fun brighter(color: Color): Color = Color((color.red * 1.1f).coerceAtMost(1f),
  (color.green * 1.1f).coerceAtMost(1f), (color.blue * 1.1f).coerceAtMost(1f), color.alpha)

/** A tooltip when there is text for one. */
@Composable
fun Tip(text: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  if (text.isNullOrEmpty()) {
    Box(modifier) { content() }
  } else {
    Tooltip(tooltip = { Text(text, style = labelStyle) }, modifier = modifier, content = content)
  }
}

/** A clickable area that lights up under the mouse, like the IDE's list rows. */
@Composable
fun Modifier.hoverClick(shape: RoundedCornerShape = RoundedCornerShape(8.dp), alpha: Float = 1f,
  selected: Boolean = false, interaction: MutableInteractionSource? = null, onClick: () -> Unit): Modifier {
  val own = remember { MutableInteractionSource() }
  val source = interaction ?: own
  val hovered by source.collectIsHoveredAsState()
  val background = when {
    selected -> colors.hover
    hovered -> colors.hover.copy(alpha = colors.hover.alpha * alpha)
    else -> Color.Transparent
  }
  return this.clip(shape).background(background).hoverable(source)
    .clickable(source, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
}

/** Whether the mouse is over what this modifier is on. */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  return interaction to hovered
}

@Composable
fun Spinner(size: Dp = 14.dp) {
  Box(Modifier.size(size), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.size(size))
  }
}

@Composable
fun Dot(color: Color, size: Dp, modifier: Modifier = Modifier) {
  Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Points right, or down when open. */
@Composable
fun Chevron(open: Boolean, size: Dp = 12.dp, tint: Color = colors.faint) {
  val angle by animateFloatAsState(if (open) 90f else 0f, tween(200))
  Ico(Icons.chevronRight, Modifier.rotate(angle), size, tint)
}

/** A label that shimmers while what it names runs. */
@Composable
fun ShineText(text: AnnotatedString, shine: Boolean, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
  if (!shine) {
    Text(text, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    return
  }
  val transition = rememberInfiniteTransition()
  val offset by transition.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(2200, easing = LinearEasing)))
  val highlight = colors.fg
  val width = with(LocalDensity.current) { 240.dp.toPx() }
  val brush = Brush.horizontalGradient(
    0f to color, (offset - 0.15f).coerceIn(0f, 1f) to color, offset.coerceIn(0f, 1f) to highlight,
    (offset + 0.15f).coerceIn(0f, 1f) to color, 1f to color, startX = 0f, endX = width)
  Text(text, modifier, style = style.copy(brush = brush), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private val bitmaps = HashMap<String, ImageBitmap?>()

/** The image's bitmap, decoded once. */
fun bitmapOf(image: ImageAttachment): ImageBitmap? = bitmaps.getOrPut(image.mediaType + image.data.hashCode()) {
  try {
    org.jetbrains.skia.Image.makeFromEncoded(Base64.getDecoder().decode(image.data)).toComposeImageBitmap()
  } catch (e: RuntimeException) {
    null
  }
}

@Composable
fun ImageThumb(image: ImageAttachment, size: Dp, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
  val bitmap = remember(image) { bitmapOf(image) }
  val (interaction, hovered) = rememberHover()
  Tip(image.name.ifEmpty { t("common.image") }) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(colors.editorBg)
      .border(1.dp, colors.softBorder, RoundedCornerShape(10.dp)).hoverable(interaction)
      .clickable(onClick = onOpen)) {
      if (bitmap != null) {
        Image(bitmap, image.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
      }
      if (onRemove != null && hovered) {
        Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
          Tip(t("common.remove")) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f))
              .clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
              Ico(Icons.closeSmall, size = 12.dp, tint = Color.White)
            }
          }
        }
      }
    }
  }
}

private val CHIP_FILE = Color(0xFF3B9EDE)
private val CHIP_PROBLEMS = Color(0xFFD9A343)
private val CHIP_SELECTION = Color(0xFFA87FD6)

/** A chip for attached IDE context. */
@Composable
fun MentionChip(item: ContextItem, onRemove: (() -> Unit)?) {
  val chip = when (item.kind) {
    ContextItem.Kind.PROBLEMS -> CHIP_PROBLEMS
    ContextItem.Kind.SELECTION -> CHIP_SELECTION
    else -> CHIP_FILE
  }
  val icon = when (item.kind) {
    ContextItem.Kind.PROBLEMS -> Icons.warning
    ContextItem.Kind.SELECTION -> Icons.selection
    else -> Icons.file
  }
  val color = lerpColor(chip, colors.fg, 0.22f)
  Tip(item.text?.ifEmpty { null } ?: item.label) {
    Row(Modifier.height((smallStyle.fontSize.value * 1.5f).dp).clip(RoundedCornerShape(6.dp))
      .background(chip.copy(alpha = 0.11f))
      .border(1.dp, lerpColor(colors.softBorder, chip, 0.34f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Ico(icon, size = 12.dp, tint = color)
      Text(item.label, color = color, style = smallStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1,
        overflow = TextOverflow.Ellipsis)
      if (onRemove != null) {
        val (interaction, hovered) = rememberHover()
        Box(Modifier.alpha(if (hovered) 1f else 0.7f).hoverable(interaction).clickable(onClick = onRemove)
          .pointerHoverIcon(PointerIcon.Hand)) {
          Ico(Icons.closeSmall, size = 12.dp, tint = color)
        }
      }
    }
  }
}

fun lerpColor(from: Color, to: Color, amount: Float): Color = androidx.compose.ui.graphics.lerp(from, to, amount)

@Composable
fun InlineAlert(text: String, modifier: Modifier = Modifier) {
  Row(modifier.clip(RoundedCornerShape(14.dp)).background(colors.del.copy(alpha = 0.08f))
    .border(1.dp, colors.del.copy(alpha = 0.32f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Ico(Icons.error, size = 14.dp, tint = colors.del)
    Text(text, color = colors.del, style = smallStyle)
  }
}

/** A dismissible alert. */
@Composable
fun AlertCard(alert: Alert, onDismiss: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Row(Modifier.shadow(8.dp, shape).clip(shape).background(lerpColor(colors.bg, colors.del, 0.08f))
    .border(1.dp, colors.del.copy(alpha = 0.32f), shape).padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    Ico(Icons.error, Modifier.padding(top = 1.dp), size = 14.dp, tint = colors.del)
    Text(alert.text, Modifier.weight(1f, fill = false), color = colors.fg, style = smallStyle, maxLines = 4,
      overflow = TextOverflow.Ellipsis)
    val label = alert.actionLabel
    val action = alert.action
    if (label != null && action != null) {
      OutlineButton(label) {
        onDismiss()
        action()
      }
    }
    IconBtn(Icons.closeSmall, t("common.dismiss"), iconSize = 14.dp, onClick = onDismiss)
  }
}

/** Decoded project icons by content. */
private val projectIcons = HashMap<ByteBuffer, Painter?>()

/** A project folder's own icon, else a folder. */
@Composable
fun ProjectIcon(project: ChatMessage.ProjectItem?, size: Dp = 14.dp, tint: Color = Color.Unspecified) {
  val icon = project?.let { if (JewelTheme.isDark) it.iconDark ?: it.icon else it.icon }
  val density = LocalDensity.current
  val painter = remember(icon) {
    if (icon == null) {
      null
    } else {
      projectIcons.getOrPut(ByteBuffer.wrap(icon.bytes)) { decodeIcon(icon, density) }
    }
  }
  if (painter == null) {
    Ico(Icons.folder, size = size, tint = tint)
  } else {
    Image(painter, null, Modifier.size(size).clip(RoundedCornerShape(3.dp)))
  }
}

private fun decodeIcon(icon: ProjectIcons.Icon, density: androidx.compose.ui.unit.Density): Painter? {
  return try {
    if (icon.isSvg) {
      icon.bytes.decodeToSvgPainter(density)
    } else {
      androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(icon.bytes).toComposeImageBitmap())
    }
  } catch (e: RuntimeException) {
    null
  }
}

/** A thin horizontal rule. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

/** An uppercase section title with an optional count, over a rule. */
@Composable
fun SectionTitle(title: String, count: String? = null, modifier: Modifier = Modifier) {
  Column(modifier.padding(top = 14.dp, bottom = 4.dp)) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(title.uppercase(textLocale), Modifier.weight(1f), color = colors.muted,
        style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em))
      if (count != null) {
        Text(count, color = colors.muted, style = tinyStyle.copy(fontWeight = FontWeight.Medium,
          letterSpacing = 0.04.em))
      }
    }
    Rule()
  }
}
