package io.firebite.tako.chat;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.ExecutableLocator;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.watch.ForgeCall;
import io.firebite.tako.chat.watch.PullRequestWatch;
import io.firebite.tako.chat.watch.PullRequestWatcher;
import io.firebite.tako.chat.watch.WatchException;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.project.Forge;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ui.bridge.message.thread.Notice;
import io.firebite.tako.ui.bridge.message.workspace.ThreadUpdated;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/** The watches of the threads' pull requests, which prompt a thread when its pull request has news. */
public final class PullRequestWatches {
  private static final Logger LOG = Logger.getInstance(PullRequestWatches.class);

  private final Project project;
  private final ChatService service;
  private final ThreadStore store;
  private final PullRequestWatcher watcher;

  PullRequestWatches(Project project, ChatService service, ThreadStore store) {
    this.project = project;
    this.service = service;
    this.store = store;
    this.watcher = new PullRequestWatcher(store, PullRequestWatches::runForgeCli, new Host(),
      System::currentTimeMillis);
  }

  /** Runs gh or glab, found anew each time, so installing it while the IDE runs is enough. */
  private static ForgeCall.Result runForgeCli(Forge forge, Path directory, List<String> args) {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    String cli = ExecutableLocator.locate("", forge.cli(), List.of(), environment, SystemInfo.isWindows);
    return ForgeCall.run(cli != null ? cli : forge.cli(), environment, directory, args);
  }

  PullRequestWatcher watcher() {
    return this.watcher;
  }

  /**
   * Watches the pull request of the thread's branch, as the header shows it. Shows why when it can't.
   *
   * @return completes on the EDT once the watch started or failed
   */
  public CompletableFuture<Void> start(String id) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    Async.inBackground(() -> {
      String failure = null;
      try {
        this.watcher.start(id, null, false);
      } catch (WatchException e) {
        failure = failure(e);
      } catch (RuntimeException e) {
        LOG.warn("Could not watch the pull request of thread " + id, e);
        failure = TakoBundle.message("watch.failed.other", Objects.toString(e.getMessage(), e.toString()));
      }
      String text = failure;
      Async.onEdt(() -> {
        if (text != null) {
          this.service.post(new Notice(text), id);
        }
        done.complete(null);
      });
    });
    return done;
  }

  public void stop(String id) {
    this.watcher.stop(id);
  }

  /** What the chat says when a watch did not start, or null when nothing needs saying. */
  private static @Nullable String failure(WatchException e) {
    return switch (e.reason()) {
      case NO_THREAD, ARCHIVED, STARTING -> null;
      case NO_PULL_REQUEST -> TakoBundle.message("watch.failed.no.pull.request");
      case BAD_URL -> TakoBundle.message("watch.failed.bad.url", e.detail());
      case NOT_OPEN -> TakoBundle.message("watch.failed.not.open", e.detail());
      case MISSING -> TakoBundle.message("watch.failed.missing", e.detail());
      case LOGGED_OUT -> TakoBundle.message("watch.failed.logged.out", e.detail());
      case RATE_LIMITED -> TakoBundle.message("watch.failed.rate.limited", e.detail());
      case FAILED -> TakoBundle.message("watch.failed.other", e.detail());
    };
  }

  /** What the pull request watcher needs of the open threads and the chat. */
  private final class Host implements PullRequestWatcher.Host {

    @Override
    public @Nullable ChatController.State state(String threadId) {
      ChatController controller = PullRequestWatches.this.service.controller(threadId);
      return controller == null ? null : controller.state();
    }

    @Override
    public Path folder(ThreadStore.Record record) {
      String basePath = PullRequestWatches.this.project.getBasePath();
      String folder = record.folder(basePath);
      if (folder != null && Files.isDirectory(Path.of(folder))) {
        return Path.of(folder);
      }
      return basePath != null && Files.isDirectory(Path.of(basePath))
        ? Path.of(basePath)
        : Path.of(System.getProperty("user.home"));
    }

    @Override
    public @Nullable GitInfo.PullRequest lookup(ThreadStore.Record record) {
      String folder = record.folder(PullRequestWatches.this.project.getBasePath());
      if (folder == null || !Files.isDirectory(Path.of(folder))) {
        return null;
      }
      return GitInfo.pullRequest(Path.of(folder), EnvironmentUtil.getEnvironmentMap());
    }

    @Override
    public boolean deliver(ThreadStore.Record record, PullRequestWatch watch, String prompt) {
      AtomicBoolean isDelivered = new AtomicBoolean();
      ApplicationManager.getApplication().invokeAndWait(() -> {
        ChatService service = PullRequestWatches.this.service;
        ChatController controller = service.controller(record.id);
        ChatController.State state = controller == null ? null : controller.state();
        if (!service.isAttached() || PullRequestWatches.this.project.isDisposed()
          || PullRequestWatches.this.store.find(record.id) != record || record.archived || record.watch != watch
          || !PullRequestWatcher.canWake(state)) {
          return;
        }
        service.prompt(record, prompt);
        isDelivered.set(true);
      }, ModalityState.any());
      return isDelivered.get();
    }

    @Override
    public void stopped(ThreadStore.Record record, String number, PullRequestWatcher.Stop reason, String message) {
      Forge forge = Forge.ofNumber(number);
      String cli = (forge != null ? forge : Forge.GITHUB).cli();
      String text = switch (reason) {
        case MISSING -> TakoBundle.message("watch.stopped.missing", number, cli);
        case LOGGED_OUT -> TakoBundle.message("watch.stopped.logged.out", number, cli);
        case FAILURES -> TakoBundle.message("watch.stopped.failures", number, PullRequestWatcher.MAX_FAILURES, message);
      };
      PullRequestWatches.this.service.post(new Notice(text), record.id);
      Async.onEdt(() -> PullRequestWatches.this.service.requestAttention(record, text));
    }

    @Override
    public void changed(ThreadStore.Record record) {
      Async.onEdt(() -> {
        if (PullRequestWatches.this.store.find(record.id) == record) {
          PullRequestWatches.this.service.post(new ThreadUpdated(record.toItem()));
        }
      });
    }
  }
}
