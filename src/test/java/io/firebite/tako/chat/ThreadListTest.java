package io.firebite.tako.chat;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatHost;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.settings.ChatSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Pin, rename, unread, snooze and auto archive as the thread list asks the service for them. */
public class ThreadListTest extends BasePlatformTestCase {
  private static final long DAY = 24L * 60 * 60 * 1000;
  private static final String MERGED_URL = "https://github.com/o/r/pull/7";

  private ThreadStore store;
  private ChatService service;
  private String savedClaudePath;
  private boolean savedSettlePullRequest;
  private boolean savedSettleIdle;
  private int savedSettleDays;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    ChatSettings.Data settings = ChatSettings.state();
    this.savedClaudePath = settings.claudePath;
    this.savedSettlePullRequest = settings.settleOnPullRequest;
    this.savedSettleIdle = settings.settleIdle;
    this.savedSettleDays = settings.settleIdleDays;
    settings.claudePath = Path.of(System.getProperty("java.io.tmpdir"), "tako-no-claude", "claude.exe").toString();
    this.store = ThreadStore.getInstance(this.getProject());
    this.service = ChatService.getInstance(this.getProject());
    this.clear();
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      this.clear();
      ChatSettings.Data settings = ChatSettings.state();
      settings.claudePath = this.savedClaudePath;
      settings.settleOnPullRequest = this.savedSettlePullRequest;
      settings.settleIdle = this.savedSettleIdle;
      settings.settleIdleDays = this.savedSettleDays;
    } finally {
      super.tearDown();
    }
  }

  private void clear() {
    for (ThreadStore.Record record : this.store.all()) {
      this.service.close(record.id);
    }
    for (ThreadStore.Record record : this.store.all()) {
      this.store.remove(record.id);
    }
    for (ThreadStore.Bench bench : this.store.benches()) {
      this.store.removeBench(bench.id);
    }
  }

  private ThreadStore.Record thread() {
    return this.store.create(null, null, null, "claude");
  }

  private ThreadStore.Record child(ThreadStore.Record parent) {
    ThreadStore.Record record = this.thread();
    record.parentId = parent.id;
    return record;
  }

  private ThreadStore.Record find(ThreadStore.Record record) {
    return this.store.find(record.id);
  }

  private long later() {
    return System.currentTimeMillis() + DAY;
  }

  private void makeRun(ThreadStore.Record record) {
    ThreadStore.Bench bench = new ThreadStore.Bench();
    bench.id = "bench-" + record.id;
    ThreadStore.Run run = new ThreadStore.Run();
    run.thread = record.id;
    bench.runs.add(run);
    this.store.addBench(bench);
  }

  private @Nullable ThreadStore.Record active() {
    return this.store.find(this.store.activeId());
  }

  public void testPinningTwiceKeepsItPinnedAndUnpinningUndoesIt() {
    ThreadStore.Record record = this.thread();

    this.service.setPinned(record.id, true);
    this.service.setPinned(record.id, true);
    assertTrue(record.pinned);
    this.service.setPinned(record.id, false);
    assertFalse(record.pinned);
    this.service.setPinned("gone", true);
  }

  public void testRunsCannotBePinnedOrSnoozed() {
    ThreadStore.Record run = this.thread();
    this.makeRun(run);

    this.service.setPinned(run.id, true);
    this.service.snooze(run.id, this.later());

    assertFalse(run.pinned);
    assertEquals(0, run.snoozedUntil);
  }

  public void testRenameTakesOneTrimmedLineAndIgnoresBlank() {
    ThreadStore.Record record = this.thread();

    this.service.rename(record.id, "  Fix\nthe   parser  ");
    assertEquals("Fix the parser", record.title);
    this.service.rename(record.id, " \n ");
    assertEquals("Fix the parser", record.title);
    this.service.rename("gone", "Other");
  }

  public void testFirstPromptKeepsTheNameThePersonGave() {
    ThreadStore.Record named = this.thread();
    this.service.rename(named.id, "My name");
    ThreadStore.Record unnamed = this.thread();

    this.service.host(named.id).setTitle("Refactor the parser");
    this.service.host(unnamed.id).setTitle("Refactor the parser");

    assertEquals("My name", named.title);
    assertEquals("Refactor the parser", unnamed.title);
  }

  public void testClosedThreadIgnoresWhatItsAgentStillReports() {
    ThreadStore.Record record = this.thread();
    ChatHost host = this.service.host(record.id);
    this.service.close(record.id);

    host.setTitle("Late title");
    host.needsLook(true);

    assertNull(this.store.find(record.id));
    assertFalse(record.unread);
    assertEquals(ThreadStore.Record.DEFAULT_TITLE, record.title);
    assertNull(this.service.host(record.id));
  }

  public void testTurnEndedUnseenMarksUnreadAndOpeningClearsIt() {
    ThreadStore.Record record = this.thread();

    this.service.host(record.id).needsLook(false);
    assertTrue(record.unread);
    this.service.select(record.id);

    assertFalse(record.unread);
    assertTrue(record.seenAt > 0);
  }

  public void testMarkUnreadSkipsArchivedThreads() {
    ThreadStore.Record record = this.thread();
    ThreadStore.Record archived = this.thread();
    archived.archived = true;

    this.service.markUnread(record.id);
    this.service.markUnread(record.id);
    this.service.markUnread(archived.id);

    assertTrue(record.unread);
    assertFalse(archived.unread);
  }

  public void testSnoozingTheActiveThreadShowsAnother() {
    ThreadStore.Record other = this.thread();
    ThreadStore.Record shown = this.thread();
    this.service.select(shown.id);

    this.service.snooze(shown.id, this.later());

    assertTrue(shown.snoozedUntil > 0);
    assertEquals(other.id, this.store.activeId());
  }

  public void testSnoozingTheOnlyThreadOpensANewOne() {
    ThreadStore.Record only = this.thread();
    this.service.select(only.id);

    this.service.snooze(only.id, this.later());

    ThreadStore.Record active = this.active();
    assertNotNull(active);
    assertNotSame(only, active);
    assertEquals(0, active.snoozedUntil);
  }

  public void testSnoozingTheParentOfTheShownSubThreadShowsAnother() {
    ThreadStore.Record other = this.thread();
    ThreadStore.Record parent = this.thread();
    ThreadStore.Record child = this.child(parent);
    this.service.select(child.id);

    this.service.snooze(parent.id, this.later());

    assertEquals(other.id, this.store.activeId());
  }

  public void testReplacementSkipsSubThreadsOfTheSnoozedParent() {
    ThreadStore.Record other = this.thread();
    ThreadStore.Record parent = this.thread();
    this.child(parent);
    this.service.select(parent.id);

    this.service.snooze(parent.id, this.later());

    assertEquals(other.id, this.store.activeId());
  }

  public void testSnoozeInThePastOrOfAnArchivedThreadDoesNothing() {
    ThreadStore.Record record = this.thread();
    ThreadStore.Record archived = this.thread();
    archived.archived = true;

    this.service.snooze(record.id, System.currentTimeMillis() - 1);
    this.service.snooze(archived.id, this.later());
    this.service.snooze("gone", this.later());

    assertEquals(0, record.snoozedUntil);
    assertEquals(0, archived.snoozedUntil);
  }

  public void testSnoozingAgainReplacesTheTime() {
    ThreadStore.Record record = this.thread();
    long first = this.later();

    this.service.snooze(record.id, first);
    this.service.snooze(record.id, first + DAY);

    assertEquals(first + DAY, record.snoozedUntil);
  }

  public void testOpeningASubThreadWakesItsSnoozedParent() {
    ThreadStore.Record parent = this.thread();
    ThreadStore.Record child = this.child(parent);
    this.service.snooze(parent.id, this.later());

    this.service.select(child.id);

    assertEquals(0, parent.snoozedUntil);
    assertFalse(this.store.isSnoozeHidden(child));
  }

  public void testWakeNowOnAHiddenSubThreadWakesItsParentAndTwiceIsHarmless() {
    ThreadStore.Record parent = this.thread();
    ThreadStore.Record child = this.child(parent);
    this.service.snooze(parent.id, this.later());

    this.service.wake(child.id);
    this.service.wake(child.id);
    this.service.wake("gone");

    assertEquals(0, parent.snoozedUntil);
    assertTrue(child.seenAt > 0);
  }

  public void testQuestionWakesTheThreadAndItsParentAsUnread() {
    ThreadStore.Record parent = this.thread();
    ThreadStore.Record child = this.child(parent);
    this.service.snooze(parent.id, this.later());
    this.service.snooze(child.id, this.later());

    this.service.host(child.id).needsLook(true);

    assertEquals(0, child.snoozedUntil);
    assertEquals(0, parent.snoozedUntil);
    assertTrue(child.unread);
  }

  public void testTurnEndedKeepsTheSnooze() {
    ThreadStore.Record record = this.thread();
    this.service.snooze(record.id, this.later());

    this.service.host(record.id).needsLook(false);

    assertTrue(record.snoozedUntil > 0);
    assertTrue(record.unread);
  }

  public void testRestoringASnoozedParentKeepsTheShownSubThreadVisible() {
    ThreadStore.Record parent = this.thread();
    ThreadStore.Record child = this.child(parent);
    parent.snoozedUntil = this.later();
    parent.archived = true;
    this.service.select(child.id);

    this.service.restore(parent.id);

    assertFalse(parent.archived);
    assertEquals(child.id, this.store.activeId());
    assertFalse(this.store.isSnoozeHidden(child));
  }

  public void testUndoingTheArchiveOfASnoozedThreadKeepsItSnoozed() {
    ThreadStore.Record shown = this.thread();
    ThreadStore.Record snoozed = this.thread();
    this.service.select(shown.id);
    this.service.snooze(snoozed.id, this.later());
    this.service.archive(snoozed.id);

    this.service.restore(snoozed.id);

    assertFalse(snoozed.archived);
    assertTrue(snoozed.snoozedUntil > 0);
  }

  public void testTickWakesDueSnoozesAsUnreadButNotArchivedOnes() {
    ThreadStore.Record due = this.thread();
    ThreadStore.Record archived = this.thread();
    ThreadStore.Record later = this.thread();
    long now = System.currentTimeMillis();
    due.snoozedUntil = now - 1;
    archived.snoozedUntil = now - 1;
    archived.archived = true;
    later.snoozedUntil = now + DAY;

    this.service.upkeep().tick(now);

    assertEquals(0, due.snoozedUntil);
    assertTrue(due.unread);
    assertEquals(now, due.seenAt);
    assertEquals(now - 1, archived.snoozedUntil);
    assertEquals(now + DAY, later.snoozedUntil);
  }

  public void testWokenThreadIsNotArchivedForIdlingRightAway() {
    ChatSettings.state().settleIdle = true;
    ThreadStore.Record record = this.thread();
    long now = System.currentTimeMillis();
    record.updatedAt = now - 30 * DAY;
    record.snoozedUntil = now - 1;

    this.service.upkeep().tick(now);
    record.unread = false;
    this.service.upkeep().tick(now + DAY);

    assertFalse(record.archived);
  }

  public void testMergedPullRequestArchivesItsWorktreeThreadOnce() {
    ChatSettings.state().settleOnPullRequest = true;
    ThreadStore.Record shown = this.thread();
    this.service.select(shown.id);
    ThreadStore.Record record = this.thread();
    record.workPath = "D:/code/app-tako/fix";
    record.pullRequest = new GitInfo.PullRequest("#7", "Fix", MERGED_URL, "merged");
    long now = System.currentTimeMillis();

    this.service.upkeep().tick(now);
    assertTrue(record.archived);
    assertEquals(MERGED_URL, record.settledPullRequest);
    this.service.restore(record.id);
    this.service.upkeep().tick(now + DAY);

    assertFalse(record.archived);
  }

  public void testIdleThreadsAreArchivedUnlessKept() {
    ChatSettings.state().settleIdle = true;
    long now = System.currentTimeMillis();
    ThreadStore.Record shown = this.thread();
    this.service.select(shown.id);
    shown.updatedAt = 0;
    shown.seenAt = 0;
    ThreadStore.Record idle = this.thread();
    ThreadStore.Record pinned = this.thread();
    pinned.pinned = true;
    ThreadStore.Record run = this.thread();
    this.makeRun(run);
    ThreadStore.Record parent = this.thread();
    ThreadStore.Record child = this.child(parent);
    ThreadStore.Record unread = this.thread();
    unread.unread = true;
    for (ThreadStore.Record record : this.store.all()) {
      record.updatedAt = now - 8 * DAY;
      record.seenAt = 0;
    }

    this.service.upkeep().tick(now);

    assertTrue(idle.archived);
    assertTrue(child.archived);
    assertFalse(shown.archived);
    assertFalse(pinned.archived);
    assertFalse(run.archived);
    assertFalse(parent.archived);
    assertFalse(unread.archived);
    this.service.upkeep().tick(now);
    assertTrue(parent.archived);
  }

  public void testRulesOffArchiveNothing() {
    ThreadStore.Record shown = this.thread();
    this.service.select(shown.id);
    ThreadStore.Record record = this.thread();
    record.updatedAt = 0;
    record.workPath = "D:/code/app-tako/fix";
    record.pullRequest = new GitInfo.PullRequest("#7", "Fix", MERGED_URL, "merged");

    this.service.upkeep().tick(System.currentTimeMillis());

    assertFalse(record.archived);
  }

  public void testRefreshWithoutTheRuleOrAnOpenPullRequestAsksNothing() {
    ThreadStore.Record record = this.thread();
    record.workPath = Path.of(System.getProperty("java.io.tmpdir"), "tako-missing-worktree").toString();
    record.pullRequest = new GitInfo.PullRequest("#7", "Fix", MERGED_URL, "open");
    this.service.upkeep().refreshPullRequests();
    assertFalse(this.service.upkeep().isRefreshing());

    ChatSettings.state().settleOnPullRequest = true;
    record.pullRequest = new GitInfo.PullRequest("#7", "Fix", MERGED_URL, "merged");
    ThreadStore.Record shared = this.thread();
    shared.pullRequest = new GitInfo.PullRequest("#8", "Other", "https://github.com/o/r/pull/8", "open");
    this.service.upkeep().refreshPullRequests();

    assertFalse(this.service.upkeep().isRefreshing());
  }

  public void testRefreshedStateIsKeptOnlyForTheSamePullRequest() {
    GitInfo.PullRequest open = new GitInfo.PullRequest("#7", "Fix", MERGED_URL, "open");
    GitInfo.PullRequest merged = new GitInfo.PullRequest("#7", "Fix", MERGED_URL, "merged");
    ThreadStore.Record kept = this.thread();
    kept.pullRequest = open;
    ThreadStore.Record changed = this.thread();
    changed.pullRequest = new GitInfo.PullRequest("#9", "New", "https://github.com/o/r/pull/9", "open");
    ThreadStore.Record closed = this.thread();
    closed.pullRequest = open;
    this.service.close(closed.id);

    this.service.upkeep().store(List.of(new ThreadUpkeep.Ask(kept, open, List.of()),
      new ThreadUpkeep.Ask(changed, open, List.of()), new ThreadUpkeep.Ask(closed, open, List.of())),
      Map.of(MERGED_URL, merged));

    assertEquals(merged, kept.pullRequest);
    assertEquals("#9", changed.pullRequest.number());
    assertEquals(open, closed.pullRequest);
  }

  public void testRestoringAThreadWhoseSnoozeEndedMeanwhileWakesItUnread() {
    ThreadStore.Record shown = this.thread();
    this.service.select(shown.id);
    ThreadStore.Record record = this.thread();
    record.snoozedUntil = System.currentTimeMillis() - 1;
    record.archived = true;

    this.service.restore(record.id);

    assertEquals(0, record.snoozedUntil);
    assertTrue(record.unread);
    assertFalse(this.store.isSnoozeHidden(record));
  }

  public void testArchivingTheShownThreadSkipsSnoozedOnesForTheNextOne() {
    ThreadStore.Record open = this.thread();
    ThreadStore.Record snoozed = this.thread();
    ThreadStore.Record shown = this.thread();
    this.service.select(shown.id);
    this.service.snooze(snoozed.id, this.later());

    this.service.archive(shown.id);

    assertEquals(open.id, this.store.activeId());
    assertTrue(this.find(snoozed).snoozedUntil > 0);
  }
}
