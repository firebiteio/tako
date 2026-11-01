package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GhCliTest {

  private static GhCli.Kind kind(int exitCode, String err) {
    return GhCli.classify(new GhCli.Result(exitCode, "", err)).kind();
  }

  @Test
  public void successIsNoFailure() {
    assertNull(GhCli.classify(new GhCli.Result(0, "{}", "warning: something")));
  }

  @Test
  public void notStartedMeansMissing() {
    assertEquals(GhCli.Kind.MISSING, kind(GhCli.NOT_STARTED, "Cannot run program \"gh\""));
  }

  @Test
  public void timeoutIsAnOrdinaryFailureNotMissing() {
    GhCli.Failure failure = GhCli
      .classify(new GhCli.Result(GhCli.TIMED_OUT, "", "gh did not answer within 20 seconds"));

    assertEquals(GhCli.Kind.OTHER, failure.kind());
    assertEquals("gh did not answer within 20 seconds", failure.message());
  }

  @Test
  public void authTextsMeanLoggedOut() {
    assertEquals(GhCli.Kind.LOGGED_OUT, kind(4, ""));
    assertEquals(GhCli.Kind.LOGGED_OUT, kind(1, "To get started with GitHub CLI, please run:  gh auth login"));
    assertEquals(GhCli.Kind.LOGGED_OUT, kind(1, "You are not logged into any GitHub hosts. Run gh auth login"));
    assertEquals(GhCli.Kind.LOGGED_OUT, kind(1, "gh: Bad credentials (HTTP 401)"));
  }

  @Test
  public void rateLimitTexts() {
    assertEquals(GhCli.Kind.RATE_LIMITED, kind(1, "GraphQL: API rate limit exceeded for user ID 1."));
    assertEquals(GhCli.Kind.RATE_LIMITED, kind(1, "gh: You have exceeded a secondary Rate Limit (HTTP 403)"));
  }

  @Test
  public void otherFailureTellsTheFirstNonBlankLine() {
    GhCli.Failure failure = GhCli.classify(new GhCli.Result(1, "", "\n\n  GraphQL: Could not resolve to a PullRequest"
      + " with the number of 99.  \nmore"));

    assertEquals(GhCli.Kind.OTHER, failure.kind());
    assertEquals("GraphQL: Could not resolve to a PullRequest with the number of 99.", failure.message());
  }

  @Test
  public void silentFailureNamesTheExitCode() {
    assertEquals("gh exited with 2", GhCli.classify(new GhCli.Result(2, "", "")).message());
  }

  @Test
  public void longMessageIsCut() {
    assertEquals(200, GhCli.classify(new GhCli.Result(1, "", "x".repeat(5000))).message().length());
  }

  @Test
  public void executableThatDoesNotExistCannotStart() {
    GhCli.Result result = GhCli.system("tako-no-such-gh-executable", Map.of())
      .run(Path.of(System.getProperty("user.home")), List.of("pr", "view"));

    assertEquals(GhCli.NOT_STARTED, result.exitCode());
    assertEquals(GhCli.Kind.MISSING, GhCli.classify(result).kind());
  }
}
