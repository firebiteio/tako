package io.firebite.tako.ide.actions;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.ChatService;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.ReviewComment;
import io.firebite.tako.ide.context.ReviewTarget;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.editor.ReviewCommentPopup;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Comments on the selected lines, or the caret's line, for the agent. In a diff Tako opened the comment goes to the
 * thread whose changes it shows, in an editor or another diff's file to the current thread.
 */
public final class ReviewCommentAction extends AnAction implements DumbAware {
  private static final IntUnaryOperator SAME_LINE = line -> line;
  private static final IntUnaryOperator NO_LINE = line -> -1;

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    Pending pending = project == null ? null : pending(event);
    if (pending == null) {
      return;
    }
    if (pending.lines().isEmpty()) {
      HintManager.getInstance().showInformationHint(pending.editor(), TakoBundle.message("review.nothing"));
      return;
    }
    String title = TakoBundle.message("review.popup.title", ReviewComment.place(pending.target(), pending.lines()));
    ReviewCommentPopup.show(pending.editor(), title, comment -> add(project, pending, comment));
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    event.getPresentation().setEnabledAndVisible(event.getProject() != null && isAvailable(event));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  private static boolean isAvailable(AnActionEvent event) {
    FrameDiffTool.DiffViewer viewer = event.getData(DiffDataKeys.DIFF_VIEWER);
    if (viewer != null && !(viewer instanceof TwosideTextDiffViewer) && !(viewer instanceof UnifiedDiffViewer)) {
      return false;
    }
    if (viewer != null && target(event) != null) {
      return true;
    }
    if (viewer instanceof UnifiedDiffViewer || event.getData(AddToChatAction.CONSOLE_VIEW) != null
      || event.getData(AddToChatAction.TERMINAL_VIEW) != null) {
      return false;
    }
    Editor editor = event.getData(CommonDataKeys.EDITOR);
    return editor != null && localFile(editor) != null;
  }

  /** The comment's thread and lines, from a diff Tako opened, else from a file's editor. */
  private static @Nullable Pending pending(AnActionEvent event) {
    FrameDiffTool.DiffViewer viewer = event.getData(DiffDataKeys.DIFF_VIEWER);
    ReviewTarget target = viewer == null ? null : target(event);
    Editor eventEditor = event.getData(CommonDataKeys.EDITOR);
    if (target != null && viewer instanceof UnifiedDiffViewer unified) {
      Editor editor = unified.getEditor();
      List<ReviewComment.Line> lines = lines(editor,
        line -> unified.transferLineFromOnesideStrict(Side.LEFT, line),
        line -> unified.transferLineFromOnesideStrict(Side.RIGHT, line));
      Side side = ReviewComment.isOld(lines) ? Side.LEFT : Side.RIGHT;
      return new Pending(target, editor, lines, unified.getDocument(side));
    }
    if (target != null && viewer instanceof TwosideTextDiffViewer twoside) {
      Side side = eventEditor == null ? null : Side.fromValue(twoside.getEditors(), eventEditor);
      if (side == null) {
        side = twoside.getCurrentSide();
      }
      Editor editor = twoside.getEditor(side);
      List<ReviewComment.Line> lines = side.isLeft()
        ? lines(editor, SAME_LINE, NO_LINE)
        : lines(editor, NO_LINE, SAME_LINE);
      return new Pending(target, editor, lines, editor.getDocument());
    }
    VirtualFile file = eventEditor == null ? null : localFile(eventEditor);
    if (file == null) {
      return null;
    }
    return new Pending(ReviewTarget.file(file.getPath()), eventEditor, lines(eventEditor, NO_LINE, SAME_LINE),
      eventEditor.getDocument());
  }

  private static @Nullable ReviewTarget target(AnActionEvent event) {
    DiffRequest request = event.getData(DiffDataKeys.DIFF_REQUEST);
    return request == null ? null : request.getUserData(ReviewTarget.KEY);
  }

  private static @Nullable VirtualFile localFile(Editor editor) {
    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
    return file != null && file.isInLocalFileSystem() ? file : null;
  }

  /**
   * The selected lines, or the caret's line. A selection ending at the start of a line leaves that line out. None in an
   * empty document, such as the old side of a new file.
   */
  private static List<ReviewComment.Line> lines(Editor editor, IntUnaryOperator toBefore, IntUnaryOperator toAfter) {
    Document document = editor.getDocument();
    if (document.getTextLength() == 0) {
      return List.of();
    }
    SelectionModel selection = editor.getSelectionModel();
    int first;
    int last;
    if (selection.hasSelection()) {
      first = document.getLineNumber(selection.getSelectionStart());
      last = document.getLineNumber(Math.max(selection.getSelectionStart(), selection.getSelectionEnd() - 1));
    } else {
      first = document.getLineNumber(editor.getCaretModel().getOffset());
      last = first;
    }
    return ReviewComment.lines(first, last, toBefore, toAfter, line -> document.getText(
      new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))));
  }

  /** Builds the comment, telling the agent when the file is no longer what the diff shows, and adds it. */
  private static void add(Project project, Pending pending, String comment) {
    ReviewTarget target = pending.target();
    boolean isOld = ReviewComment.isOld(pending.lines());
    boolean isOnDisk = (isOld ? target.before() : target.after()).isOnDisk();
    String shown = pending.shown().getText();
    Async.inBackground(() -> {
      String note = isOnDisk ? note(target.path(), shown) : null;
      ContextItem item = ReviewComment.item(target, pending.lines(), comment, note);
      if (item == null) {
        return;
      }
      Async.onEdt(() -> {
        if (!project.isDisposed()) {
          ChatService.getInstance(project).addReview(target.threadId(), item);
        }
      });
    });
  }

  /** Reads the disk. */
  private static @Nullable String note(String path, String shown) {
    try {
      Path file = Path.of(path);
      return ReviewComment.note(shown, Files.exists(file), IdeFiles.readText(file));
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /** @param shown the document of the version the lines are in */
  private record Pending(
    ReviewTarget target,
    Editor editor,
    List<ReviewComment.Line> lines,
    Document shown
  ) {
  }
}
