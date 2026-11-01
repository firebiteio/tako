package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.chat.store.ThreadStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class LimitResumesTest {
  private static final long MINUTE = 60_000;
  private static final long HOUR = 60 * MINUTE;
  private static final long START = 1_900_000_000_000L;

  private final Map<String, Long> lastResumes = new HashMap<>();
  private final List<ThreadStore.Record> threads = new ArrayList<>();
  private final Set<ThreadStore.Record> busy = new HashSet<>();
  private long now = START;
  private final LimitResumes resumes = new LimitResumes(() -> this.now, this.lastResumes);

  private ThreadStore.Record thread(String agent) {
    ThreadStore.Record record = new ThreadStore.Record();
    record.id = "thread-" + this.threads.size();
    record.agent = agent;
    this.threads.add(record);
    return record;
  }

  /** A thread whose agent stopped on a limit resetting {@code inMillis} from now, scheduled to resume. */
  private ThreadStore.Record waiting(String agent, long inMillis) {
    ThreadStore.Record record = this.thread(agent);
    this.resumes.onLimit(record, "5h", this.now + inMillis, this.threads);
    assertTrue(this.resumes.schedule(record));
    return record;
  }

  private List<LimitResumes.Resume> fire() {
    return this.resumes.due(this.threads, this.busy::contains).fire();
  }

  private List<ThreadStore.Record> fired() {
    List<ThreadStore.Record> records = new ArrayList<>();
    for (LimitResumes.Resume resume : this.fire()) {
      records.add(resume.record());
    }
    return records;
  }

  @Test
  public void aStopKeepsTheLimitAndItsResetUnscheduled() {
    ThreadStore.Record record = this.thread("claude");
    List<ThreadStore.Record> changed = this.resumes.onLimit(record, "5h", START + HOUR, this.threads);

    assertEquals(List.of(record), changed);
    assertEquals("5h", record.limit.label);
    assertEquals(START + HOUR, record.limit.resetsAt);
    assertEquals(START, record.limit.hitAt);
    assertFalse(record.limit.scheduled);
    assertEquals(START + HOUR + ThreadStore.LimitStop.GRACE_MILLIS, record.limit.fireAt());
    assertEquals(List.of(), this.fire());
  }

  @Test
  public void theResumeGoesOutAMinuteAfterTheResetAndNotBefore() {
    ThreadStore.Record record = this.waiting("claude", HOUR);
    this.now = START + HOUR;
    assertEquals(List.of(), this.fire());
    this.now = START + HOUR + ThreadStore.LimitStop.GRACE_MILLIS - 1;
    assertEquals(List.of(), this.fire());
    this.now++;

    List<LimitResumes.Resume> fire = this.fire();

    assertEquals(1, fire.size());
    assertEquals(record, fire.getFirst().record());
    assertNull("the default prompt", fire.getFirst().prompt());
    assertNull("the stop is over", record.limit);
    assertEquals("it goes out once", List.of(), this.fire());
  }

  @Test
  public void theEditedPromptGoesOutAsTyped() {
    ThreadStore.Record record = this.waiting("codex", HOUR);
    String prompt = "Weiter mit den Tests 🐙\nund dann der Rest" + "x".repeat(20_000);
    assertTrue(this.resumes.setPrompt(record, prompt));
    this.now += 2 * HOUR;

    assertEquals(prompt, this.fire().getFirst().prompt());
  }

  @Test
  public void aBlankPromptMeansTheDefault() {
    ThreadStore.Record record = this.waiting("codex", HOUR);
    this.resumes.setPrompt(record, "custom");
    this.resumes.setPrompt(record, " \n\t ");
    assertNull(record.limit.prompt);
    assertFalse("no stop, no prompt", this.resumes.setPrompt(this.thread("codex"), "custom"));
  }

  @Test
  public void schedulingTwiceOrCancellingTwiceChangesNothingMore() {
    ThreadStore.Record record = this.waiting("claude", HOUR);
    assertFalse(this.resumes.schedule(record));
    assertTrue(this.resumes.cancel(record));
    assertFalse(this.resumes.cancel(record));
    this.now += 2 * HOUR;
    assertEquals(List.of(), this.fire());
    assertTrue(this.resumes.dismiss(record));
    assertFalse(this.resumes.dismiss(record));
    assertFalse(this.resumes.schedule(record));
  }

  @Test
  public void withoutAResetThereIsNothingToSchedule() {
    ThreadStore.Record record = this.thread("claude");
    this.resumes.onLimit(record, null, 0, this.threads);
    assertFalse(this.resumes.schedule(record));
    assertFalse("no stop at all", this.resumes.schedule(this.thread("claude")));
  }

  @Test
  public void resetsLongPastOrFarAheadAreUnknown() {
    long[] resets = {START - 2 * HOUR, START + 40 * 24 * HOUR, Long.MAX_VALUE, Long.MIN_VALUE, -1};
    for (long reset : resets) {
      ThreadStore.Record record = this.thread("claude");
      this.resumes.onLimit(record, "5h", reset, this.threads);
      assertEquals(String.valueOf(reset), 0, record.limit.resetsAt);
      assertFalse(this.resumes.schedule(record));
    }
  }

  @Test
  public void aResetJustPassedByOurClockCanStillBeScheduledAndWaitsAfterTheStop() {
    ThreadStore.Record record = this.thread("claude");
    this.resumes.onLimit(record, "5h", START - 10 * MINUTE, this.threads);
    assertTrue("the agent's clock may be behind ours", this.resumes.schedule(record));
    this.now = START + ThreadStore.LimitStop.MIN_WAIT_MILLIS - 1;
    assertEquals(List.of(), this.fire());
    this.now++;
    assertEquals(List.of(record), this.fired());
  }

  @Test
  public void aResetThatPassedWhileNobodyScheduledCannotBeScheduledAnyMore() {
    ThreadStore.Record record = this.thread("claude");
    this.resumes.onLimit(record, "5h", START + HOUR, this.threads);
    this.now = START + 2 * HOUR;
    assertFalse(this.resumes.schedule(record));
    assertEquals("the banner still says it reset", START + HOUR, record.limit.resetsAt);
  }

  @Test
  public void aPromptMakesTheResumeMoot() {
    ThreadStore.Record record = this.waiting("claude", HOUR);
    assertTrue(this.resumes.dismiss(record));
    this.now += 2 * HOUR;
    assertEquals(List.of(), this.fire());
  }

  @Test
  public void aResumeOverdueByADayIsDroppedButLessStillGoesOut() {
    ThreadStore.Record late = this.waiting("claude", HOUR);
    ThreadStore.Record later = this.waiting("codex", 2 * HOUR);
    this.now = late.limit.fireAt() + LimitResumes.MAX_OVERDUE_MILLIS + 1;
    long laterFire = later.limit.fireAt();

    LimitResumes.Due due = this.resumes.due(this.threads, this.busy::contains);

    assertEquals(1, due.fire().size());
    assertEquals(later, due.fire().getFirst().record());
    assertTrue(this.now - laterFire < LimitResumes.MAX_OVERDUE_MILLIS);
    assertFalse("shows that the limit reset", late.limit.scheduled);
    assertTrue(due.changed().contains(late));
  }

  @Test
  public void threadsOfOneAgentResumeOneAtATimeEarliestFirst() {
    long reset = START + HOUR;
    ThreadStore.Record first = this.waiting("claude", reset - this.now);
    this.now += MINUTE;
    ThreadStore.Record second = this.waiting("claude", reset - this.now);
    ThreadStore.Record other = this.waiting("codex", reset - this.now);
    this.now += MINUTE;
    ThreadStore.Record third = this.waiting("claude", reset - this.now);
    this.now = reset + HOUR;

    assertEquals(List.of(first, other), this.fired());
    this.now += LimitResumes.SPACING_MILLIS - 1;
    assertEquals(List.of(), this.fire());
    this.now++;
    assertEquals(List.of(second), this.fired());
    this.now += LimitResumes.SPACING_MILLIS;
    assertEquals(List.of(third), this.fired());
  }

  @Test
  public void spacingHoldsAcrossProjects() {
    ThreadStore.Record here = this.waiting("claude", HOUR);
    LimitResumes elsewhere = new LimitResumes(() -> this.now, this.lastResumes);
    ThreadStore.Record there = new ThreadStore.Record();
    there.agent = "claude";
    elsewhere.onLimit(there, "5h", START + HOUR, List.of(there));
    elsewhere.schedule(there);
    this.now += 2 * HOUR;

    assertEquals(List.of(here), this.fired());
    assertEquals(List.of(), elsewhere.due(List.of(there), record -> false).fire());
    this.now += LimitResumes.SPACING_MILLIS;
    assertEquals(1, elsewhere.due(List.of(there), record -> false).fire().size());
  }

  @Test
  public void aClockSetBackDoesNotHoldResumesBack() {
    this.lastResumes.put("claude", START + 10 * HOUR);
    ThreadStore.Record record = this.waiting("claude", HOUR);
    this.now += 2 * HOUR;
    assertEquals(List.of(record), this.fired());
  }

  @Test
  public void aBusyThreadWaitsForTheNextCheck() {
    ThreadStore.Record record = this.waiting("claude", HOUR);
    this.busy.add(record);
    this.now += 2 * HOUR;
    assertEquals(List.of(), this.fire());
    assertTrue(record.limit.scheduled);
    this.busy.clear();
    assertEquals(List.of(record), this.fired());
  }

  @Test
  public void aLimitHitAgainPushesTheAgentsOtherWaitingThreadsToItsReset() {
    ThreadStore.Record resumed = this.waiting("claude", HOUR);
    ThreadStore.Record waiting = this.waiting("claude", HOUR);
    ThreadStore.Record unscheduled = this.thread("claude");
    this.resumes.onLimit(unscheduled, "5h", START + HOUR, this.threads);
    ThreadStore.Record codex = this.waiting("codex", HOUR);
    this.now += 2 * HOUR;
    assertEquals(List.of(resumed, codex), this.fired());

    List<ThreadStore.Record> changed = this.resumes.onLimit(resumed, "7d", this.now + 24 * HOUR, this.threads);

    assertEquals(List.of(resumed, waiting), changed);
    assertFalse("the person decides again", resumed.limit.scheduled);
    assertEquals(this.now + 24 * HOUR, waiting.limit.resetsAt);
    assertTrue(waiting.limit.scheduled);
    assertEquals(START + HOUR, unscheduled.limit.resetsAt);
    this.now += 2 * LimitResumes.SPACING_MILLIS;
    assertEquals(List.of(), this.fire());
  }

  @Test
  public void aLimitHitAgainWithoutResetPushesNobody() {
    ThreadStore.Record resumed = this.waiting("claude", HOUR);
    ThreadStore.Record waiting = this.waiting("claude", HOUR);
    this.now += 2 * HOUR;
    this.fire();
    long fireAt = waiting.limit.fireAt();

    assertEquals(List.of(resumed), this.resumes.onLimit(resumed, null, 0, this.threads));
    assertEquals(fireAt, waiting.limit.fireAt());
  }

  @Test
  public void aLimitHitAgainBeforeTheResumeKeepsItsPromptAndSchedule() {
    ThreadStore.Record record = this.waiting("claude", HOUR);
    this.resumes.setPrompt(record, "custom");
    this.now += MINUTE;
    this.resumes.onLimit(record, "7d", START + 2 * HOUR, this.threads);
    assertEquals("7d", record.limit.label);
    assertEquals(START + 2 * HOUR, record.limit.resetsAt);
    assertTrue(record.limit.scheduled);
    assertEquals("custom", record.limit.prompt);

    this.resumes.onLimit(record, null, 0, this.threads);
    assertFalse("nothing to wait for", record.limit.scheduled);
    assertEquals("custom", record.limit.prompt);
  }

  @Test
  public void aLateResetFillsInTheLimitThatHadNone() {
    ThreadStore.Record record = this.thread("codex");
    ThreadStore.Record waiting = this.waiting("codex", HOUR);
    this.resumes.onLimit(record, null, 0, this.threads);

    List<ThreadStore.Record> changed = this.resumes.onReset(record, "5h", START + 3 * HOUR, this.threads);

    assertEquals(List.of(record, waiting), changed);
    assertEquals("5h", record.limit.label);
    assertEquals(START + 3 * HOUR, record.limit.resetsAt);
    assertEquals(START, record.limit.hitAt);
    assertFalse(record.limit.scheduled);
    assertEquals("the account is limited until then", START + 3 * HOUR, waiting.limit.resetsAt);
  }

  @Test
  public void aLateResetDoesNotBringBackALimitThePersonMovedOnFrom() {
    ThreadStore.Record record = this.thread("codex");
    this.resumes.onLimit(record, null, 0, this.threads);
    this.resumes.dismiss(record);
    assertEquals(List.of(), this.resumes.onReset(record, "5h", START + HOUR, this.threads));
    assertNull(record.limit);
  }

  @Test
  public void aLateResetDoesNotReplaceAKnownOneOrTakeNonsense() {
    ThreadStore.Record record = this.thread("codex");
    this.resumes.onLimit(record, "5h", START + HOUR, this.threads);
    assertEquals(List.of(), this.resumes.onReset(record, "7d", START + 5 * HOUR, this.threads));
    assertEquals(START + HOUR, record.limit.resetsAt);

    ThreadStore.Record unknown = this.thread("codex");
    this.resumes.onLimit(unknown, null, 0, this.threads);
    assertEquals(List.of(), this.resumes.onReset(unknown, "5h", START + 400 * 24 * HOUR, this.threads));
    assertEquals(0, unknown.limit.resetsAt);
  }

  @Test
  public void aResumeScheduledBeforeALimitWithoutResetGoesOutOnceTheResetIsNamed() {
    ThreadStore.Record record = this.waiting("codex", HOUR);
    this.resumes.setPrompt(record, "custom");
    this.resumes.onLimit(record, null, 0, this.threads);
    assertFalse(record.limit.scheduled);

    this.resumes.onReset(record, "5h", START + 2 * HOUR, this.threads);

    assertTrue(record.limit.scheduled);
    assertFalse(record.limit.resumesOnceReset);
    this.now = START + 3 * HOUR;
    assertEquals("custom", this.fire().getFirst().prompt());
  }

  @Test
  public void cancellingWhileTheResetIsUnknownKeepsItFromComingBack() {
    ThreadStore.Record record = this.waiting("codex", HOUR);
    this.resumes.onLimit(record, null, 0, this.threads);
    assertTrue(this.resumes.cancel(record));
    this.resumes.onReset(record, "5h", START + 2 * HOUR, this.threads);
    assertFalse(record.limit.scheduled);
  }

  @Test
  public void onlyCommandsThatShowSomethingLeaveTheLimit() {
    assertFalse(LimitResumes.movesOn("/usage"));
    assertFalse(LimitResumes.movesOn("  /status \n"));
    assertFalse(LimitResumes.movesOn("/cost"));
    assertTrue(LimitResumes.movesOn("/clear"));
    assertTrue(LimitResumes.movesOn("/init"));
    assertTrue(LimitResumes.movesOn("/compact"));
    assertTrue(LimitResumes.movesOn("/usage now"));
    assertTrue(LimitResumes.movesOn("/plugin:skill"));
    assertTrue(LimitResumes.movesOn("/review-pr 12"));
    assertTrue(LimitResumes.movesOn("/src/Main.java why does this throw"));
    assertTrue(LimitResumes.movesOn("/"));
    assertTrue(LimitResumes.movesOn(""));
    assertTrue(LimitResumes.movesOn("continue"));
    assertTrue(LimitResumes.movesOn("/ü"));
  }
}
