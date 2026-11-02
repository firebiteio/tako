package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ForgeCallTest {

  private static ForgeCall.Kind kind(int exitCode, String err) {
    return ForgeCall.classify(new ForgeCall.Result(exitCode, "", err)).kind();
  }

  @Test
  public void successIsNoFailure() {
    assertNull(ForgeCall.classify(new ForgeCall.Result(0, "{}", "warning: something")));
  }

  @Test
  public void notStartedMeansMissing() {
    assertEquals(ForgeCall.Kind.MISSING, kind(ForgeCall.NOT_STARTED, "Cannot run program \"gh\""));
  }

  @Test
  public void timeoutIsAnOrdinaryFailureNotMissing() {
    ForgeCall.Failure failure = ForgeCall
      .classify(new ForgeCall.Result(ForgeCall.TIMED_OUT, "", "gh did not answer within 20 seconds"));

    assertEquals(ForgeCall.Kind.OTHER, failure.kind());
    assertEquals("gh did not answer within 20 seconds", failure.message());
  }

  @Test
  public void authTextsMeanLoggedOut() {
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(4, ""));
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(1, "To get started with GitHub CLI, please run:  gh auth login"));
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(1, "You are not logged into any GitHub hosts. Run gh auth login"));
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(1, "gh: Bad credentials (HTTP 401)"));
  }

  @Test
  public void glabAuthTextsMeanLoggedOut() {
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(1, "glab: 401 Unauthorized (HTTP 401)"));
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(1, "API call failed: GET https://gitlab.com/api/v4/user: 401 "
      + "{message: 401 Unauthorized}"));
    assertEquals(ForgeCall.Kind.LOGGED_OUT, kind(1, "No token found. Run glab auth login"));
  }

  @Test
  public void glabRateLimitTexts() {
    assertEquals(ForgeCall.Kind.RATE_LIMITED, kind(1, "glab: 429 Too Many Requests (HTTP 429)"));
  }

  @Test
  public void rateLimitTexts() {
    assertEquals(ForgeCall.Kind.RATE_LIMITED, kind(1, "GraphQL: API rate limit exceeded for user ID 1."));
    assertEquals(ForgeCall.Kind.RATE_LIMITED, kind(1, "gh: You have exceeded a secondary Rate Limit (HTTP 403)"));
  }

  @Test
  public void otherFailureTellsTheFirstNonBlankLine() {
    ForgeCall.Failure failure = ForgeCall
      .classify(new ForgeCall.Result(1, "", "\n\n  GraphQL: Could not resolve to a PullRequest"
        + " with the number of 99.  \nmore"));

    assertEquals(ForgeCall.Kind.OTHER, failure.kind());
    assertEquals("GraphQL: Could not resolve to a PullRequest with the number of 99.", failure.message());
  }

  @Test
  public void silentFailureNamesTheExitCode() {
    assertEquals("exited with 2", ForgeCall.classify(new ForgeCall.Result(2, "", "")).message());
  }

  @Test
  public void longMessageIsCut() {
    assertEquals(200, ForgeCall.classify(new ForgeCall.Result(1, "", "x".repeat(5000))).message().length());
  }

  @Test
  public void executableThatDoesNotExistCannotStart() {
    ForgeCall.Result result = ForgeCall.run("tako-no-such-gh-executable", Map.of(),
      Path.of(System.getProperty("user.home")), List.of("pr", "view"));

    assertEquals(ForgeCall.NOT_STARTED, result.exitCode());
    assertEquals(ForgeCall.Kind.MISSING, ForgeCall.classify(result).kind());
  }
}
