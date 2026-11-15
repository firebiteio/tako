package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.GitWorktree;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.bridge.message.ChatError;
import io.firebite.tako.ui.bridge.message.workspace.Select;
import io.firebite.tako.ui.bridge.message.workspace.ThreadAdded;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** Threads that implement a plan another thread's agent proposed, and plans saved as Markdown. */
public final class PlanThreads {
  private static final Logger LOG = Logger.getInstance(PlanThreads.class);

  private final Project project;
  private final ChatService service;
  private final ThreadStore store;

  PlanThreads(Project project, ChatService service, ThreadStore store) {
    this.project = project;
    this.service = service;
    this.store = store;
  }

  /**
   * Starts a thread that implements the plan the thread's agent proposed, and selects it. It runs the same agent, model
   * and effort, not in plan mode. Shows failures.
   *
   * @param worktree work in a new git worktree on a new branch, else in the planning thread's folder
   * @return completes on the EDT with the new thread's id, or null when none started
   */
  public CompletableFuture<@Nullable String> implement(String id, String plan, boolean worktree) {
    CompletableFuture<@Nullable String> done = new CompletableFuture<>();
    ThreadStore.Record source = this.store.find(id);
    if (source == null) {
      this.fail(done, TakoBundle.message("error.plan.thread.gone"));
      return done;
    }
    String folder = this.service.workFolder(source);
    Async.inBackground(() -> {
      try {
        if (folder.isEmpty() || !Files.isDirectory(Path.of(folder))) {
          Async.onEdt(() -> this.fail(done, TakoBundle.message("error.plan.folder", folder)));
          return;
        }
        GitWorktree.Worktree created = null;
        if (worktree) {
          String title = Plans.title(plan);
          created = GitWorktree.create(Path.of(folder), title != null ? title : Plans.UNTITLED,
            EnvironmentUtil.getEnvironmentMap());
          if (created == null) {
            Async.onEdt(() -> this.fail(done, TakoBundle.message("error.plan.worktree", folder)));
            return;
          }
        }
        GitWorktree.Worktree made = created;
        Async.onEdt(() -> this.start(done, source, plan, folder, made));
      } catch (RuntimeException e) {
        LOG.warn("Could not start a thread implementing the plan of thread " + id, e);
        Async.onEdt(() -> this.fail(done, Objects.toString(e.getMessage(), e.toString())));
      }
    });
    return done;
  }

  /**
   * Completes with null when the thread did not start, even if it was stored, so the plan's approval stays.
   *
   * @param folder   the planning thread's folder
   * @param worktree the worktree made for the new thread, removed again when no thread got it
   */
  private void start(CompletableFuture<@Nullable String> done, ThreadStore.Record source, String plan,
    String folder, @Nullable GitWorktree.Worktree worktree) {
    if (this.store.find(source.id) != source) {
      this.dropWorktree(folder, worktree);
      this.fail(done, TakoBundle.message("error.plan.thread.gone"));
      return;
    }
    ThreadStore.Record record;
    try {
      record = this.create(source, plan, worktree);
    } catch (RuntimeException e) {
      LOG.warn("Could not store a thread implementing the plan of thread " + source.id, e);
      this.dropWorktree(folder, worktree);
      this.fail(done, Objects.toString(e.getMessage(), e.toString()));
      return;
    }
    try {
      this.service.post(new ThreadAdded(record.toItem()));
      this.store.setActiveId(record.id);
      this.service.post(new Select(record.id));
      this.service.newController(record).open(Plans.implementPrompt(plan));
    } catch (RuntimeException e) {
      LOG.warn("Could not open the thread implementing the plan of thread " + source.id, e);
      this.fail(done, Objects.toString(e.getMessage(), e.toString()));
      return;
    }
    done.complete(record.id);
  }

  private void dropWorktree(String repository, @Nullable GitWorktree.Worktree worktree) {
    if (worktree != null) {
      Async.inBackground(() -> removeWorktree(Path.of(repository), worktree));
    }
  }

  private static void removeWorktree(Path repository, GitWorktree.Worktree worktree) {
    try {
      Worktrees.remove(repository, worktree.folder(), worktree.branch(), EnvironmentUtil.getEnvironmentMap());
    } catch (Worktrees.GitException e) {
      LOG.warn("Could not remove the worktree " + worktree.folder(), e);
    }
  }

  private void fail(CompletableFuture<@Nullable String> done, String error) {
    this.service.post(new ChatError(error));
    done.complete(null);
  }

  /**
   * Stores a thread for implementing the plan, beside the planning thread and like it but not in plan mode.
   *
   * @param worktree the worktree made for it, whose {@code .worktreesetup} runs first; null to work where the source does
   */
  ThreadStore.Record create(ThreadStore.Record source, String plan, @Nullable GitWorktree.Worktree worktree) {
    String projectPath = source.projectPath;
    this.service.folders().ensure(projectPath);
    String agent = source.agent();
    String title = Plans.title(plan);
    ThreadStore.Record record = this.store.create(null, ChatController.titleOf(title != null
      ? TakoBundle.message("thread.implement.title", title)
      : TakoBundle.message("thread.implement.title.default")), projectPath, agent);
    String defaultMode = ClaudeBackend.ID.equals(agent) ? ChatSettings.state().claudeDefaults().mode() : "";
    record.model = source.model;
    record.mode = Plans.implementMode(agent, source.mode, defaultMode);
    record.effort = source.effort;
    record.workPath = worktree != null ? worktree.folder().toString() : source.workPath;
    record.pendingSetup = worktree != null
      ? ThreadStore.PendingSetup.of(worktree.repository(), worktree.folder())
      : null;
    this.store.save();
    return record;
  }

  /** Asks where to save the plan as Markdown, in the thread's folder by default, then opens the file. */
  public void save(String id, String plan) {
    Async.onEdt(() -> this.chooseFile(id, plan));
  }

  private void chooseFile(String id, String plan) {
    ThreadStore.Record record = this.store.find(id);
    String folder = record != null ? this.service.workFolder(record) : this.service.folders().folderOf(null);
    Path directory = folder.isEmpty() || !Files.isDirectory(Path.of(folder)) ? null : Path.of(folder);
    FileSaverDescriptor descriptor = new FileSaverDescriptor(TakoBundle.message("dialog.save.plan.title"),
      TakoBundle.message("dialog.save.plan.description"));
    descriptor.withExtensionFilter("md");
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, this.project)
      .save(directory, Plans.fileName(plan));
    if (target == null) {
      return;
    }
    try {
      VirtualFile file = IdeFiles.writeText(target.getFile().toPath(), Plans.fileText(plan));
      FileEditorManager.getInstance(this.project).openFile(file, true);
    } catch (IOException e) {
      this.service.post(new ChatError(TakoBundle.message("error.plan.save", Objects.toString(e.getMessage(), ""))));
    }
  }
}
