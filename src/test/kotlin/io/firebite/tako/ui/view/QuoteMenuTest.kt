package io.firebite.tako.ui.view

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.jewel.ui.component.TextContextMenu as JewelTextContextMenu

/** Right-clicks real selectable text, with the IDE's own text menu underneath, and reads the menu it opens. */
class QuoteMenuTest {
  private class Capture : ContextMenuRepresentation {
    var items: List<ContextMenuItem> = emptyList()

    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
      if (state.status is ContextMenuState.Status.Open) {
        this.items = items()
      }
    }
  }

  private var time = 0L

  private fun rightClickHello(enabled: Boolean, quoted: MutableList<String>, check: (Capture, () -> Unit) -> Unit) {
    val capture = Capture()
    val menu = QuoteTextContextMenu(JewelTextContextMenu, "Quote", { enabled }, { quoted.add(it) })
    val scene = ImageComposeScene(300, 60, Density(1f)) {
      CompositionLocalProvider(LocalTextContextMenu provides menu, LocalContextMenuRepresentation provides capture) {
        SelectionContainer {
          BasicText("hello")
        }
      }
    }
    try {
      this.frame(scene)
      val start = Offset(1f, 8f)
      val end = Offset(250f, 8f)
      scene.sendPointerEvent(PointerEventType.Press, start, buttons = PointerButtons(isPrimaryPressed = true),
        button = PointerButton.Primary)
      this.frame(scene)
      scene.sendPointerEvent(PointerEventType.Move, end, buttons = PointerButtons(isPrimaryPressed = true))
      this.frame(scene)
      scene.sendPointerEvent(PointerEventType.Release, end, buttons = PointerButtons(), button = PointerButton.Primary)
      this.frame(scene)
      val at = Offset(8f, 8f)
      scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true),
        button = PointerButton.Secondary)
      scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary)
      this.frame(scene)
      check(capture) { repeat(3) { this.frame(scene) } }
    } finally {
      scene.close()
    }
  }

  private fun frame(scene: ImageComposeScene) {
    this.time += 16_000_000L
    scene.render(this.time)
  }

  @Test
  fun quotesTheTextUnderARightClick() {
    val quoted = ArrayList<String>()
    this.rightClickHello(true, quoted) { capture, frames ->
      val item = capture.items.singleOrNull { it.label == "Quote" }
        ?: throw AssertionError("no quote item in " + capture.items.map { it.label })
      item.onClick()
      frames()
    }

    assertEquals(listOf("hello"), quoted)
  }

  @Test
  fun offersNoQuoteWhileDisabled() {
    this.rightClickHello(false, ArrayList()) { capture, _ ->
      assertTrue(capture.items.isNotEmpty())
      assertFalse(capture.items.any { it.label == "Quote" })
    }
  }
}
