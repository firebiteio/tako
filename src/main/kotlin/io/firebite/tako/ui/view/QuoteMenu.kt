package io.firebite.tako.ui.view

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import io.firebite.tako.ui.state.ThreadState
import io.firebite.tako.ui.state.canQuote
import io.firebite.tako.ui.state.quote
import io.firebite.tako.ui.t
import kotlinx.coroutines.launch

/** Adds "Quote in composer" to the context menu of text selected in [content]; the quote goes to [thread]'s composer. */
@Composable
fun QuoteMenu(thread: ThreadState, content: @Composable () -> Unit) {
  val base = LocalTextContextMenu.current
  val label = t("timeline.quote")
  val menu = remember(base, label, thread) { QuoteTextContextMenu(base, label, { thread.canQuote }, { thread.quote(it) }) }
  CompositionLocalProvider(LocalTextContextMenu provides menu, content = content)
}

/**
 * [base] with one more item, which passes the selected text to [onQuote].
 *
 * @param enabled whether the item shows
 */
class QuoteTextContextMenu(
  private val base: TextContextMenu,
  private val label: String,
  private val enabled: () -> Boolean,
  private val onQuote: (String) -> Unit,
) : TextContextMenu {
  @Composable
  override fun Area(textManager: TextContextMenu.TextManager, state: ContextMenuState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    ContextMenuDataProvider({
      val selected = textManager.selectedText.text
      if (selected.isBlank() || !this.enabled()) {
        emptyList()
      } else {
        listOf(ContextMenuItem(this.label) {
          // The menu closes in the next frame and may hand the focus back to the text; quote after that.
          scope.launch {
            withFrameNanos {}
            withFrameNanos {}
            this@QuoteTextContextMenu.onQuote(selected)
          }
        })
      }
    }) {
      this.base.Area(textManager, state, content)
    }
  }
}
