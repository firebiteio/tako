package io.firebite.tako.chat.thread;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.TreeSnapshot;
import io.firebite.tako.ide.project.Worktrees;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * The files the agent changes. The IDE refreshes them once a tool call that may have written them returns, and their
 * content from before each turn stays for the turn's diff. Files changed without a tool call reporting them, for
 * example by a script, are found by comparing the checkout with a snapshot taken at the turn's start.
 */
final class TurnChanges {
  private static final Logger LOG = Logger.getInstance(TurnChanges.class);
  private final Project project;
  private final Supplier<@Nullable String> gitFolder;
  private final Map<String, AgentEvent.ToolStarted> pendingTools = new ConcurrentHashMap<>();
  private final Map<Integer, Map<String, String>> originalsByTurn = new ConcurrentHashMap<>();
  private final Map<Integer, Unreported> unreportedByTurn = new ConcurrentHashMap<>();
  private final Set<Path> reported = ConcurrentHashMap.newKeySet();
  private volatile int currentTurn;
  private @Nullable TreeSnapshot snapshot;

  TurnChanges(Project project, Supplier<@Nullable String> gitFolder) {
    this.project = project;
    this.gitFolder = gitFolder;
  }

  /** Takes a snapshot of the checkout. Runs git, so call off the EDT. */
  synchronized void startTurn(int turn) {
    this.currentTurn = turn;
    this.reported.clear();
    this.closeSnapshot();
    String folder = this.gitFolder.get();
    if (folder == null) {
      return;
    }
    try {
      this.snapshot = TreeSnapshot.take(Path.of(folder), EnvironmentUtil.getEnvironmentMap());
    } catch (Worktrees.GitException | InvalidPathException e) {
      LOG.debug("No snapshot of " + folder, e);
    }
  }

  /**
   * The files the current turn changed without a tool call reporting them, for example through a script. Empty without
   * a snapshot. Call before the chat gets the turn's end. Runs git, so call off the EDT.
   */
  synchronized List<AgentEvent.Change> endTurn() {
    TreeSnapshot started = this.snapshot;
    this.snapshot = null;
    if (started == null) {
      return List.of();
    }
    List<AgentEvent.Change> changes;
    try (started) {
      changes = started.changes();
    } catch (Worktrees.GitException e) {
      LOG.warn("Could not read what the turn changed", e);
      return List.of();
    }
    for (String path : this.originalsByTurn.getOrDefault(this.currentTurn, Map.of()).keySet()) {
      this.report(path);
    }
    List<AgentEvent.Change> unreported = new ArrayList<>();
    for (AgentEvent.Change change : changes) {
      if (!this.reported.contains(Path.of(change.path()))) {
        unreported.add(change);
      }
    }
    if (!unreported.isEmpty()) {
      this.unreportedByTurn.put(this.currentTurn, new Unreported(started, unreported));
    }
    return unreported;
  }

  void onToolStarted(AgentEvent.ToolStarted tool) {
    this.pendingTools.put(tool.id(), tool);
  }

  void onToolFinished(AgentEvent.ToolFinished result) {
    AgentEvent.ToolStarted tool = this.pendingTools.remove(result.id());
    if (result.change() != null) {
      this.report(result.change().path());
    }
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
  synchronized void clear() {
    this.pendingTools.clear();
    this.originalsByTurn.clear();
    this.unreportedByTurn.clear();
    this.reported.clear();
    this.closeSnapshot();
  }

  /** Each file the turn changed, before the turn and now, or empty when the turn changed none. Reads files. */
  List<EditPreview> previews(int turn, @Nullable String onlyPath) {
    Map<String, String> originals = this.originalsByTurn.getOrDefault(turn, Map.of());
    List<EditPreview> previews = new ArrayList<>();
    for (Map.Entry<String, String> original : originals.entrySet()) {
      if (onlyPath == null || onlyPath.equals(original.getKey())) {
        previews.add(this.preview(original.getKey(), original.getValue()));
      }
    }
    Unreported unreported = this.unreportedByTurn.get(turn);
    if (unreported == null) {
      return previews;
    }
    for (AgentEvent.Change change : unreported.changes()) {
      if (onlyPath != null && !onlyPath.equals(change.path())) {
        continue;
      }
      try {
        String before = change.isNew() ? "" : unreported.snapshot().original(Path.of(change.path()));
        previews.add(this.preview(change.path(), before));
      } catch (Worktrees.GitException e) {
        LOG.warn("Could not read " + change.path() + " from before the turn", e);
      }
    }
    return previews;
  }

  private EditPreview preview(String path, String before) {
    Path resolved = IdeFiles.resolve(this.project, path);
    String now = resolved == null ? null : IdeFiles.readText(resolved);
    return new EditPreview(path, before, now == null ? "" : now);
  }

  private void report(String path) {
    Path resolved = IdeFiles.resolve(this.project, path);
    if (resolved != null) {
      this.reported.add(resolved);
    }
  }

  private void closeSnapshot() {
    if (this.snapshot != null) {
      this.snapshot.close();
      this.snapshot = null;
    }
  }

  /** A turn's unreported changes, and the snapshot from its start that holds their old content. */
  private record Unreported(
    TreeSnapshot snapshot,
    List<AgentEvent.Change> changes
  ) {
  }
}
