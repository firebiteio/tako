package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ReviewTarget;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Parallel runs: one prompt sent to several threads at once, each working in its own git worktree off the same commit,
 * then compared, and one run's changes applied to the main checkout.
 */
final class Benches {
  private static final Logger LOG = Logger.getInstance(Benches.class);
  private static final int ID_LENGTH = 4;

  private final Project project;
  private final ChatService service;
  private final ThreadStore store;
  /** Benches whose winner is being applied, which must not be discarded meanwhile. */
  private final Set<String> applying = ConcurrentHashMap.newKeySet();

  Benches(Project project, ChatService service, ThreadStore store) {
    this.project = project;
    this.service = service;
    this.store = store;
  }

  void post() {
    List<ChatMessage.BenchItem> items = new ArrayList<>();
    for (ThreadStore.Bench bench : this.store.benches()) {
      items.add(bench.toItem());
    }
    this.service.post(new ChatMessage.Benches(items));
  }

  /** Checks the folder is in a git repository and asks before leaving uncommitted changes out. Must run on the EDT. */
  void create(BenchRequest request, @Nullable String projectPath, String folder) {
    Async.inBackground(() -> {
      Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
      Path repository;
      String commit;
      boolean isDirty;
      try {
        repository = Worktrees.topLevel(Path.of(folder), environment);
        commit = Worktrees.head(repository, environment);
        isDirty = Worktrees.isDirty(repository, environment);
      } catch (Worktrees.GitException e) {
        this.fail(request, TakoBundle.message("error.bench.no.git", e.getMessage()));
        return;
      }
      Async.onEdt(() -> {
        if (isDirty && !this.confirmDirty()) {
          this.fail(request, "");
          return;
        }
        Async.inBackground(() -> this.addWorktrees(request, projectPath, folder, repository, commit, environment));
      });
    });
  }

  /** What each run changed, in run order, or null for an unknown bench. Reads git, so call off the EDT. */
  @Nullable
  List<ChatMessage.RunChanges> compare(String benchId) {
    ThreadStore.Bench bench = this.store.findBench(benchId);
    if (bench == null) {
      return null;
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    List<CompletableFuture<ChatMessage.RunChanges>> lookups = new ArrayList<>();
    for (ThreadStore.Run run : bench.runs) {
      lookups.add(CompletableFuture.supplyAsync(() -> changesOf(run, bench.baseCommit, environment),
        AppExecutorUtil.getAppExecutorService()));
    }
    List<ChatMessage.RunChanges> runs = new ArrayList<>();
    for (CompletableFuture<ChatMessage.RunChanges> lookup : lookups) {
      runs.add(lookup.join());
    }
    return runs;
  }

  private static ChatMessage.RunChanges changesOf(ThreadStore.Run run, String commit,
    Map<String, String> environment) {
    try {
      Worktrees.DiffStat stat = Worktrees.diffStat(Path.of(run.worktree), commit, environment);
      return new ChatMessage.RunChanges(stat.files(), stat.added(), stat.removed(), null);
    } catch (Worktrees.GitException e) {
      return new ChatMessage.RunChanges(0, 0, 0, e.getMessage());
    }
  }

  /** Reads git, so call off the EDT. */
  void showDiff(String benchId, int runIndex) {
    ThreadStore.Bench bench = this.store.findBench(benchId);
    ThreadStore.Run run = runOf(bench, runIndex);
    if (bench == null || run == null) {
      return;
    }
    try {
      List<EditPreview> previews = Worktrees.changes(Path.of(run.worktree), bench.baseCommit,
        EnvironmentUtil.getEnvironmentMap());
      if (previews.isEmpty()) {
        this.service.post(new ChatMessage.Error(TakoBundle.message("error.bench.no.changes", runIndex)));
        return;
      }
      Async.onEdt(() -> IdeFiles.showDiffs(this.project, previews,
        preview -> ReviewTarget.run(run.thread, Path.of(run.worktree).resolve(preview.filePath()).toString())));
    } catch (Worktrees.GitException e) {
      this.service.post(new ChatMessage.Error(TakoBundle.message("error.bench.diff", runIndex, e.getMessage())));
    }
  }

  /** Applies the run's changes to the main checkout, then discards the bench. Reads git, so call off the EDT. */
  void pickWinner(String benchId, int runIndex) {
    ThreadStore.Bench bench = this.store.findBench(benchId);
    ThreadStore.Run run = runOf(bench, runIndex);
    if (bench == null || run == null || !this.applying.add(bench.id)) {
      return;
    }
    try {
      Worktrees.applyTo(Path.of(bench.repository), Path.of(run.worktree), bench.baseCommit,
        EnvironmentUtil.getEnvironmentMap());
    } catch (Worktrees.GitException e) {
      this.applying.remove(bench.id);
      this.service.post(new ChatMessage.Error(TakoBundle.message("error.bench.apply", runIndex, e.getMessage())));
      return;
    }
    VfsUtil.markDirtyAndRefresh(true, true, true, Path.of(bench.repository).toFile());
    Async.onEdt(() -> {
      this.applying.remove(bench.id);
      this.discard(bench.id);
    });
  }

  /** Closes the bench's threads, then removes its worktrees and branches. Must run on the EDT. */
  void discard(String benchId) {
    ThreadStore.Bench bench = this.store.findBench(benchId);
    if (bench == null || this.applying.contains(bench.id)) {
      return;
    }
    List<String> threads = new ArrayList<>();
    for (ThreadStore.Run run : bench.runs) {
      threads.add(run.thread);
    }
    this.service.closeAll(threads);
    this.store.removeBench(bench.id);
    this.post();
    Async.inBackground(() -> this.removeWorktrees(bench));
  }

  private void addWorktrees(BenchRequest request, @Nullable String projectPath, String folder, Path repository,
    String commit, Map<String, String> environment) {
    ThreadStore.Bench bench = new ThreadStore.Bench();
    bench.id = UUID.randomUUID().toString();
    String title = ChatController.titleOf(request.prompt().text());
    bench.title = title.isEmpty() ? TakoBundle.message("bench.untitled") : title;
    bench.projectPath = projectPath;
    bench.repository = slashes(repository);
    bench.baseCommit = commit;
    String slug = Worktrees.slug(bench.title) + "-" + bench.id.substring(0, ID_LENGTH);
    Path benchFolder = Worktrees.benchFolder(repository, slug);
    Path subfolder = Path.of(folder).startsWith(repository) ? repository.relativize(Path.of(folder)) : Path.of("");
    List<String> workDirectories = new ArrayList<>();
    for (int i = 1; i <= request.runs().size(); i++) {
      ThreadStore.Run run = new ThreadStore.Run();
      Path location = benchFolder.resolve(String.valueOf(i));
      run.worktree = slashes(location);
      run.branch = slug + "-" + i;
      try {
        Worktrees.add(repository, location, run.branch, commit, environment);
      } catch (Worktrees.GitException e) {
        this.removeWorktrees(bench);
        this.fail(request, TakoBundle.message("error.bench.worktree", e.getMessage()));
        return;
      }
      bench.runs.add(run);
      Path workDirectory = location.resolve(subfolder);
      if (!Files.isDirectory(workDirectory)) {
        this.removeWorktrees(bench);
        this.fail(request, TakoBundle.message("error.bench.folder.missing", subfolder));
        return;
      }
      workDirectories.add(slashes(workDirectory));
    }
    Async.onEdt(() -> this.startRuns(request, bench, workDirectories));
  }

  private void startRuns(BenchRequest request, ThreadStore.Bench bench, List<String> workDirectories) {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    List<ThreadStore.Record> records = new ArrayList<>();
    List<String> threads = new ArrayList<>();
    for (int i = 0; i < bench.runs.size(); i++) {
      BenchRequest.RunSpec spec = request.runs().get(i);
      ThreadStore.Record record = this.store.create(null, bench.title, bench.projectPath,
        AgentRegistry.find(spec.agent(), environment).id());
      record.model = spec.model();
      record.mode = spec.mode();
      record.effort = spec.effort();
      record.workPath = workDirectories.get(i);
      bench.runs.get(i).thread = record.id;
      records.add(record);
      threads.add(record.id);
    }
    this.store.addBench(bench);
    this.post();
    for (ThreadStore.Record record : records) {
      this.service.openThread(record);
    }
    this.service.select(threads.getFirst());
    this.service.close(request.source());
    for (String thread : threads) {
      this.service.post(request.prompt(), thread);
    }
  }

  private boolean confirmDirty() {
    return Messages.showOkCancelDialog(this.project, TakoBundle.message("dialog.bench.dirty.message"),
      TakoBundle.message("dialog.bench.dirty.title"), TakoBundle.message("dialog.bench.dirty.ok"),
      Messages.getCancelButton(), Messages.getWarningIcon()) == Messages.OK;
  }

  /** @param error why, empty when the person cancelled */
  private void fail(BenchRequest request, String error) {
    this.service.post(new ChatMessage.BenchFailed(request.prompt(), error), request.source());
  }

  /** Removes the worktrees, then the bench's folder and the folder of all benches once they are empty. */
  private void removeWorktrees(ThreadStore.Bench bench) {
    if (bench.runs.isEmpty()) {
      return;
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    Path repository = Path.of(bench.repository);
    for (ThreadStore.Run run : bench.runs) {
      try {
        Worktrees.remove(repository, Path.of(run.worktree), run.branch, environment);
      } catch (Worktrees.GitException e) {
        LOG.warn("Could not remove the worktree " + run.worktree, e);
        this.service.post(new ChatMessage.Error(TakoBundle.message("error.bench.remove", run.worktree,
          e.getMessage())));
      }
    }
    Worktrees.prune(repository, environment);
    Path benchFolder = Path.of(bench.runs.getFirst().worktree).getParent();
    deleteIfEmpty(benchFolder);
    deleteIfEmpty(benchFolder.getParent());
  }

  private static void deleteIfEmpty(@Nullable Path folder) {
    if (folder == null) {
      return;
    }
    try {
      Files.deleteIfExists(folder);
    } catch (DirectoryNotEmptyException ignored) {
    } catch (IOException e) {
      LOG.debug("Could not delete " + folder, e);
    }
  }

  private static @Nullable ThreadStore.Run runOf(@Nullable ThreadStore.Bench bench, int runIndex) {
    return bench == null || runIndex < 1 || runIndex > bench.runs.size() ? null : bench.runs.get(runIndex - 1);
  }

  private static String slashes(Path path) {
    return path.toString().replace('\\', '/');
  }
}
