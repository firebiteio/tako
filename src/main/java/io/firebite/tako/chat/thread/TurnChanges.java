package io.firebite.tako.chat.thread;

import com.intellij.openapi.project.Project;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.ide.editor.IdeFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * The files the agent changes. The IDE refreshes them once a tool call that may have written them returns, and their
 * content from before each turn stays for the turn's diff.
 */
final class TurnChanges {
  private final Project project;
  private final Map<String, AgentEvent.ToolStarted> pendingTools = new ConcurrentHashMap<>();
  private final Map<Integer, Map<String, String>> originalsByTurn = new ConcurrentHashMap<>();
  private volatile int currentTurn;

  TurnChanges(Project project) {
    this.project = project;
  }

  void startTurn(int turn) {
    this.currentTurn = turn;
  }

  void onToolStarted(AgentEvent.ToolStarted tool) {
    this.pendingTools.put(tool.id(), tool);
  }

  void onToolFinished(AgentEvent.ToolFinished result) {
    AgentEvent.ToolStarted tool = this.pendingTools.remove(result.id());
    if (tool == null || result.isError()) {
      return;
    }
    if (result.change() != null) {
      IdeFiles.refresh(this.project, result.change().path());
    } else if (tool.kind() == ToolKind.EDIT || tool.kind() == ToolKind.COMMAND) {
      IdeFiles.refresh(this.project, null);
    }
  }

  /** Keeps the file's content from before its first change in the current turn. */
  void onFileEdited(String path, String originalContent) {
    this.originalsByTurn.computeIfAbsent(this.currentTurn, turn -> new ConcurrentHashMap<>())
      .putIfAbsent(path, originalContent);
  }

  /** Forgets every turn, for when the chat numbers its turns from the start again. */
  void clear() {
    this.pendingTools.clear();
    this.originalsByTurn.clear();
  }

  /** Each file the turn changed, before the turn and now, or empty when the turn changed none. Reads files. */
  List<EditPreview> previews(int turn, @Nullable String onlyPath) {
    Map<String, String> originals = this.originalsByTurn.getOrDefault(turn, Map.of());
    List<EditPreview> previews = new ArrayList<>();
    for (Map.Entry<String, String> original : originals.entrySet()) {
      if (onlyPath != null && !onlyPath.equals(original.getKey())) {
        continue;
      }
      Path path = IdeFiles.resolve(this.project, original.getKey());
      String now = path == null ? null : IdeFiles.readText(path);
      previews.add(new EditPreview(original.getKey(), original.getValue(), now == null ? "" : now));
    }
    return previews;
  }
}
