package io.firebite.tako.chat.watch;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.project.GitInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Polls the pull requests threads watch and wakes a thread's agent with a prompt when something on its pull request
 * needs it. A thread that is busy is not polled; its watch stays due, so the next tick after the thread settles polls
 * it and tells everything that changed meanwhile in one prompt. What a watch has seen moves on only once the prompt
 * was delivered.
 */
public final class PullRequestWatcher {
  public static final long POLL_MILLIS = 120_000;
  public static final long RATE_LIMIT_MILLIS = 900_000;
  public static final int MAX_FAILURES = 8;
  private static final Logger LOG = Logger.getInstance(PullRequestWatcher.class);

  private final ThreadStore store;
  private final GhCli.Runner gh;
  private final Host host;
  private final LongSupplier clock;
  /** Guards swapping a record's watch. gh, {@link Host#lookup} and {@link Host#deliver} never run under it. */
  private final Object lock = new Object();
  private final Set<String> starting = ConcurrentHashMap.newKeySet();

  public PullRequestWatcher(ThreadStore store, GhCli.Runner gh, Host host, LongSupplier clock) {
    this.store = store;
    this.gh = gh;
    this.host = host;
    this.clock = clock;
  }

  /**
   * Watches a pull request for the thread, replacing a watch on another one. Watching the one already watched keeps
   * what that watch has seen. Runs gh, so call off the EDT.
   *
   * @param url         the pull request's address, or null for the one of the thread's branch
   * @param freshLookup whether to ask gh for the branch's pull request instead of trusting the thread's record, which
   *                    may be from before the agent switched branches or opened one
   */
  public Started start(String threadId, @Nullable String url, boolean freshLookup) throws WatchException {
    ThreadStore.Record record = this.store.find(threadId);
    if (record == null) {
      throw new WatchException(WatchException.Reason.NO_THREAD, "", "The thread no longer exists in Tako.");
    }
    if (record.archived) {
      throw new WatchException(WatchException.Reason.ARCHIVED, "", "The thread is archived.");
    }
    if (!this.starting.add(threadId)) {
      throw new WatchException(WatchException.Reason.STARTING, "", "Tako is already starting to watch a pull "
        + "request for this thread.");
    }
    try {
      PullRequestUrl target = this.target(record, url, freshLookup);
      Path folder = this.host.folder(record);
      String view = this.view(folder, target);
      GhCli.Result comments = this.gh.run(folder, commentsArgs(target));
      boolean commentsRead = GhCli.classify(comments) == null;
      if (!commentsRead) {
        LOG.debug("Could not read the review comments of " + target.repository() + ": " + comments.err());
      }
      PullRequestStatus status = Objects.requireNonNull(PullRequestStatus.parse(view,
        commentsRead ? comments.out() : null));
      String self = this.output(this.gh.run(folder, List.of("api", "--hostname", target.host(), "user", "--jq",
        ".login"))).trim();
      if (self.isEmpty()) {
        throw new WatchException(WatchException.Reason.FAILED, "gh did not tell your login", "gh did not tell your "
          + "GitHub login.");
      }
      return this.begin(record, status, self, commentsRead);
    } finally {
      this.starting.remove(threadId);
    }
  }

  /**
   * Whether a thread in this state takes a wake prompt now: idle, stopped (the prompt starts its agent) or not open
   * (null; the prompt opens it). Not while its agent works or waits for the person.
   */
  public static boolean canWake(@Nullable ChatController.State state) {
    return state == null || !(state.isBusy() || state == ChatController.State.WAITING);
  }

  /** Ends the thread's watch. @return the number of the pull request it watched, or null when it watched none */
  public @Nullable String stop(String threadId) {
    synchronized (this.lock) {
      ThreadStore.Record record = this.store.find(threadId);
      PullRequestWatch watch = record == null ? null : record.watch;
      if (watch == null) {
        return null;
      }
      this.swap(record, null);
      this.host.changed(record);
      return watch.number;
    }
  }

  /** Polls each watch that is due and whose thread can take a prompt now. */
  public void tick() {
    for (ThreadStore.Record record : this.store.all()) {
      PullRequestWatch watch = record.watch;
      if (watch == null) {
        continue;
      }
      if (record.archived) {
        this.endSilently(record, watch);
        continue;
      }
      if (this.clock.getAsLong() < watch.nextPollAt || !canWake(this.host.state(record.id))) {
        continue;
      }
      try {
        this.poll(record);
      } catch (ProcessCanceledException e) {
        throw e;
      } catch (RuntimeException e) {
        LOG.warn("Could not poll the pull request of thread " + record.id, e);
      }
    }
  }

  /** Reads the watched pull request and tells the thread what changed. */
  void poll(ThreadStore.Record record) {
    PullRequestWatch watch = record.watch;
    PullRequestUrl url = watch == null ? null : PullRequestUrl.parse(watch.url);
    if (url == null) {
      if (watch != null) {
        this.endSilently(record, watch);
      }
      return;
    }
    Path folder = this.host.folder(record);
    GhCli.Result view = this.gh.run(folder, viewArgs(url));
    GhCli.Failure failure = GhCli.classify(view);
    PullRequestStatus status = null;
    boolean commentsRead = false;
    if (failure == null) {
      GhCli.Result comments = this.gh.run(folder, commentsArgs(url));
      commentsRead = GhCli.classify(comments) == null;
      if (!commentsRead) {
        LOG.debug("Could not read the review comments of " + watch.url + ": " + comments.err());
      }
      status = PullRequestStatus.parse(view.out(), commentsRead ? comments.out() : null);
      if (status == null) {
        failure = new GhCli.Failure(GhCli.Kind.OTHER, "gh gave no pull request for " + watch.url);
      }
    }
    PullRequestWatch.Update update;
    synchronized (this.lock) {
      if (!this.isCurrent(record, watch)) {
        return;
      }
      if (failure != null) {
        this.failed(record, watch, failure);
        return;
      }
      update = watch.advance(status, commentsRead);
      if (update.events().isEmpty()) {
        update.next().nextPollAt = this.clock.getAsLong() + POLL_MILLIS;
        this.swap(record, update.next());
        return;
      }
    }
    boolean isDelivered = this.host.deliver(record, watch, WakePrompt.compose(watch, update));
    synchronized (this.lock) {
      if (!isDelivered || !this.isCurrent(record, watch)) {
        return;
      }
      if (update.end() != null) {
        this.swap(record, null);
        this.host.changed(record);
        return;
      }
      update.next().nextPollAt = this.clock.getAsLong() + POLL_MILLIS;
      this.swap(record, update.next());
    }
  }

  private PullRequestUrl target(ThreadStore.Record record, @Nullable String url, boolean freshLookup)
    throws WatchException {
    if (url != null && !url.isBlank()) {
      PullRequestUrl parsed = PullRequestUrl.parse(url);
      if (parsed == null) {
        throw new WatchException(WatchException.Reason.BAD_URL, url, "Not a GitHub pull request URL: " + url);
      }
      return parsed;
    }
    GitInfo.PullRequest pullRequest = freshLookup ? this.host.lookup(record) : null;
    if (pullRequest == null) {
      pullRequest = record.pullRequest;
    }
    if (pullRequest == null || !pullRequest.isGitHub() || !"open".equals(pullRequest.state())) {
      throw new WatchException(WatchException.Reason.NO_PULL_REQUEST, "", "This thread's branch has no open GitHub "
        + "pull request. Pass url.");
    }
    PullRequestUrl parsed = PullRequestUrl.parse(pullRequest.url());
    if (parsed == null) {
      throw new WatchException(WatchException.Reason.BAD_URL, pullRequest.url(), "Not a GitHub pull request URL: "
        + pullRequest.url());
    }
    return parsed;
  }

  /** gh's view of the open pull request. */
  private String view(Path folder, PullRequestUrl target) throws WatchException {
    String view = this.output(this.gh.run(folder, viewArgs(target)));
    PullRequestStatus status = PullRequestStatus.parse(view, null);
    if (status == null) {
      throw new WatchException(WatchException.Reason.FAILED, "gh gave no pull request", "gh gave no pull request for "
        + target.repository() + "#" + target.number() + ".");
    }
    if (!status.isOpen()) {
      String state = status.state().toLowerCase(Locale.ROOT);
      throw new WatchException(WatchException.Reason.NOT_OPEN, status.label(), status.label() + " is " + state
        + ". Only open pull requests can be watched.");
    }
    return view;
  }

  /** The command's output, or the reason it failed. */
  private String output(GhCli.Result result) throws WatchException {
    GhCli.Failure failure = GhCli.classify(result);
    if (failure == null) {
      return result.out();
    }
    throw switch (failure.kind()) {
      case MISSING -> new WatchException(WatchException.Reason.MISSING, "", "gh, the GitHub CLI, is not installed "
        + "or not on the PATH.");
      case LOGGED_OUT -> new WatchException(WatchException.Reason.LOGGED_OUT, "", "gh is not logged in. The person "
        + "has to run gh auth login.");
      case RATE_LIMITED -> new WatchException(WatchException.Reason.RATE_LIMITED, "", "GitHub's rate limit is "
        + "reached. Try again later.");
      case OTHER -> new WatchException(WatchException.Reason.FAILED, failure.message(), "gh failed: "
        + failure.message());
    };
  }

  private Started begin(ThreadStore.Record record, PullRequestStatus status, String self, boolean commentsRead)
    throws WatchException {
    synchronized (this.lock) {
      if (this.store.find(record.id) != record) {
        throw new WatchException(WatchException.Reason.NO_THREAD, "", "The thread no longer exists in Tako.");
      }
      if (record.archived) {
        throw new WatchException(WatchException.Reason.ARCHIVED, "", "The thread is archived.");
      }
      PullRequestWatch existing = record.watch;
      PullRequestUrl url = PullRequestUrl.parse(status.url());
      if (existing != null && url != null && url.equals(PullRequestUrl.parse(existing.url))) {
        return new Started(status, true);
      }
      PullRequestWatch watch = PullRequestWatch.baseline(status, self, commentsRead);
      watch.nextPollAt = this.clock.getAsLong() + POLL_MILLIS;
      this.swap(record, watch);
      this.host.changed(record);
      return new Started(status, false);
    }
  }

  private void failed(ThreadStore.Record record, PullRequestWatch watch, GhCli.Failure failure) {
    switch (failure.kind()) {
      case RATE_LIMITED -> watch.nextPollAt = this.clock.getAsLong() + RATE_LIMIT_MILLIS;
      case MISSING -> this.end(record, watch, Stop.MISSING, failure.message());
      case LOGGED_OUT -> this.end(record, watch, Stop.LOGGED_OUT, failure.message());
      case OTHER -> {
        PullRequestWatch next = watch.failed();
        if (next.failures >= MAX_FAILURES) {
          this.end(record, watch, Stop.FAILURES, failure.message());
        } else {
          next.nextPollAt = this.clock.getAsLong() + POLL_MILLIS;
          this.swap(record, next);
        }
      }
    }
  }

  private void end(ThreadStore.Record record, PullRequestWatch watch, Stop reason, String message) {
    this.swap(record, null);
    this.host.changed(record);
    this.host.stopped(record, watch.number, reason, message);
  }

  private void endSilently(ThreadStore.Record record, PullRequestWatch watch) {
    synchronized (this.lock) {
      if (this.isCurrent(record, watch)) {
        this.swap(record, null);
        this.host.changed(record);
      }
    }
  }

  private boolean isCurrent(ThreadStore.Record record, PullRequestWatch watch) {
    return record.watch == watch && this.store.find(record.id) == record;
  }

  private void swap(ThreadStore.Record record, @Nullable PullRequestWatch watch) {
    record.watch = watch;
    this.store.save();
  }

  private static List<String> viewArgs(PullRequestUrl url) {
    return List.of("pr", "view", "https://" + url.host() + "/" + url.owner() + "/" + url.repo() + "/pull/"
      + url.number(), "--json", PullRequestStatus.VIEW_FIELDS);
  }

  private static List<String> commentsArgs(PullRequestUrl url) {
    return List.of("api", "--hostname", url.host(), url.commentsPath());
  }

  /** Why a watch ended on its own, other than by its pull request merging or closing. */
  public enum Stop {
    MISSING,
    LOGGED_OUT,
    /** {@link #MAX_FAILURES} failed reads in a row. */
    FAILURES
  }

  /** What the watcher needs of the IDE. */
  public interface Host {
    /** What the thread's agent does, or null when the thread is not open. */
    @Nullable
    ChatController.State state(String threadId);

    /** Where gh runs: a folder that exists. */
    Path folder(ThreadStore.Record record);

    /** The open pull request of the thread's branch, asked of gh now; null without one or a work folder. */
    @Nullable
    GitInfo.PullRequest lookup(ThreadStore.Record record);

    /**
     * Sends the prompt as if typed, unless the thread can't take one now or {@code watch} is no longer its watch.
     * Blocks until it decided.
     */
    boolean deliver(ThreadStore.Record record, PullRequestWatch watch, String prompt);

    /** The watch ended on its own; tell the person. */
    void stopped(ThreadStore.Record record, String number, Stop reason, String message);

    /** The thread's watch started or ended; the chat should show it. */
    void changed(ThreadStore.Record record);
  }

  /** @param kept whether the pull request was watched already, so the watch kept what it has seen */
  public record Started(
    PullRequestStatus status,
    boolean kept
  ) {
  }
}
