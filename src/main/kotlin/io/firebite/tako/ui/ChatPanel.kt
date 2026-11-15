package io.firebite.tako.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.firebite.tako.chat.ChatService
import io.firebite.tako.ui.bridge.message.ThreadMessage
import io.firebite.tako.ui.bridge.message.WorkspaceMessage
import io.firebite.tako.ui.state.Workspace
import io.firebite.tako.ui.view.ChatScreen
import kotlinx.coroutines.cancel
import org.jetbrains.jewel.bridge.JewelComposePanel
import javax.swing.JComponent

/**
 * The chat in Compose. The IDE posts [WorkspaceMessage]s and [ThreadMessage]s from any thread; they reach the chat's state on the EDT in the
 * order posted. The chat calls the [ChatService] directly.
 */
class ChatPanel(parent: Disposable, project: Project, service: ChatService) : Disposable {
  private val workspace = Workspace(service, project)
  val component: JComponent = JewelComposePanel(focusOnClickInside = true) { ChatScreen(this.workspace) }

  init {
    Disposer.register(parent, this)
  }

  /** Safe from any thread. */
  fun post(message: WorkspaceMessage) {
    this.onEdt { this.workspace.receive(message) }
  }

  /** Safe from any thread. */
  fun post(message: ThreadMessage, threadId: String) {
    this.onEdt { this.workspace.receive(message, threadId) }
  }

  override fun dispose() {
    this.workspace.scope.cancel()
  }

  private fun onEdt(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
  }
}
