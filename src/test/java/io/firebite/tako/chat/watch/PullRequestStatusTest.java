package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.ide.project.Forge;
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
  public void readsGitLabMergeRequest() {
    PullRequestStatus status = Gl.status();

    assertEquals(Forge.GITLAB, status.forge());
    assertEquals("!7", status.label());
    assertEquals(Gl.URL, status.url());
    assertTrue(status.isOpen());
    assertEquals(Gh.SHA_A, status.headSha());
    assertEquals("main", status.baseBranch());
    assertEquals("MERGEABLE", status.mergeable());
    assertEquals(List.of(
      new PullRequestStatus.Check("flaky", "https://git.example.com/group/sub/app/-/jobs/503",
        PullRequestStatus.Outcome.PASSED),
      new PullRequestStatus.Check("lint", "https://git.example.com/group/sub/app/-/jobs/502",
        PullRequestStatus.Outcome.PASSED),
      new PullRequestStatus.Check("test", "https://git.example.com/group/sub/app/-/jobs/501",
        PullRequestStatus.Outcome.FAILED)),
      status.checks());
    assertEquals(List.of(new PullRequestStatus.Review("3001", "erin", "APPROVED", ""),
      new PullRequestStatus.Review("3002", "erin", "COMMENTED", "Looks good overall")), status.reviews());
    assertEquals(List.of(new PullRequestStatus.Comment(3003, "dave", "src/App.java", 12, null, "Rename this",
      Gl.URL + "#note_3003")), status.comments());
  }

  @Test
  public void gitLabStatesInGitHubsTerms() {
    String request = Gl.recorded("merge-request.json");

    assertEquals("MERGED", gitLab(request.replace("\"opened\"", "\"merged\"")).state());
    assertEquals("CLOSED", gitLab(request.replace("\"opened\"", "\"closed\"")).state());
    assertEquals("CLOSED", gitLab(request.replace("\"opened\"", "\"locked\"")).state());
    assertEquals("CONFLICTING", gitLab(request.replace("\"has_conflicts\": false", "\"has_conflicts\": true"))
      .mergeable());
    assertEquals("UNKNOWN", gitLab(request.replace("\"can_be_merged\"", "\"checking\"")).mergeable());
    assertEquals(PullRequestStatus.Outcome.PENDING, PullRequestStatus.parseGitLab(request,
      "[{\"name\":\"test\",\"status\":\"running\"},{\"name\":\"deploy\",\"status\":\"manual\"}]", "[]").checks()
      .getFirst().outcome());
    assertNull(PullRequestStatus.parseGitLab("{\"message\":\"404 Not found\"}", null, null));
  }

  @Test
  public void onlyTheHeadsPipelineCounts() {
    String request = Gl.recorded("merge-request.json");

    assertEquals(new PullRequestStatus.Pipeline(42, 900), PullRequestStatus.headPipeline(request));
    assertEquals("projects/42/pipelines/900/jobs?per_page=100", PullRequestStatus.headPipeline(request).jobsPath());
    String older = request.replaceFirst("\"sha\": \"aaaaaaa1", "\"sha\": \"bbbbbbb2");
    assertNull("a pipeline of the commit before", PullRequestStatus.headPipeline(older));
    assertEquals("merged results test the head too", new PullRequestStatus.Pipeline(42, 900),
      PullRequestStatus.headPipeline(older.replace("/7/head", "/7/merge")));
    assertNull(PullRequestStatus.headPipeline(request.replace("\"head_pipeline\"", "\"no_pipeline\"")));
  }

  private static PullRequestStatus gitLab(String request) {
    return PullRequestStatus.parseGitLab(request, null, "[]");
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
