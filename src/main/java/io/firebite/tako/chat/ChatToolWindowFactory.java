package io.firebite.tako.chat;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import io.firebite.tako.TakoBundle;
import java.util.List;
import javax.swing.SwingConstants;
import org.jetbrains.annotations.NotNull;

public final class ChatToolWindowFactory implements ToolWindowFactory, DumbAware {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    if (!JBCefApp.isSupported()) {
      JBLabel label = new JBLabel(TakoBundle.message("error.jcef"), SwingConstants.CENTER);
      toolWindow.getContentManager().addContent(ContentFactory.getInstance().createContent(label, "", false));
      return;
    }
    AnAction newChat = ActionManager.getInstance().getAction("Tako.NewChat");
    if (newChat != null) {
      toolWindow.setTitleActions(List.of(newChat));
    }
    ChatService.getInstance(project).attach(toolWindow);
  }
}
