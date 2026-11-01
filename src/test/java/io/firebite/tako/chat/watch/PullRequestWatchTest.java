package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PullRequestWatchTest {
  private static final String SELF = "tako-user";

  private static PullRequestStatus open(String sha, JsonObject... checks) {
    return Gh.status(Gh.open(sha, checks), "[]");
  }

  private static PullRequestWatch watch(PullRequestStatus status) {
    return PullRequestWatch.baseline(status, SELF, true);
  }

  /** Advances {@code watch} as if every update was delivered, returning the events of the last one. */
  private static List<WatchEvent> events(PullRequestWatch[] watch, PullRequestStatus status) {
    PullRequestWatch.Update update = watch[0].advance(status, true);
    watch[0] = update.next();
    return update.events();
  }

  @Test
  public void baselineTellsNothing() {
    PullRequestStatus status = Gh.status(Gh.recorded("view-failing.json"), Gh.recorded("comments.json"));
    PullRequestWatch.Update update = watch(status).advance(status, true);

    assertEquals(List.of(), update.events());
    assertNull(update.end());
  }

  @Test
  public void newFailureIsToldOnce() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", "")))};

    List<WatchEvent> events = events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE")));
    assertEquals(1, events.size());
    WatchEvent.ChecksFailed failed = (WatchEvent.ChecksFailed) events.get(0);
    assertEquals("CI / build", failed.failed().get(0).name());
    assertEquals(Gh.SHA_A, failed.headSha());

    assertEquals(List.of(), events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE"))));
  }

  @Test
  public void secondFailureLaterIsToldWithoutTheFirst() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", ""), Gh.run("test", "")))};
    events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE"), Gh.run("test", "")));

    List<WatchEvent> events = events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE"), Gh.run("test", "CANCELLED")));

    assertEquals(1, ((WatchEvent.ChecksFailed) events.get(0)).failed().size());
    assertEquals("CI / test", ((WatchEvent.ChecksFailed) events.get(0)).failed().get(0).name());
  }

  @Test
  public void reRunThatFailsAgainIsToldAgain() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", "")))};
    events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE")));
    events(watch, open(Gh.SHA_A, Gh.run("build", "")));

    assertTrue(events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE"))).get(0) instanceof WatchEvent.ChecksFailed);
  }

  @Test
  public void failingAgainOnANewCommitIsToldWithoutAPendingPollBetween() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", "")))};
    events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE")));

    List<WatchEvent> events = events(watch, open(Gh.SHA_B, Gh.run("build", "FAILURE")));

    assertEquals(Gh.SHA_B, ((WatchEvent.ChecksFailed) events.get(0)).headSha());
  }

  @Test
  public void passingAgainOnANewCommitIsTold() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", "")))};
    assertTrue(events(watch, open(Gh.SHA_A, Gh.run("build", "SUCCESS"))).get(0) instanceof WatchEvent.ChecksPassed);
    assertEquals(List.of(), events(watch, open(Gh.SHA_A, Gh.run("build", "SUCCESS"))));

    List<WatchEvent> events = events(watch, open(Gh.SHA_B, Gh.run("build", "SUCCESS")));

    assertEquals(new WatchEvent.ChecksPassed(Gh.SHA_B, 1), events.get(0));
  }

  @Test
  public void passedNeedsEveryCheckDone() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", ""), Gh.run("test", "")))};

    assertEquals(List.of(), events(watch, open(Gh.SHA_A, Gh.run("build", "SUCCESS"), Gh.run("test", ""))));
    assertEquals(List.of(new WatchEvent.ChecksPassed(Gh.SHA_A, 2)),
      events(watch, open(Gh.SHA_A, Gh.run("build", "SUCCESS"), Gh.run("test", "NEUTRAL"))));
  }

  @Test
  public void noChecksIsNeverPassedAndKeepsTheLastState() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", "FAILURE")))};

    assertEquals(List.of(), events(watch, open(Gh.SHA_B)));
    assertEquals(List.of(), events(watch, open(Gh.SHA_A, Gh.run("build", "FAILURE"))));
  }

  @Test
  public void reviewsAndCommentsOfOthersAreTold() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A))};
    String view = Gh.view("OPEN", Gh.SHA_A, "MERGEABLE", List.of(), List.of(
      Gh.review("R1", "alice", "APPROVED", ""),
      Gh.review("R2", "bob", "COMMENTED", ""),
      Gh.review("R3", "carol", "COMMENTED", "Looks fine overall"),
      Gh.review("R4", "TAKO-USER", "CHANGES_REQUESTED", "my own")));
    PullRequestStatus status = Gh.status(view, Gh.comments(Gh.comment(9, "bob", "second"), Gh.comment(8, "bob",
      "first"), Gh.comment(7, SELF, "reply from the agent")));

    List<WatchEvent> events = events(watch, status);

    assertEquals(4, events.size());
    assertEquals("R1", ((WatchEvent.Reviewed) events.get(0)).review().id());
    assertEquals("R3", ((WatchEvent.Reviewed) events.get(1)).review().id());
    assertEquals(8, ((WatchEvent.Commented) events.get(2)).comment().id());
    assertEquals(9, ((WatchEvent.Commented) events.get(3)).comment().id());
    assertEquals(List.of(), events(watch, status));
  }

  @Test
  public void commentOfAReviewSubmittedLaterIsToldThoughItsIdIsOlder() {
    PullRequestWatch[] watch = {PullRequestWatch.baseline(Gh.status(Gh.open(Gh.SHA_A),
      Gh.comments(Gh.comment(1005, "alice", "single"))), SELF, true)};

    List<WatchEvent> events = events(watch, Gh.status(Gh.open(Gh.SHA_A),
      Gh.comments(Gh.comment(1005, "alice", "single"), Gh.comment(1001, "bob", "drafted earlier"))));

    assertEquals(1001, ((WatchEvent.Commented) events.get(0)).comment().id());
  }

  @Test
  public void failedCommentsReadLosesNothing() {
    PullRequestWatch watch = watch(open(Gh.SHA_A));
    PullRequestStatus withComment = Gh.status(Gh.open(Gh.SHA_A), Gh.comments(Gh.comment(5, "bob", "hi")));

    PullRequestWatch.Update unread = watch.advance(Gh.status(Gh.open(Gh.SHA_A), null), false);
    PullRequestWatch.Update read = unread.next().advance(withComment, true);

    assertEquals(List.of(), unread.events());
    assertEquals(1, read.events().size());
  }

  @Test
  public void watchStartedWithoutCommentsLearnsThemSilentlyThenTellsNewOnes() {
    PullRequestWatch[] watch = {PullRequestWatch.baseline(Gh.status(Gh.open(Gh.SHA_A), null), SELF, false)};
    PullRequestStatus old = Gh.status(Gh.open(Gh.SHA_A), Gh.comments(Gh.comment(5, "bob", "old")));

    PullRequestWatch.Update unread = watch[0].advance(Gh.status(Gh.open(Gh.SHA_A), null), false);
    assertEquals(List.of(), unread.events());
    assertTrue(unread.next().commentsUnknown);
    watch[0] = unread.next();
    assertEquals(List.of(), events(watch, old));
    assertEquals(1, events(watch, Gh.status(Gh.open(Gh.SHA_A), Gh.comments(Gh.comment(6, "bob", "new"),
      Gh.comment(5, "bob", "old")))).size());
  }

  @Test
  public void fullWindowIgnoresOldCommentsMovingIn() {
    JsonObject[] window = new JsonObject[PullRequestWatch.COMMENT_WINDOW];
    for (int i = 0; i < window.length; i++) {
      window[i] = Gh.comment(1000 + window.length - i, "bob", "c" + i);
    }
    PullRequestWatch[] watch = {PullRequestWatch.baseline(Gh.status(Gh.open(Gh.SHA_A), Gh.comments(window)), SELF,
      true)};
    JsonObject[] shifted = new JsonObject[window.length];
    System.arraycopy(window, 1, shifted, 0, window.length - 1);
    shifted[window.length - 1] = Gh.comment(1000, "bob", "older one that moved into the window");

    assertEquals(List.of(), events(watch, Gh.status(Gh.open(Gh.SHA_A), Gh.comments(shifted))));
  }

  @Test
  public void conflictIsToldOnceAndAgainOnlyAfterItWasClean() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A))};
    PullRequestStatus conflicting = Gh.status(Gh.view("OPEN", Gh.SHA_A, "CONFLICTING", List.of(), List.of()), "[]");
    PullRequestStatus unknown = Gh.status(Gh.view("OPEN", Gh.SHA_A, "UNKNOWN", List.of(), List.of()), "[]");

    assertEquals(List.of(new WatchEvent.Conflicting("master")), events(watch, conflicting));
    assertEquals(List.of(), events(watch, unknown));
    assertEquals(List.of(), events(watch, conflicting));
    assertEquals(List.of(), events(watch, open(Gh.SHA_A)));
    assertEquals(List.of(new WatchEvent.Conflicting("master")), events(watch, conflicting));
  }

  @Test
  public void mergedIsToldAloneAndEnds() {
    PullRequestWatch watch = watch(open(Gh.SHA_A, Gh.run("build", "")));
    PullRequestStatus merged = Gh.status(Gh.view("MERGED", Gh.SHA_B, "UNKNOWN", List.of(Gh.run("build", "FAILURE")),
      List.of(Gh.review("R1", "alice", "APPROVED", ""))), "[]");

    PullRequestWatch.Update update = watch.advance(merged, true);

    assertEquals(List.of(new WatchEvent.Merged()), update.events());
    assertEquals(PullRequestWatch.End.MERGED_OR_CLOSED, update.end());
  }

  @Test
  public void closedEnds() {
    PullRequestWatch.Update update = watch(open(Gh.SHA_A)).advance(
      Gh.status(Gh.view("CLOSED", Gh.SHA_A, "MERGEABLE", List.of(), List.of()), "[]"), true);

    assertEquals(List.of(new WatchEvent.Closed()), update.events());
    assertEquals(PullRequestWatch.End.MERGED_OR_CLOSED, update.end());
  }

  @Test
  public void commentLoopEndsTheWatch() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A))};
    List<JsonObject> comments = new ArrayList<>();
    PullRequestWatch.Update update = null;
    for (int i = 1; i <= PullRequestWatch.COMMENT_WAKE_LIMIT; i++) {
      comments.add(0, Gh.comment(i, "review-bot", "again " + i));
      update = watch[0].advance(Gh.status(Gh.open(Gh.SHA_A), Gh.comments(comments.toArray(new JsonObject[0]))),
        true);
      watch[0] = update.next();
      if (i < PullRequestWatch.COMMENT_WAKE_LIMIT) {
        assertNull(update.end());
      }
    }

    assertEquals(PullRequestWatch.End.COMMENT_LOOP, update.end());
  }

  @Test
  public void quietPollResetsTheCommentLoop() {
    PullRequestWatch watch = watch(open(Gh.SHA_A));
    watch.commentWakes = PullRequestWatch.COMMENT_WAKE_LIMIT - 1;

    assertEquals(0, watch.advance(open(Gh.SHA_A), true).next().commentWakes);
  }

  @Test
  public void checkNewsResetsTheCommentLoop() {
    PullRequestWatch[] watch = {watch(open(Gh.SHA_A, Gh.run("build", "")))};
    watch[0].commentWakes = PullRequestWatch.COMMENT_WAKE_LIMIT - 1;

    PullRequestWatch.Update update = watch[0].advance(Gh.status(Gh.open(Gh.SHA_A, Gh.run("build", "FAILURE")),
      Gh.comments(Gh.comment(1, "bot", "hi"))), true);

    assertNull(update.end());
    assertEquals(0, update.next().commentWakes);
  }

  @Test
  public void advanceLeavesTheWatchAsItWas() {
    PullRequestWatch watch = watch(open(Gh.SHA_A, Gh.run("build", "")));
    String before = new Gson().toJson(watch);

    watch.advance(Gh.status(Gh.open(Gh.SHA_B, Gh.run("build", "FAILURE")), Gh.comments(Gh.comment(1, "bob", "x"))),
      true);
    watch.failed();

    assertEquals(before, new Gson().toJson(watch));
  }

  @Test
  public void savedWatchKeepsWhatItHasSeen() {
    PullRequestStatus status = Gh.status(Gh.recorded("view-failing.json"), Gh.recorded("comments.json"));
    PullRequestWatch watch = watch(status);
    watch.nextPollAt = 99;

    PullRequestWatch loaded = new Gson().fromJson(new Gson().toJson(watch), PullRequestWatch.class);

    assertEquals(0, loaded.nextPollAt);
    assertEquals(List.of(), loaded.advance(status, true).events());
  }

  @Test
  public void olderSaveWithoutListsStillWorks() {
    PullRequestWatch loaded = new Gson().fromJson("{\"url\":\"" + Gh.URL + "\",\"number\":\"#12\",\"self\":\"me\","
      + "\"failedChecks\":null}", PullRequestWatch.class);

    PullRequestWatch.Update update = loaded.advance(open(Gh.SHA_A, Gh.run("build", "FAILURE")), true);

    assertTrue(update.events().get(0) instanceof WatchEvent.ChecksFailed);
    assertFalse(update.next().failedChecks.isEmpty());
  }

  @Test
  public void failedCountsUp() {
    PullRequestWatch watch = watch(open(Gh.SHA_A));

    assertEquals(2, watch.failed().failed().failures);
    assertEquals(0, watch.failed().advance(open(Gh.SHA_A), true).next().failures);
  }
}
