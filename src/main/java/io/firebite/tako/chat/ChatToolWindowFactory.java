package io.firebite.tako.chat;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public final class ChatToolWindowFactory implements ToolWindowFactory, DumbAware {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    AnAction newChat = ActionManager.getInstance().getAction("Tako.NewChat");
    if (newChat != null) {
      toolWindow.setTitleActions(List.of(newChat));
    }
    ChatService.getInstance(project).attach(toolWindow);
  }
}
