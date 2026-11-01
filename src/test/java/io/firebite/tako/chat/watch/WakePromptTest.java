package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WakePromptTest {

  private static PullRequestWatch watch() {
    PullRequestWatch watch = new PullRequestWatch();
    watch.url = Gh.URL;
    watch.number = "#12";
    return watch;
  }

  private static String compose(List<WatchEvent> events, PullRequestWatch.End end) {
    return WakePrompt.compose(watch(), new PullRequestWatch.Update(watch(), events, end));
  }

  private static PullRequestStatus.Comment comment(long id, Integer line, Integer originalLine, String body) {
    return new PullRequestStatus.Comment(id, "bob", "src/Foo.java", line, originalLine, body, Gh.URL + "#r" + id);
  }

  @Test
  public void tellsFailedChecksWithLogHints() {
    String prompt = compose(List.of(new WatchEvent.ChecksFailed(Gh.SHA_A, List.of(
      new PullRequestStatus.Check("CI / build", "https://github.com/firebiteio/tako/actions/runs/111/job/222",
        PullRequestStatus.Outcome.FAILED),
      new PullRequestStatus.Check("ci/lint", "https://ci.example.com/9", PullRequestStatus.Outcome.FAILED),
      new PullRequestStatus.Check("no url", null, PullRequestStatus.Outcome.FAILED)))), null);

    assertTrue(prompt, prompt.startsWith("Update on pull request #12 (" + Gh.URL + "), which Tako is watching for "
      + "you:\n- Checks failed on aaaaaaa:\n"
      + "  - CI / build https://github.com/firebiteio/tako/actions/runs/111/job/222 (logs: gh run view 111 -R "
      + "github.com/firebiteio/tako --log-failed)\n"
      + "  - ci/lint https://ci.example.com/9\n"
      + "  - no url\n"));
    assertTrue(prompt.contains("unwatch_pull_request"));
    assertTrue(prompt.contains("Tako wakes you on the next change"));
  }

  @Test
  public void tellsEveryKindOfEvent() {
    String prompt = compose(List.of(new WatchEvent.ChecksPassed(Gh.SHA_B, 1),
      new WatchEvent.Conflicting("main"),
      new WatchEvent.Reviewed(new PullRequestStatus.Review("R1", "alice", "CHANGES_REQUESTED", "Split it")),
      new WatchEvent.Reviewed(new PullRequestStatus.Review("R2", "", "APPROVED", "")),
      new WatchEvent.Commented(comment(1, 42, 40, "nit")),
      new WatchEvent.Commented(comment(2, null, 7, "typo")),
      new WatchEvent.Commented(comment(3, null, null, "file level"))), null);

    assertTrue(prompt, prompt.contains("- All 1 check passed on bbbbbbb.\n"));
    assertTrue(prompt.contains("- The branch now has merge conflicts with main.\n"));
    assertTrue(prompt.contains("- alice reviewed (changes requested): \"Split it\"\n"));
    assertTrue(prompt.contains("- Someone reviewed (approved)\n"));
    assertTrue(prompt.contains("- bob commented on src/Foo.java:42: \"nit\" " + Gh.URL + "#r1\n"));
    assertTrue(prompt.contains("- bob commented on src/Foo.java:7 (outdated): \"typo\""));
    assertTrue(prompt.contains("- bob commented on src/Foo.java: \"file level\""));
  }

  @Test
  public void mergedEndsWithoutAskingToWaitForMore() {
    String prompt = compose(List.of(new WatchEvent.Merged()), PullRequestWatch.End.MERGED_OR_CLOSED);

    assertTrue(prompt.endsWith("- The pull request was merged. Tako stopped watching it."));
    assertFalse(prompt.contains("next change"));
  }

  @Test
  public void commentLoopSaysTheWatchStopped() {
    String prompt = compose(List.of(new WatchEvent.Commented(comment(1, 1, 1, "again"))),
      PullRequestWatch.End.COMMENT_LOOP);

    assertTrue(prompt.endsWith("Tako stopped watching after 10 comment-only updates in a row. Call "
      + "watch_pull_request to watch it again."));
  }

  @Test
  public void listsAreCapped() {
    List<WatchEvent> events = new ArrayList<>();
    List<PullRequestStatus.Check> checks = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      events.add(new WatchEvent.Commented(comment(i, i, i, "comment " + i)));
      checks.add(new PullRequestStatus.Check("job " + i, null, PullRequestStatus.Outcome.FAILED));
    }
    events.add(new WatchEvent.ChecksFailed(Gh.SHA_A, checks));

    String prompt = compose(events, null);

    assertTrue(prompt.contains("comment 9\""));
    assertFalse(prompt.contains("comment 10\""));
    assertTrue(prompt.contains("\n- and 15 more\n"));
    assertTrue(prompt.contains("  - job 9\n"));
    assertFalse(prompt.contains("job 10"));
    assertTrue(prompt.contains("\n  - and 15 more\n"));
  }

  @Test
  public void bodyIsOneLineWithoutHtmlCommentsAndCut() {
    assertEquals("Please split it ✨", WakePrompt.body("Please <!-- hidden\nmarker -->split\r\n\r\n  it ✨"));
    assertEquals("", WakePrompt.body("  <!-- only -->  "));

    String longBody = "a".repeat(WakePrompt.BODY_LENGTH - 2) + "😀😀😀";
    String cut = WakePrompt.body(longBody);

    assertTrue(cut.length() <= WakePrompt.BODY_LENGTH);
    assertTrue(cut.endsWith("…"));
    assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 2)));
  }
}
