package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PullRequestStatusTest {

  @Test
  public void readsRecordedView() {
    PullRequestStatus status = Gh.status(Gh.recorded("view-failing.json"), Gh.recorded("comments.json"));

    assertEquals(12, status.number());
    assertEquals("#12", status.label());
    assertEquals("Watch pull requests 🐙", status.title());
    assertTrue(status.isOpen());
    assertEquals("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d", status.headSha());
    assertEquals("master", status.baseBranch());
    assertEquals("MERGEABLE", status.mergeable());
    assertEquals(List.of(
      new PullRequestStatus.Check("CI / build", "https://github.com/firebiteio/tako/actions/runs/111/job/222",
        PullRequestStatus.Outcome.FAILED),
      new PullRequestStatus.Check("CI / test", "https://github.com/firebiteio/tako/actions/runs/111/job/223",
        PullRequestStatus.Outcome.PENDING),
      new PullRequestStatus.Check("deploy", "https://github.com/firebiteio/tako/actions/runs/112/job/300",
        PullRequestStatus.Outcome.PASSED),
      new PullRequestStatus.Check("ci/lint", "https://ci.example.com/builds/9", PullRequestStatus.Outcome.FAILED),
      new PullRequestStatus.Check("license/cla", null, PullRequestStatus.Outcome.PASSED)), status.checks());
    assertEquals(2, status.reviews().size());
    assertEquals(new PullRequestStatus.Review("PRR_kwDOAAABBB5", "bob", "COMMENTED", ""), status.reviews().get(1));
  }

  @Test
  public void readsReviewCommentsWithOutdatedLines() {
    List<PullRequestStatus.Comment> comments = Gh.status(Gh.recorded("view-failing.json"),
      Gh.recorded("comments.json")).comments();

    assertEquals(new PullRequestStatus.Comment(1002, "bob", "src/main/java/io/firebite/tako/Foo.java", 42, 40,
      "nit: make this `final`: ünïcödé 👍", "https://github.com/firebiteio/tako/pull/12#discussion_r1002"),
      comments.get(0));
    assertNull(comments.get(1).line());
    assertEquals(Integer.valueOf(7), comments.get(1).originalLine());
  }

  @Test
  public void reRunReplacesTheEarlierRunOfTheSameCheck() {
    PullRequestStatus status = Gh.status(Gh.recorded("view-rerun.json"), null);

    assertEquals(2, status.checks().size());
    assertEquals(PullRequestStatus.Outcome.PASSED, status.checks().get(0).outcome());
    assertEquals("https://github.com/firebiteio/tako/actions/runs/111/job/250", status.checks().get(0).url());
  }

  @Test
  public void notAPullRequest() {
    assertNull(PullRequestStatus.parse(null, null));
    assertNull(PullRequestStatus.parse("", "[]"));
    assertNull(PullRequestStatus.parse("{}", "[]"));
    assertNull(PullRequestStatus.parse("[1,2]", "[]"));
    assertNull(PullRequestStatus.parse("not json {", "[]"));
  }

  @Test
  public void brokenCommentsReadAsNone() {
    String view = Gh.recorded("view-failing.json");

    assertTrue(PullRequestStatus.parse(view, "{\"message\":\"Not Found\"}").comments().isEmpty());
    assertTrue(PullRequestStatus.parse(view, "[1, null, {\"body\":\"no id\"}").comments().isEmpty());
    assertTrue(PullRequestStatus.parse(view, "[1, null, {\"body\":\"no id\"}]").comments().isEmpty());
  }

  @Test
  public void missingFieldsAreTolerated() {
    PullRequestStatus status = PullRequestStatus.parse("{\"url\":\"https://github.com/o/r/pull/1\","
      + "\"statusCheckRollup\":[{\"__typename\":\"CheckRun\"},{\"__typename\":\"StatusContext\"},null,3],"
      + "\"reviews\":[{\"state\":\"APPROVED\"},{\"id\":\"R1\",\"author\":null}]}", null);

    assertEquals("", status.state());
    assertEquals(2, status.checks().size());
    assertEquals(PullRequestStatus.Outcome.PENDING, status.checks().get(0).outcome());
    assertEquals(PullRequestStatus.Outcome.PENDING, status.checks().get(1).outcome());
    assertEquals(List.of(new PullRequestStatus.Review("R1", "", "", "")), status.reviews());
  }

  @Test
  public void manyChecks() {
    List<com.google.gson.JsonObject> checks = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      checks.add(Gh.run("job " + i, i == 250 ? "TIMED_OUT" : "SUCCESS"));
    }
    PullRequestStatus status = Gh.status(Gh.view("OPEN", Gh.SHA_A, "MERGEABLE", checks, List.of()), null);

    assertEquals(500, status.checks().size());
    assertEquals(1, status.count(PullRequestStatus.Outcome.FAILED));
  }
}
