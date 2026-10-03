package io.firebite.tako.ide.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.ChatService;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import org.jetbrains.annotations.NotNull;

/** Attaches the file's inspection results to the current chat and proposes a prompt to fix them. */
public final class FixProblemsAction extends AnAction implements DumbAware {

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    Editor editor = event.getData(CommonDataKeys.EDITOR);
    if (project == null || editor == null) {
      return;
    }
    ContextItem problems = IdeContext.problems(project, editor);
    if (problems != null) {
      ChatService.getInstance(project).addContext(problems, TakoBundle.message("context.fix.problems.prompt"));
    }
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    event.getPresentation().setEnabledAndVisible(event.getProject() != null
      && event.getData(CommonDataKeys.EDITOR) != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
