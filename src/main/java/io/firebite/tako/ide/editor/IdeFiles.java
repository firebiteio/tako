package io.firebite.tako.ide.editor;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.EditPreview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Opening, diffing, refreshing and editing the files the agent names. */
public final class IdeFiles {
  private IdeFiles() {
  }

  public static @Nullable Path resolve(Project project, String path) {
    try {
      Path candidate = Path.of(path);
      if (candidate.isAbsolute()) {
        return candidate;
      }
      String base = project.getBasePath();
      return base == null ? null : Path.of(base).resolve(candidate).normalize();
    } catch (InvalidPathException e) {
      return null;
    }
  }

  public static @Nullable String readText(Path path) {
    try {
      return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    } catch (IOException e) {
      return null;
    }
  }

  /** Must run on the EDT. */
  public static boolean open(Project project, String path, int line) {
    Path resolved = resolve(project, path);
    VirtualFile file = resolved == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved);
    if (file == null || file.isDirectory()) {
      return false;
    }
    new OpenFileDescriptor(project, file, Math.max(line - 1, 0), 0).navigate(true);
    return true;
  }

  /** Must run on the EDT. */
  public static void showDiff(Project project, EditPreview preview, String title) {
    DiffManager.getInstance().showDiff(project, request(project, preview, title, TakoBundle.message("diff.current"),
      TakoBundle.message("diff.proposed")));
  }

  /** One diff window stepping through the files. Must run on the EDT. */
  public static void showDiffs(Project project, List<EditPreview> previews) {
    List<DiffRequest> requests = new ArrayList<>();
    for (EditPreview preview : previews) {
      String title = relativeOrAbsolute(project, preview.filePath());
      requests.add(request(project, preview, title, TakoBundle.message("diff.before"), TakoBundle.message("diff.now")));
    }
    DiffManager.getInstance().showDiff(project, new SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT);
  }

  private static SimpleDiffRequest request(Project project, EditPreview preview, String title, String beforeTitle,
    String afterTitle) {
    String fileName = Path.of(preview.filePath()).getFileName().toString();
    FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName);
    DiffContentFactory contents = DiffContentFactory.getInstance();
    return new SimpleDiffRequest(title,
      contents.create(project, preview.before(), fileType),
      contents.create(project, preview.after(), fileType),
      beforeTitle, afterTitle);
  }

  private static String relativeOrAbsolute(Project project, String path) {
    String base = project.getBasePath();
    String normalized = path.replace('\\', '/');
    if (base != null && normalized.toLowerCase(Locale.ROOT).startsWith(base.toLowerCase(Locale.ROOT) + "/")) {
      return normalized.substring(base.length() + 1);
    }
    return normalized;
  }

  public static void refresh(Project project, @Nullable String path) {
    if (path == null) {
      String base = project.getBasePath();
      if (base != null) {
        VfsUtil.markDirtyAndRefresh(true, true, true, Path.of(base).toFile());
      }
      return;
    }
    Path resolved = resolve(project, path);
    if (resolved != null && resolved.getParent() != null) {
      VfsUtil.markDirtyAndRefresh(true, false, true, resolved.getParent().toFile(), resolved.toFile());
    }
  }

  /** Replaces the selection, or inserts at the caret, of the editor in front. Must run on the EDT. */
  public static void insertAtCaret(Project project, String text) {
    Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
    if (editor == null || !editor.getDocument().isWritable()) {
      return;
    }
    WriteCommandAction.runWriteCommandAction(project, TakoBundle.message("command.insert.code"), null, () -> {
      int offset = editor.getCaretModel().getOffset();
      if (editor.getSelectionModel().hasSelection()) {
        int start = editor.getSelectionModel().getSelectionStart();
        editor.getDocument().replaceString(start, editor.getSelectionModel().getSelectionEnd(), text);
        offset = start;
      } else {
        editor.getDocument().insertString(offset, text);
      }
      editor.getCaretModel().moveToOffset(offset + text.length());
    });
  }
}
