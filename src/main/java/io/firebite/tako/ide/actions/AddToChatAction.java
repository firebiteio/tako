package io.firebite.tako.ide.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.firebite.tako.chat.ChatService;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.Images;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Attaches the editor selection, or else the chosen files, to the current chat. */
public final class AddToChatAction extends AnAction implements DumbAware {

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) {
      return;
    }
    ChatService chats = ChatService.getInstance(project);
    Editor editor = event.getData(CommonDataKeys.EDITOR);
    ContextItem selection = editor == null ? null : IdeContext.selection(project, editor);
    if (selection != null) {
      chats.addContext(selection, null);
      return;
    }
    VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    if (files == null) {
      return;
    }
    List<Path> images = new ArrayList<>();
    for (VirtualFile file : files) {
      if (Images.isImageFile(file.getName()) && file.isInLocalFileSystem()) {
        images.add(file.toNioPath());
      } else {
        chats.addContext(IdeContext.file(file), null);
      }
    }
    if (!images.isEmpty()) {
      chats.addImages(images);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    boolean hasEditor = event.getData(CommonDataKeys.EDITOR) != null;
    VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    event.getPresentation().setEnabledAndVisible(event.getProject() != null
      && (hasEditor || (files != null && files.length > 0)));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
