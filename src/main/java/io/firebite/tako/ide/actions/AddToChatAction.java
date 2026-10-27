package io.firebite.tako.ide.actions;

import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
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
  /** The terminal plugin's {@code TerminalView.DATA_KEY}, by name so Tako runs without that plugin. */
  static final DataKey<Object> TERMINAL_VIEW = DataKey.create("TerminalView");

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) {
      return;
    }
    ChatService chats = ChatService.getInstance(project);
    if (isOutput(event)) {
      ContextItem output = outputSelection(event);
      if (output != null) {
        chats.addContext(output, null);
      }
      return;
    }
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
    if (isOutput(event)) {
      event.getPresentation().setEnabledAndVisible(event.getProject() != null && outputText(event) != null);
      return;
    }
    boolean hasEditor = event.getData(CommonDataKeys.EDITOR) != null;
    VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    event.getPresentation().setEnabledAndVisible(event.getProject() != null
      && (hasEditor || (files != null && files.length > 0)));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  /**
   * The selection in the terminal, or in a run or debug console labeled by its run configuration. Null without a
   * selection or outside of them.
   */
  static @Nullable ContextItem outputSelection(AnActionEvent event) {
    String text = outputText(event);
    if (text == null) {
      return null;
    }
    if (event.getData(TERMINAL_VIEW) != null) {
      return IdeContext.excerpt(TakoBundle.message("context.terminal"), "the terminal", text);
    }
    RunContentDescriptor run = event.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR);
    String name = run == null ? null : run.getDisplayName();
    return name == null || name.isEmpty()
      ? IdeContext.excerpt(TakoBundle.message("context.console"), "the console", text)
      : IdeContext.excerpt(name, "the console of " + name, text);
  }

  /** The terminal, in its tool window or an editor tab, or a run or debug console. No file stands behind them. */
  private static boolean isOutput(AnActionEvent event) {
    return event.getData(TERMINAL_VIEW) != null
      || (event.getData(LangDataKeys.CONSOLE_VIEW) != null && event.getData(CommonDataKeys.EDITOR) != null);
  }

  /**
   * The selected terminal or console text. The terminal publishes its selection as the text a search would start
   * with, a console selects in its editor.
   */
  private static @Nullable String outputText(AnActionEvent event) {
    String text;
    if (event.getData(TERMINAL_VIEW) != null) {
      text = event.getData(PlatformDataKeys.PREDEFINED_TEXT);
    } else {
      Editor editor = event.getData(CommonDataKeys.EDITOR);
      text = editor == null || event.getData(LangDataKeys.CONSOLE_VIEW) == null
        ? null
        : editor.getSelectionModel().getSelectedText();
    }
    return text == null || text.isEmpty() ? null : text;
  }
}
