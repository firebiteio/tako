package io.firebite.tako.chat.watch;

import java.util.List;

/** Something on a watched pull request the agent should act on. */
public sealed interface WatchEvent {

  /** Checks that started failing since the last poll. */
  record ChecksFailed(
    String headSha,
    List<PullRequestStatus.Check> failed
  ) implements WatchEvent {
  }

  record ChecksPassed(
    String headSha,
    int count
  ) implements WatchEvent {
  }

  record Reviewed(
    PullRequestStatus.Review review
  ) implements WatchEvent {
  }

  record Commented(
    PullRequestStatus.Comment comment
  ) implements WatchEvent {
  }

  record Conflicting(
    String baseBranch
  ) implements WatchEvent {
  }

  record Merged() implements WatchEvent {
  }

  record Closed() implements WatchEvent {
  }
}
