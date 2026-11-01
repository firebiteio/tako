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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Wakes snoozed threads whose time came and archives threads the settings say are done: their pull request was merged
 * or closed, or nobody touched them for some days.
 */
public final class ThreadUpkeep {
  private static final Logger LOG = Logger.getInstance(ThreadUpkeep.class);
  private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  private final ChatService service;
  private final ThreadStore store;

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
    if (rules.pullRequest() && record.workPath != null && pullRequest != null && isClosed(pullRequest)
      && !pullRequest.url().equals(record.settledPullRequest)) {
      return Reason.PULL_REQUEST;
    }
    long idleSince = Math.max(record.updatedAt, record.seenAt);
    if (rules.idle() && !record.pinned && now - idleSince >= rules.idleDays() * DAY_MILLIS) {
      return Reason.IDLE;
    }
    return null;
  }

  private static boolean isClosed(GitInfo.PullRequest pullRequest) {
    return "merged".equals(pullRequest.state()) || "closed".equals(pullRequest.state());
  }

  /** Wakes the threads whose snooze ended as unread, then archives the done ones. Must run on the EDT. */
  void tick(long now) {
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && isDue(record, now)) {
        record.snoozedUntil = 0;
        record.unread = true;
        record.seenAt = now;
        this.service.postUpdate(record);
      }
    }
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
    for (ThreadStore.Record record : records) {
      Reason reason = settleReason(record, rules, now);
      if (reason == null || parents.contains(record.id) || this.isKept(record)) {
        continue;
      }
      if (reason == Reason.PULL_REQUEST && record.pullRequest != null) {
        record.settledPullRequest = record.pullRequest.url();
      }
      LOG.info("Archiving thread " + record.id + " automatically: " + reason);
      this.service.archive(record.id);
    }
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
   * Keeps an answer only for the branch and pull request the thread already has, so a branch switched outside the IDE
   * does not attach another one. Runs git and gh or glab, so call off the EDT.
   */
  void refreshPullRequests() {
    if (!Rules.of(ChatSettings.state()).pullRequest()) {
      return;
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    Map<String, GitInfo> folders = new HashMap<>();
    for (ThreadStore.Record record : this.store.all()) {
      String folder = record.workPath;
      String branch = record.branch;
      GitInfo.PullRequest stored = record.pullRequest;
      if (record.archived || folder == null || branch == null || stored == null || isClosed(stored)) {
        continue;
      }
      GitInfo found = folders.computeIfAbsent(folder, key -> lookUp(key, environment));
      GitInfo.PullRequest pullRequest = found.pullRequest();
      if (!branch.equals(found.branch()) || pullRequest == null || !pullRequest.url().equals(stored.url())
        || pullRequest.equals(stored)) {
        continue;
      }
      Async.onEdt(() -> {
        GitInfo.PullRequest current = record.pullRequest;
        if (this.store.find(record.id) == record && current != null && current.url().equals(pullRequest.url())) {
          record.pullRequest = pullRequest;
          this.service.postUpdate(record);
        }
      });
    }
  }

  /** The folder's branch and its pull request, or nothing when the folder is gone. */
  private static GitInfo lookUp(String folder, Map<String, String> environment) {
    Path path;
    try {
      path = Path.of(folder);
    } catch (InvalidPathException e) {
      return new GitInfo(null, null, null);
    }
    if (!Files.isDirectory(path)) {
      return new GitInfo(null, null, null);
    }
    GitInfo local = GitInfo.local(path, environment);
    if (local.branch() == null) {
      return local;
    }
    return new GitInfo(local.branch(), local.worktree(), GitInfo.pullRequest(path, environment));
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
    public static final int DEFAULT_IDLE_DAYS = 7;
    public static final int MIN_IDLE_DAYS = 1;
    public static final int MAX_IDLE_DAYS = 365;

    public static Rules of(ChatSettings.Data settings) {
      return new Rules(settings.settleOnPullRequest, settings.settleIdle,
        Math.clamp(settings.settleIdleDays, MIN_IDLE_DAYS, MAX_IDLE_DAYS));
    }
  }
}
