package io.firebite.tako.chat.thread;

import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ReviewTarget;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.GitDiffs;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.ui.bridge.message.ChatError;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/** The diffs a thread shows: of a proposed edit, of a turn, and of its git checkout. */
public final class ChatDiffs {
  private final Project project;
  private final ChatView view;
  private final ThreadStore.Record record;
  private final TurnChanges changes;
  private final Supplier<@Nullable AgentSession> session;
  private final Supplier<String> agentName;
  private final Supplier<@Nullable String> gitFolder;

  ChatDiffs(Project project, ChatView view, ThreadStore.Record record, TurnChanges changes,
    Supplier<@Nullable AgentSession> session, Supplier<String> agentName, Supplier<@Nullable String> gitFolder) {
    this.project = project;
    this.view = view;
    this.record = record;
    this.changes = changes;
    this.session = session;
    this.agentName = agentName;
    this.gitFolder = gitFolder;
  }

  /** Shows what the tool call a permission prompt is about would change. */
  public void showProposedEdit(String requestId) {
    Async.inBackground(() -> {
      AgentSession live = this.session.get();
      EditPreview preview = live == null ? null : live.proposedEdit(requestId);
      if (preview == null) {
        this.postError(TakoBundle.message("error.no.diff"));
        return;
      }
      String title = TakoBundle.message("diff.title", this.agentName.get(),
        Path.of(preview.filePath()).getFileName());
      ReviewTarget target = ReviewTarget.proposed(this.record.id, IdeFiles.absolute(this.project, preview.filePath()));
      Async.onEdt(() -> IdeFiles.showDiff(this.project, preview, title, target));
    });
  }

  /**
   * Shows what the turn changed.
   *
   * @param turn the chat's number for the turn's prompt
   * @param path one file of the turn, or all of them when null
   */
  public void showTurnDiff(int turn, @Nullable String path) {
    Async.inBackground(() -> {
      List<EditPreview> previews = this.changes.previews(turn, path);
      if (previews.isEmpty()) {
        this.postError(TakoBundle.message("error.no.turn.changes"));
        return;
      }
      Async.onEdt(() -> IdeFiles.showDiffs(this.project, previews,
        preview -> ReviewTarget.turn(this.record.id, IdeFiles.absolute(this.project, preview.filePath()))));
    });
  }

  /** What the thread's checkout can show a diff of, or null without a folder. Reads git, so call off the EDT. */
  public @Nullable GitDiffs.Sources gitDiffSources() throws Worktrees.GitException {
    String directory = this.gitFolder.get();
    return directory == null ? null : GitDiffs.sources(Path.of(directory), EnvironmentUtil.getEnvironmentMap());
  }

  /** Shows what the source changed in the thread's checkout. Works in any state, since it only reads git. */
  public void showGitDiff(GitDiffs.Source source) {
    Async.inBackground(() -> {
      String directory = this.gitFolder.get();
      if (directory == null) {
        return;
      }
      Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
      GitDiffs.Changes changes;
      try {
        changes = GitDiffs.changes(Path.of(directory), source, environment);
      } catch (Worktrees.GitException e) {
        this.postError(TakoBundle.message("error.git.diff", e.getMessage()));
        return;
      }
      if (changes.files().isEmpty()) {
        this.postError(switch (source) {
          case GitDiffs.Uncommitted uncommitted -> TakoBundle.message("error.git.diff.empty.uncommitted");
          case GitDiffs.Branch branch -> TakoBundle.message("error.git.diff.empty.branch");
          case GitDiffs.Commit commit -> TakoBundle.message("error.git.diff.empty.commit");
        });
        return;
      }
      String before = changes.beforeName() != null ? changes.beforeName() : TakoBundle.message("diff.git.empty");
      String after = changes.afterName() != null ? changes.afterName() : TakoBundle.message("diff.git.working.tree");
      Async.onEdt(() -> IdeFiles.showGitDiffs(this.project, changes, before, after, environment));
    });
  }

  private void postError(String text) {
    this.view.post(new ChatError(text));
  }
}
