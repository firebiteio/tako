package io.firebite.tako.ide.context;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.firebite.tako.TakoBundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Turns what the IDE shows into prompt context. Call on the EDT or in a read action. */
public final class IdeContext {
  private static final int MAX_PROBLEMS = 50;

  private IdeContext() {
  }

  public static @Nullable ContextItem selection(Project project, Editor editor) {
    SelectionModel selection = editor.getSelectionModel();
    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
    if (!selection.hasSelection() || file == null) {
      return null;
    }
    Document document = editor.getDocument();
    int startLine = document.getLineNumber(selection.getSelectionStart()) + 1;
    int endLine = document.getLineNumber(Math.max(selection.getSelectionStart(), selection.getSelectionEnd() - 1)) + 1;
    String path = file.getPath();
    String text = "Selected in " + path + " (lines " + startLine + "-" + endLine + "):\n```"
      + fenceLanguage(file) + "\n" + selection.getSelectedText() + "\n```";
    return ContextItem.create(ContextItem.Kind.SELECTION, file.getName() + ":" + startLine + "-" + endLine, text);
  }

  public static ContextItem file(VirtualFile file) {
    return ContextItem.create(ContextItem.Kind.FILE, file.getName(), "Referenced file: " + file.getPath());
  }

  public static @Nullable ContextItem problems(Project project, Editor editor) {
    Document document = editor.getDocument();
    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
    if (file == null) {
      return null;
    }
    List<String> lines = new ArrayList<>();
    DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WEAK_WARNING, 0,
      document.getTextLength(), (HighlightInfo info) -> {
        if (info.getDescription() != null && lines.size() < MAX_PROBLEMS) {
          int line = document.getLineNumber(info.getStartOffset()) + 1;
          lines.add("L" + line + " " + info.getSeverity().getName() + ": " + info.getDescription());
        }
        return true;
      });
    String path = file.getPath();
    if (lines.isEmpty()) {
      return ContextItem.create(ContextItem.Kind.PROBLEMS, TakoBundle.message("context.no.problems", file.getName()),
        "The IDE reports no problems in " + path + ".");
    }
    String text = "IDE inspections in " + path + ":\n" + String.join("\n", lines);
    return ContextItem.create(ContextItem.Kind.PROBLEMS,
      TakoBundle.message("context.problems", lines.size(), file.getName()), text);
  }

  private static String fenceLanguage(VirtualFile file) {
    String extension = file.getExtension();
    return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
  }
}
