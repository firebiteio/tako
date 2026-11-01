package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.settings.ChatSettings;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Wakes snoozed threads whose time came and archives threads the settings say are done: their pull request was merged
 * or closed, or nobody touched them for some days.
 */
public final class ThreadUpkeep {
  private static final Logger LOG = Logger.getInstance(ThreadUpkeep.class);
  private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
  /** How long a thread whose pull request ended stays after its last prompt or opening, as work may go on after it. */
  static final long PULL_REQUEST_GRACE_MILLIS = 60L * 60 * 1000;

  private final ChatService service;
  private final ThreadStore store;
  /** Whether a pull request refresh still waits for gh or glab, so a slow network does not pile them up. */
  private final AtomicBoolean refreshing = new AtomicBoolean();

  ThreadUpkeep(ChatService service, ThreadStore store) {
    this.service = service;
    this.store = store;
  }

  static boolean isDue(ThreadStore.Record record, long now) {
    return record.snoozedUntil > 0 && record.snoozedUntil <= now;
  }

  /**
   * Why the thread is done by its own record, or null. Leaves out what needs more than the record: whether it is shown,
   * works, is a run or has sub threads.
   */
  static @Nullable Reason settleReason(ThreadStore.Record record, Rules rules, long now) {
    if (record.archived || record.snoozedUntil > 0 || record.unread) {
      return null;
    }
    GitInfo.PullRequest pullRequest = record.pullRequest;
    long idleSince = Math.max(record.updatedAt, record.seenAt);
    if (rules.pullRequest() && record.workPath != null && pullRequest != null && isClosed(pullRequest)
      && !pullRequest.url().equals(record.settledPullRequest) && now - idleSince >= PULL_REQUEST_GRACE_MILLIS) {
      return Reason.PULL_REQUEST;
    }
    if (rules.idle() && !record.pinned && now - idleSince >= rules.idleDays() * DAY_MILLIS) {
      return Reason.IDLE;
    }
    return null;
  }

  private static boolean isClosed(GitInfo.PullRequest pullRequest) {
    return "merged".equals(pullRequest.state()) || "closed".equals(pullRequest.state());
  }

  /**
   * Wakes the threads whose snooze ended as unread, then archives the done ones. Saves the threads once for all of
   * them. Must run on the EDT.
   */
  void tick(long now) {
    List<ThreadStore.Record> woken = new ArrayList<>();
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && isDue(record, now)) {
        record.snoozedUntil = 0;
        record.unread = true;
        record.seenAt = now;
        woken.add(record);
      }
    }
    this.service.postUpdates(woken);
    Rules rules = Rules.of(ChatSettings.state());
    if (!rules.pullRequest() && !rules.idle()) {
      return;
    }
    List<ThreadStore.Record> records = this.store.all();
    Set<String> parents = new HashSet<>();
    for (ThreadStore.Record record : records) {
      if (!record.archived && record.parentId != null) {
        parents.add(record.parentId);
      }
    }
    List<ThreadStore.Record> done = new ArrayList<>();
    for (ThreadStore.Record record : records) {
      Reason reason = settleReason(record, rules, now);
      if (reason == null || parents.contains(record.id) || this.isKept(record)) {
        continue;
      }
      if (reason == Reason.PULL_REQUEST && record.pullRequest != null) {
        record.settledPullRequest = record.pullRequest.url();
      }
      LOG.info("Archiving thread " + record.id + " automatically: " + reason);
      done.add(record);
    }
    this.service.archiveAll(done);
  }

  /** Whether the thread stays though done: it is shown, works, waits, is hidden by a snooze or is a run. */
  private boolean isKept(ThreadStore.Record record) {
    if (record.id.equals(this.store.activeId()) || this.store.isRun(record.id) || this.store.isSnoozeHidden(record)) {
      return true;
    }
    ChatController controller = this.service.controller(record.id);
    if (controller == null) {
      return false;
    }
    ChatController.State state = controller.state();
    return state.isBusy() || state == ChatController.State.WAITING;
  }

  /**
   * Asks for the state of the open pull requests of threads in their own worktree, while the pull request rule is on.
   * Asks for each by its url or number, so a merge that deleted the branch or the worktree is still seen. Reads the
   * threads here, asks gh or glab in the background and stores the answers on the EDT, unless the thread got another
   * pull request meanwhile. Skips while the last refresh still runs. Must run on the EDT.
   */
  void refreshPullRequests() {
    if (!Rules.of(ChatSettings.state()).pullRequest()) {
      return;
    }
    List<Ask> asks = new ArrayList<>();
    for (ThreadStore.Record record : this.store.all()) {
      GitInfo.PullRequest known = record.pullRequest;
      if (!record.archived && record.workPath != null && known != null && !isClosed(known)) {
        asks.add(new Ask(record, known, List.of(record.workPath, this.service.folderOf(record.projectPath))));
      }
    }
    if (asks.isEmpty() || !this.refreshing.compareAndSet(false, true)) {
      return;
    }
    Async.inBackground(() -> {
      try {
        Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
        Map<String, GitInfo.PullRequest> found = new HashMap<>();
        for (Ask ask : asks) {
          String url = ask.known().url();
          if (!found.containsKey(url)) {
            found.put(url, lookUp(ask, environment));
          }
        }
        Async.onEdt(() -> this.store(asks, found));
      } finally {
        this.refreshing.set(false);
      }
    });
  }

  boolean isRefreshing() {
    return this.refreshing.get();
  }

  /** Keeps each answer for a thread that still has the pull request it was asked for. Must run on the EDT. */
  void store(List<Ask> asks, Map<String, GitInfo.PullRequest> found) {
    List<ThreadStore.Record> changed = new ArrayList<>();
    for (Ask ask : asks) {
      ThreadStore.Record record = ask.record();
      GitInfo.PullRequest pullRequest = found.get(ask.known().url());
      boolean isSame = this.store.find(record.id) == record && ask.known().equals(record.pullRequest);
      if (isSame && pullRequest != null && pullRequest.url().equals(ask.known().url())
        && !pullRequest.equals(ask.known())) {
        record.pullRequest = pullRequest;
        changed.add(record);
      }
    }
    this.service.postUpdates(changed);
  }

  /**
   * The pull request as it is now, asked in each folder that still exists until one answers, as a removed worktree can
   * leave a folder that is no checkout anymore. Null when none answers.
   */
  private static @Nullable GitInfo.PullRequest lookUp(Ask ask, Map<String, String> environment) {
    for (String folder : ask.folders()) {
      if (folder.isBlank()) {
        continue;
      }
      Path path;
      try {
        path = Path.of(folder);
      } catch (InvalidPathException e) {
        continue;
      }
      GitInfo.PullRequest found = Files.isDirectory(path) ? GitInfo.pullRequest(path, environment, ask.known()) : null;
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** A thread's pull request to ask for, and the folders of its repository to ask in, its own first. */
  record Ask(
    ThreadStore.Record record,
    GitInfo.PullRequest known,
    List<String> folders
  ) {
  }

  enum Reason {
    PULL_REQUEST,
    IDLE
  }

  /** What the settings let the upkeep archive. */
  public record Rules(
    boolean pullRequest,
    boolean idle,
    int idleDays
  ) {
    public static Rules of(ChatSettings.Data settings) {
      return new Rules(settings.settleOnPullRequest, settings.settleIdle, settings.idleDays());
    }
  }
}
