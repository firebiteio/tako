package io.firebite.tako.ide.editor;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
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
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.ide.context.ReviewTarget;
import io.firebite.tako.ide.project.GitDiffs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
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

  /** The regular files under the folder, newest first, relative with {@code /} separators. Reads the disk. */
  public static List<String> newestFiles(Path directory, int limit) {
    List<DatedFile> files = new ArrayList<>();
    try {
      Files.walkFileTree(directory, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          if (attributes.isRegularFile()) {
            files.add(new DatedFile(directory.relativize(file).toString().replace('\\', '/'),
              attributes.lastModifiedTime().toMillis()));
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      return List.of();
    }
    files.sort(Comparator.comparingLong(DatedFile::modified).reversed());
    List<String> newest = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, files.size()); i++) {
      newest.add(files.get(i).path());
    }
    return newest;
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

  /** Like {@link #open}, but also finds a project file by its name or a tail of its path, like {@code Foo.java}. */
  public static void openMentioned(Project project, String path, int line) {
    if (open(project, path, line)) {
      return;
    }
    String tail = path.replace('\\', '/');
    if (tail.startsWith("./")) {
      tail = tail.substring(2);
    }
    String suffix = tail;
    ReadAction.nonBlocking(() -> findByTail(project, suffix))
      .inSmartMode(project)
      .expireWith(project)
      .finishOnUiThread(ModalityState.defaultModalityState(), file -> {
        if (file != null) {
          new OpenFileDescriptor(project, file, Math.max(line - 1, 0), 0).navigate(true);
        }
      })
      .submit(AppExecutorUtil.getAppExecutorService());
  }

  /** The project file whose path ends with {@code tail}, the shortest when several do. Needs a read action. */
  private static @Nullable VirtualFile findByTail(Project project, String tail) {
    String name = tail.substring(tail.lastIndexOf('/') + 1);
    VirtualFile best = null;
    for (VirtualFile file : FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))) {
      String filePath = file.getPath();
      boolean matches = filePath.equals(tail) || filePath.endsWith("/" + tail);
      if (matches && (best == null || filePath.length() < best.getPath().length())) {
        best = file;
      }
    }
    return best;
  }

  /** @param target where review comments on the diff go. Must run on the EDT. */
  public static void showDiff(Project project, EditPreview preview, String title, ReviewTarget target) {
    DiffManager.getInstance().showDiff(project, request(project, preview, title, TakoBundle.message("diff.current"),
      TakoBundle.message("diff.proposed"), target));
  }

  /**
   * One diff window stepping through the files. Must run on the EDT.
   *
   * @param targetOf where review comments on a file's diff go
   */
  public static void showDiffs(Project project, List<EditPreview> previews,
    Function<EditPreview, ReviewTarget> targetOf) {
    List<DiffRequest> requests = new ArrayList<>();
    for (EditPreview preview : previews) {
      String title = relativeOrAbsolute(project, preview.filePath());
      requests.add(request(project, preview, title, TakoBundle.message("diff.before"), TakoBundle.message("diff.now"),
        targetOf.apply(preview)));
    }
    DiffManager.getInstance().showDiff(project, new SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT);
  }

  /** One diff window stepping through the files, each read when the window gets to it. Must run on the EDT. */
  public static void showGitDiffs(Project project, GitDiffs.Changes changes, String beforeTitle, String afterTitle,
    Map<String, String> environment) {
    List<GitDiffProducer> producers = new ArrayList<>();
    for (GitDiffs.Change change : changes.files()) {
      producers.add(new GitDiffProducer(project, changes, change, beforeTitle, afterTitle, environment,
        GitDiffs.MAX_FILE_BYTES));
    }
    DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain.fromProducers(producers),
      DiffDialogHints.DEFAULT);
  }

  private static SimpleDiffRequest request(Project project, EditPreview preview, String title, String beforeTitle,
    String afterTitle, ReviewTarget target) {
    String fileName = Path.of(preview.filePath()).getFileName().toString();
    FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName);
    DiffContentFactory contents = DiffContentFactory.getInstance();
    SimpleDiffRequest request = new SimpleDiffRequest(title,
      contents.create(project, preview.before(), fileType),
      contents.create(project, preview.after(), fileType),
      beforeTitle, afterTitle);
    request.putUserData(ReviewTarget.KEY, target);
    return request;
  }

  /** The path as the agent reads it: absolute against the project when it is relative, else as given. */
  public static String absolute(Project project, String path) {
    Path resolved = resolve(project, path);
    return resolved == null ? path : resolved.toString();
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

  private record DatedFile(
    String path,
    long modified
  ) {
  }
}
