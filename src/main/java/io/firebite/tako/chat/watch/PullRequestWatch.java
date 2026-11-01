package io.firebite.tako.chat.watch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A thread's watch on a pull request and what it has seen of it, saved with the thread. Each poll compares the pull
 * request with this state, so an event is told once, across IDE restarts too. {@link #advance} returns a new state and
 * never changes this one, so saving it while a poll runs is safe.
 */
public final class PullRequestWatch {
  /** Wakes in a row that bring only reviews and comments, after which the watch ends: two bots could talk forever. */
  public static final int COMMENT_WAKE_LIMIT = 10;
  /** How many review comments one poll reads, newest first. */
  public static final int COMMENT_WINDOW = 100;

  public String url = "";
  /** {@code #12}. */
  public String number = "";
  /** The gh account's login. Its reviews and comments wake nobody, so the agent's replies do not wake it. */
  public String self = "";
  public String headSha = "";
  /** The commit {@link #failedChecks} and {@link #passed} belong to. */
  public String checksSha = "";
  public @Nullable List<String> failedChecks = new ArrayList<>();
  public boolean passed;
  public @Nullable List<String> seenReviews = new ArrayList<>();
  /** The review comments of the last read. */
  public @Nullable List<Long> seenComments = new ArrayList<>();
  public boolean conflicting;
  public int commentWakes;
  /** Failed reads in a row. */
  public int failures;
  /** When to poll next; 0 polls at the next tick. Not saved, so a restarted IDE polls right away. */
  public transient long nextPollAt;

  /** A watch that has seen everything the pull request holds now. */
  public static PullRequestWatch baseline(PullRequestStatus status, String self) {
    PullRequestWatch watch = new PullRequestWatch();
    watch.url = status.url();
    watch.number = status.label();
    watch.self = self;
    watch.headSha = status.headSha();
    watch.checksSha = status.headSha();
    watch.failedChecks = failedNames(status);
    watch.passed = isPassed(status);
    for (PullRequestStatus.Review review : status.reviews()) {
      watch.seenReviews.add(review.id());
    }
    for (PullRequestStatus.Comment comment : status.comments()) {
      watch.seenComments.add(comment.id());
    }
    watch.conflicting = "CONFLICTING".equals(status.mergeable());
    return watch;
  }

  /**
   * What changed since this state, and the state after telling it.
   *
   * @param commentsRead whether {@code status} holds the review comments; when their read failed, they count as
   *                     unchanged
   */
  public Update advance(PullRequestStatus status, boolean commentsRead) {
    PullRequestWatch next = this.copy();
    next.failures = 0;
    List<WatchEvent> events = new ArrayList<>();
    if ("MERGED".equals(status.state()) || "CLOSED".equals(status.state())) {
      events.add("MERGED".equals(status.state()) ? new WatchEvent.Merged() : new WatchEvent.Closed());
      return new Update(next, events, End.MERGED_OR_CLOSED);
    }
    boolean isNewHead = !status.headSha().isEmpty() && !status.headSha().equals(this.headSha);
    if (!status.headSha().isEmpty()) {
      next.headSha = status.headSha();
    }
    this.checkEvents(status, next, events);
    boolean hasProgress = !events.isEmpty();
    this.reviewEvents(status, next, events);
    if (commentsRead) {
      this.commentEvents(status, next, events);
    }
    if ("CONFLICTING".equals(status.mergeable()) && !this.conflicting) {
      events.add(new WatchEvent.Conflicting(status.baseBranch()));
      hasProgress = true;
    }
    if (!"UNKNOWN".equals(status.mergeable()) && !status.mergeable().isEmpty()) {
      next.conflicting = "CONFLICTING".equals(status.mergeable());
    }
    boolean isCommentsOnly = !events.isEmpty() && !hasProgress;
    next.commentWakes = (isNewHead || hasProgress ? 0 : this.commentWakes) + (isCommentsOnly ? 1 : 0);
    End end = isCommentsOnly && next.commentWakes >= COMMENT_WAKE_LIMIT ? End.COMMENT_LOOP : null;
    return new Update(next, events, end);
  }

  /** This state after one more failed read. */
  public PullRequestWatch failed() {
    PullRequestWatch next = this.copy();
    next.failures = this.failures + 1;
    return next;
  }

  private void checkEvents(PullRequestStatus status, PullRequestWatch next, List<WatchEvent> events) {
    if (status.checks().isEmpty()) {
      return;
    }
    boolean isSameCommit = status.headSha().equals(this.checksSha);
    List<String> before = isSameCommit ? list(this.failedChecks) : List.of();
    List<PullRequestStatus.Check> failed = new ArrayList<>();
    for (PullRequestStatus.Check check : status.checks()) {
      if (check.outcome() == PullRequestStatus.Outcome.FAILED && !before.contains(check.name())) {
        failed.add(check);
      }
    }
    if (!failed.isEmpty()) {
      events.add(new WatchEvent.ChecksFailed(status.headSha(), failed));
    }
    boolean isPassed = isPassed(status);
    if (isPassed && !(isSameCommit && this.passed)) {
      events.add(new WatchEvent.ChecksPassed(status.headSha(), status.checks().size()));
    }
    next.checksSha = status.headSha();
    next.failedChecks = failedNames(status);
    next.passed = isPassed;
  }

  private void reviewEvents(PullRequestStatus status, PullRequestWatch next, List<WatchEvent> events) {
    List<String> seen = list(this.seenReviews);
    List<String> ids = new ArrayList<>();
    for (PullRequestStatus.Review review : status.reviews()) {
      ids.add(review.id());
      if (!seen.contains(review.id()) && !this.isSelf(review.author()) && isWorthTelling(review)) {
        events.add(new WatchEvent.Reviewed(review));
      }
    }
    for (String id : seen) {
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    next.seenReviews = ids;
  }

  /**
   * A comment is new when the last read did not hold it. Ids are given when a reviewer drafts a comment, so a review
   * submitted later brings comments older than ones already seen. Once the window is full, a comment older than all it
   * holds only moved into it because a newer one was deleted.
   */
  private void commentEvents(PullRequestStatus status, PullRequestWatch next, List<WatchEvent> events) {
    Set<Long> seen = new HashSet<>(list(this.seenComments));
    long oldestSeen = Long.MAX_VALUE;
    for (long id : seen) {
      oldestSeen = Math.min(oldestSeen, id);
    }
    boolean isWindowFull = seen.size() >= COMMENT_WINDOW;
    List<PullRequestStatus.Comment> fresh = new ArrayList<>();
    List<Long> ids = new ArrayList<>();
    for (PullRequestStatus.Comment comment : status.comments()) {
      ids.add(comment.id());
      boolean isNew = !seen.contains(comment.id()) && (!isWindowFull || comment.id() > oldestSeen);
      if (isNew && !this.isSelf(comment.author())) {
        fresh.add(comment);
      }
    }
    fresh.sort((first, second) -> Long.compare(first.id(), second.id()));
    for (PullRequestStatus.Comment comment : fresh) {
      events.add(new WatchEvent.Commented(comment));
    }
    next.seenComments = ids;
  }

  private boolean isSelf(String login) {
    return !this.self.isEmpty() && this.self.equalsIgnoreCase(login);
  }

  /** An empty plain review only bundles inline comments, which come on their own. */
  private static boolean isWorthTelling(PullRequestStatus.Review review) {
    return "APPROVED".equals(review.state()) || "CHANGES_REQUESTED".equals(review.state()) || !review.body().isBlank();
  }

  private static boolean isPassed(PullRequestStatus status) {
    return !status.checks().isEmpty() && status.count(PullRequestStatus.Outcome.PENDING) == 0
      && status.count(PullRequestStatus.Outcome.FAILED) == 0;
  }

  private static List<String> failedNames(PullRequestStatus status) {
    List<String> names = new ArrayList<>();
    for (PullRequestStatus.Check check : status.checks()) {
      if (check.outcome() == PullRequestStatus.Outcome.FAILED) {
        names.add(check.name());
      }
    }
    return names;
  }

  private static <T> List<T> list(@Nullable List<T> list) {
    return list != null ? list : List.of();
  }

  private PullRequestWatch copy() {
    PullRequestWatch copy = new PullRequestWatch();
    copy.url = this.url;
    copy.number = this.number;
    copy.self = this.self;
    copy.headSha = this.headSha;
    copy.checksSha = this.checksSha;
    copy.failedChecks = new ArrayList<>(list(this.failedChecks));
    copy.passed = this.passed;
    copy.seenReviews = new ArrayList<>(list(this.seenReviews));
    copy.seenComments = new ArrayList<>(list(this.seenComments));
    copy.conflicting = this.conflicting;
    copy.commentWakes = this.commentWakes;
    copy.failures = this.failures;
    copy.nextPollAt = this.nextPollAt;
    return copy;
  }

  public enum End {
    MERGED_OR_CLOSED,
    /** {@link #COMMENT_WAKE_LIMIT} comment-only wakes in a row. */
    COMMENT_LOOP
  }

  /**
   * @param next   the state once the events are told
   * @param end    why the watch ends once they are told, or null when it goes on
   */
  public record Update(
    PullRequestWatch next,
    List<WatchEvent> events,
    @Nullable End end
  ) {
  }
}
