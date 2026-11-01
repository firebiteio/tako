package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.settings.ChatSettings;
import org.junit.Test;

public class ThreadUpkeepTest {
  private static final long DAY = 24L * 60 * 60 * 1000;
  private static final long NOW = 1_800_000_000_000L;
  private static final String URL = "https://github.com/o/r/pull/7";
  private static final ThreadUpkeep.Rules BOTH = new ThreadUpkeep.Rules(true, true, 7);

  private static ThreadStore.Record record(long updatedAt) {
    ThreadStore.Record record = new ThreadStore.Record();
    record.id = "t";
    record.updatedAt = updatedAt;
    return record;
  }

  private static ThreadStore.Record inWorktree(String state) {
    ThreadStore.Record record = record(NOW - ThreadUpkeep.PULL_REQUEST_GRACE_MILLIS);
    record.workPath = "D:/code/app-tako/fix";
    record.pullRequest = new GitInfo.PullRequest("#7", "Fix", URL, state);
    return record;
  }

  @Test
  public void snoozeIsDueOnceItsTimeCame() {
    ThreadStore.Record record = record(NOW);
    assertFalse(ThreadUpkeep.isDue(record, NOW));
    record.snoozedUntil = NOW + 1;
    assertFalse(ThreadUpkeep.isDue(record, NOW));
    assertTrue(ThreadUpkeep.isDue(record, NOW + 1));
    assertTrue(ThreadUpkeep.isDue(record, NOW + 365 * DAY));
  }

  @Test
  public void mergedOrClosedPullRequestSettlesAThreadInItsOwnWorktree() {
    assertEquals(ThreadUpkeep.Reason.PULL_REQUEST, ThreadUpkeep.settleReason(inWorktree("merged"), BOTH, NOW));
    assertEquals(ThreadUpkeep.Reason.PULL_REQUEST, ThreadUpkeep.settleReason(inWorktree("closed"), BOTH, NOW));
    assertNull(ThreadUpkeep.settleReason(inWorktree("open"), BOTH, NOW));
    assertNull(ThreadUpkeep.settleReason(inWorktree("draft"), BOTH, NOW));
  }

  @Test
  public void workGoingOnAfterTheMergeKeepsTheThreadForAnHour() {
    ThreadStore.Record prompted = inWorktree("merged");
    prompted.updatedAt = NOW - ThreadUpkeep.PULL_REQUEST_GRACE_MILLIS + 1;
    assertNull(ThreadUpkeep.settleReason(prompted, BOTH, NOW));

    ThreadStore.Record opened = inWorktree("merged");
    opened.seenAt = NOW - 1;
    assertNull(ThreadUpkeep.settleReason(opened, BOTH, NOW));
    assertEquals(ThreadUpkeep.Reason.PULL_REQUEST,
      ThreadUpkeep.settleReason(opened, BOTH, NOW + ThreadUpkeep.PULL_REQUEST_GRACE_MILLIS));
  }

  @Test
  public void pullRequestOfTheSharedCheckoutSettlesNothing() {
    ThreadStore.Record record = inWorktree("merged");
    record.workPath = null;
    record.worktree = "thread-list-management";

    assertNull(ThreadUpkeep.settleReason(record, BOTH, NOW));
  }

  @Test
  public void pullRequestSettlesOnlyOnce() {
    ThreadStore.Record record = inWorktree("merged");
    record.settledPullRequest = URL;
    assertNull(ThreadUpkeep.settleReason(record, BOTH, NOW));

    record.pullRequest = new GitInfo.PullRequest("#8", "Follow up", "https://github.com/o/r/pull/8", "closed");
    assertEquals(ThreadUpkeep.Reason.PULL_REQUEST, ThreadUpkeep.settleReason(record, BOTH, NOW));
  }

  @Test
  public void pinnedThreadSettlesForItsPullRequestButNotForIdling() {
    ThreadStore.Record merged = inWorktree("merged");
    merged.pinned = true;
    assertEquals(ThreadUpkeep.Reason.PULL_REQUEST, ThreadUpkeep.settleReason(merged, BOTH, NOW));

    ThreadStore.Record idle = record(NOW - 30 * DAY);
    idle.pinned = true;
    assertNull(ThreadUpkeep.settleReason(idle, BOTH, NOW));
  }

  @Test
  public void idleTimeCountsFromTheLaterOfActivityAndOpening() {
    assertEquals(ThreadUpkeep.Reason.IDLE, ThreadUpkeep.settleReason(record(NOW - 7 * DAY), BOTH, NOW));
    assertNull(ThreadUpkeep.settleReason(record(NOW - 7 * DAY + 1), BOTH, NOW));

    ThreadStore.Record opened = record(NOW - 30 * DAY);
    opened.seenAt = NOW - DAY;
    assertNull(ThreadUpkeep.settleReason(opened, BOTH, NOW));
  }

  @Test
  public void clockSetBackSettlesNothing() {
    assertNull(ThreadUpkeep.settleReason(record(NOW + 30 * DAY), BOTH, NOW));
  }

  @Test
  public void unreadSnoozedOrArchivedThreadsAreNotSettled() {
    ThreadStore.Record unread = inWorktree("merged");
    unread.updatedAt = 0;
    unread.unread = true;
    assertNull(ThreadUpkeep.settleReason(unread, BOTH, NOW));

    ThreadStore.Record snoozed = record(0);
    snoozed.snoozedUntil = NOW - 1;
    assertNull(ThreadUpkeep.settleReason(snoozed, BOTH, NOW));

    ThreadStore.Record archived = record(0);
    archived.archived = true;
    assertNull(ThreadUpkeep.settleReason(archived, BOTH, NOW));
  }

  @Test
  public void rulesOffSettleNothing() {
    ThreadUpkeep.Rules off = new ThreadUpkeep.Rules(false, false, 7);
    ThreadStore.Record record = inWorktree("merged");
    record.updatedAt = 0;

    assertNull(ThreadUpkeep.settleReason(record, off, NOW));
    assertEquals(ThreadUpkeep.Reason.IDLE,
      ThreadUpkeep.settleReason(record, new ThreadUpkeep.Rules(false, true, 7), NOW));
  }

  @Test
  public void idleDaysOutOfRangeAreClamped() {
    ChatSettings.Data settings = new ChatSettings.Data();
    assertFalse(ThreadUpkeep.Rules.of(settings).idle());
    assertEquals(7, ThreadUpkeep.Rules.of(settings).idleDays());
    settings.settleIdleDays = 0;
    assertEquals(1, ThreadUpkeep.Rules.of(settings).idleDays());
    settings.settleIdleDays = -5;
    assertEquals(1, ThreadUpkeep.Rules.of(settings).idleDays());
    settings.settleIdleDays = Integer.MAX_VALUE;
    assertEquals(365, ThreadUpkeep.Rules.of(settings).idleDays());
  }
}
