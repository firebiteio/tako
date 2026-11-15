package io.firebite.tako.chat.thread;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.git.ThreadGit;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.project.Checkpoints;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.Worktrees;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;

/** The thread's file checkpoints, one per prompt, which a rewind puts the files back to. All of it runs git. */
final class FileCheckpoints {
  private static final Logger LOG = Logger.getInstance(FileCheckpoints.class);
  /** How long a snapshot or restore waits for the editors to be saved, which a modal dialog holds up. */
  private static final long SAVE_TIMEOUT_SECONDS = 2;

  private final ThreadStore.Record record;
  private final ChatHost host;

  FileCheckpoints(ThreadStore.Record record, ChatHost host) {
    this.record = record;
    this.host = host;
  }

  /** Saves the editors and snapshots the files for the prompt after the first {@code prompts}. */
  void capture(String folder, int prompts, String typed) {
    try {
      this.saveDocuments();
      Checkpoints.capture(Path.of(folder), this.record.id, prompts, typed, EnvironmentUtil.getEnvironmentMap());
    } catch (Worktrees.GitException e) {
      LOG.debug("No file checkpoint for thread " + this.record.id, e);
    }
  }

  /** Drops the checkpoints of the prompt after the first {@code prompts} and later ones. */
  void forget(String folder, int prompts) {
    Checkpoints.forget(Path.of(folder), this.record.id, prompts, EnvironmentUtil.getEnvironmentMap());
  }

  /** The checkpoint of the prompt after the first {@code prompts}, if it was {@code prompt}. */
  @Nullable String find(String folder, int prompts, String prompt) {
    return Checkpoints.find(Path.of(folder), this.record.id, prompts, prompt, EnvironmentUtil.getEnvironmentMap());
  }

  /**
   * Why the files in the folder must not be put back now: a git action or another thread's agent works in its
   * repository. Null when nothing does.
   */
  @Nullable String blocker(String folder) {
    if (ThreadGit.hasActions(Path.of(folder))) {
      return TakoBundle.message("git.failed.running");
    }
    String topLevel = GitCommands.topLevel(Path.of(folder), EnvironmentUtil.getEnvironmentMap());
    String other = topLevel == null ? null : this.host.busyThreadIn(topLevel);
    return other == null ? null : TakoBundle.message("error.rewind.folder.busy", other);
  }

  /** Saves the editors, puts the files back and lets the IDE see the changed ones. */
  void restore(String folder, String commit) throws Worktrees.GitException {
    this.saveDocuments();
    List<File> refreshed = new ArrayList<>();
    for (Path path : Checkpoints.restore(Path.of(folder), this.record.id, commit,
      EnvironmentUtil.getEnvironmentMap())) {
      Path existing = path;
      while (existing != null && !Files.exists(existing)) {
        existing = existing.getParent();
      }
      if (existing != null) {
        refreshed.add(existing.toFile());
      }
    }
    if (!refreshed.isEmpty()) {
      VfsUtil.markDirtyAndRefresh(true, false, true, refreshed.toArray(new File[0]));
    }
  }

  /**
   * Writes unsaved editor changes to disk, so a snapshot or restore sees what the person sees. Waits for the EDT at
   * most {@link #SAVE_TIMEOUT_SECONDS}, then goes on with what is on disk.
   */
  private void saveDocuments() {
    CompletableFuture<Void> saved = new CompletableFuture<>();
    ApplicationManager.getApplication().invokeLater(() -> {
      FileDocumentManager.getInstance().saveAllDocuments();
      saved.complete(null);
    }, ModalityState.defaultModalityState());
    try {
      saved.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException | ExecutionException e) {
      LOG.debug("Editors not saved, e.g. while a dialog is open", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
