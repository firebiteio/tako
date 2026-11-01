package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GitHubCliTest {
  @Test
  public void createdPullRequestIsNumberedLikeTheHeader() {
    GitInfo.PullRequest created = GitHubCli.parseCreated("{\"number\": 12, \"html_url\": "
      + "\"https://github.com/o/r/pull/12\", \"title\": \"Fix ünïcode\", \"state\": \"OPEN\"}");

    assertEquals(new GitInfo.PullRequest("#12", "Fix ünïcode", "https://github.com/o/r/pull/12", "open"), created);
  }

  @Test
  public void anythingElseIsNoPullRequest() {
    assertNull(GitHubCli.parseCreated(""));
    assertNull(GitHubCli.parseCreated(null));
    assertNull(GitHubCli.parseCreated("not json"));
    assertNull(GitHubCli.parseCreated("{\"message\": \"Validation Failed\"}"));
    assertNull(GitHubCli.parseCreated("[1, 2]"));
  }

  @Test
  public void errorsNameGitHubsReasons() {
    String body = "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequest\",\"code\":\"custom\","
      + "\"message\":\"A pull request already exists for o:feat.\"},{\"resource\":\"PullRequest\",\"code\":"
      + "\"invalid\",\"field\":\"head\"}]}";

    assertEquals("A pull request already exists for o:feat.\ninvalid head",
      GitHubCli.parseError(body, "gh: Validation Failed (HTTP 422)"));
  }

  @Test
  public void errorFallsBackToTheMessageThenGhsOutput() {
    assertEquals("Not Found", GitHubCli.parseError("{\"message\": \"Not Found\"}", "gh: Not Found (HTTP 404)"));
    assertEquals("gh: Not Found (HTTP 404)", GitHubCli.parseError("", "gh: Not Found (HTTP 404)\n"));
    assertEquals("garbage", GitHubCli.parseError("garbage", ""));
    assertEquals("", GitHubCli.parseError("", ""));
  }
}
