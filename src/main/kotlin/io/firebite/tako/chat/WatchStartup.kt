package io.firebite.tako.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.firebite.tako.chat.store.ThreadStore
import io.firebite.tako.chat.watch.PullRequestWatcher

/**
 * Sets up Tako's tool window on project open when a thread watches a pull request. The window's content is made only
 * when it is first shown, and polling runs and delivers through it, so a watch would otherwise sleep until then.
 */
class WatchStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!PullRequestWatcher.hasWatches(ThreadStore.getInstance(project).all())) {
      return
    }
    val manager = ToolWindowManager.getInstance(project)
    manager.invokeLater {
      if (!project.isDisposed) {
        manager.getToolWindow(ChatService.TOOL_WINDOW_ID)?.contentManager
      }
    }
  }
}
