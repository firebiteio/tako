package io.firebite.tako.ide.actions;

import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.ChatService;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.Images;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Attaches the terminal, console or editor selection, or else the chosen files, to the current chat. */
public final class AddToChatAction extends AnAction implements DumbAware {
  private static final String TERMINAL_TOOL_WINDOW = "Terminal";

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) {
      return;
    }
    ChatService chats = ChatService.getInstance(project);
    Editor editor = event.getData(CommonDataKeys.EDITOR);
    ContextItem selection = outputSelection(event, editor);
    if (selection == null && editor != null) {
      selection = IdeContext.selection(project, editor);
    }
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
      && (hasEditor || terminalSelection(event) != null || (files != null && files.length > 0)));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  /** The selection in a run or debug console, labeled by its run configuration, or in the terminal. */
  private static @Nullable ContextItem outputSelection(AnActionEvent event, @Nullable Editor editor) {
    if (editor != null && event.getData(LangDataKeys.CONSOLE_VIEW) != null) {
      String text = editor.getSelectionModel().getSelectedText();
      if (text == null || text.isEmpty()) {
        return null;
      }
      RunContentDescriptor run = event.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR);
      String name = run == null ? null : run.getDisplayName();
      return name == null || name.isEmpty()
        ? IdeContext.excerpt(TakoBundle.message("context.console"), "the console", text)
        : IdeContext.excerpt(name, "the console of " + name, text);
    }
    String text = terminalSelection(event);
    return text == null ? null : IdeContext.excerpt(TakoBundle.message("context.terminal"), "the terminal", text);
  }

  /** The terminal publishes its selection as the text a search would start with. */
  private static @Nullable String terminalSelection(AnActionEvent event) {
    ToolWindow window = event.getData(PlatformDataKeys.TOOL_WINDOW);
    if (window == null || !TERMINAL_TOOL_WINDOW.equals(window.getId())) {
      return null;
    }
    String text = event.getData(PlatformDataKeys.PREDEFINED_TEXT);
    return text == null || text.isEmpty() ? null : text;
  }
}
