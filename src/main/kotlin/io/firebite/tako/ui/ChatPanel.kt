package io.firebite.tako.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.firebite.tako.ui.bridge.PageMessage
import io.firebite.tako.ui.bridge.PageOp
import io.firebite.tako.ui.bridge.PageRequest
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.view.ChatScreen
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.util.function.Consumer
import javax.swing.JComponent

/**
 * The chat in Compose. The IDE posts [PageMessage]s from any thread; they reach the chat's state on the EDT in the
 * order posted. The chat answers with [PageRequest]s, starting with [PageOp.Ready].
 */
class ChatPanel(parent: Disposable, project: Project) : Disposable {
  private var handler: Consumer<PageRequest> = Consumer {}
  private val workspace = Workspace { request -> this.handler.accept(request) }
  val component: JComponent = JewelComposePanel(focusOnClickInside = true) { ChatScreen(this.workspace, project) }

  init {
    Disposer.register(parent, this)
    this.onEdt { this.handler.accept(PageRequest(null, PageOp.Ready())) }
  }

  fun setHandler(handler: Consumer<PageRequest>) {
    this.handler = handler
  }

  /** Safe from any thread. */
  fun post(message: PageMessage.WorkspaceMessage) {
    this.onEdt { this.workspace.receive(message) }
  }

  /** Safe from any thread. */
  fun post(message: PageMessage.ThreadMessage, threadId: String) {
    this.onEdt { this.workspace.receive(message, threadId) }
  }

  override fun dispose() {
  }

  private fun onEdt(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
  }
}
