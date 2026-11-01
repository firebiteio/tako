package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class GitInfoTest {

  @Test
  public void parsesGitHubPullRequest() {
    GitInfo.PullRequest pullRequest = GitInfo.parseGitHub(
      "{\"number\":42,\"title\":\"Add threads\",\"url\":\"https://github.com/o/r/pull/42\",\"state\":\"OPEN\"}");

    assertEquals(new GitInfo.PullRequest("#42", "Add threads", "https://github.com/o/r/pull/42", "open"), pullRequest);
  }

  @Test
  public void parsesGitLabMergeRequest() {
    GitInfo.PullRequest mergeRequest = GitInfo.parseGitLab(
      "{\"iid\":7,\"title\":\"Fix login\",\"web_url\":\"https://gitlab.com/o/r/-/merge_requests/7\",\"state\":\"merged\"}");

    assertEquals("!7", mergeRequest.number());
    assertEquals("merged", mergeRequest.state());
  }

  @Test
  public void gitLabOpenedReadsAsOpen() {
    assertEquals("open", GitInfo.parseGitLab(
      "{\"iid\":8,\"title\":\"t\",\"web_url\":\"https://gitlab.com/o/r/-/merge_requests/8\",\"state\":\"opened\"}")
      .state());
  }

  @Test
  public void mainCheckoutIsNoWorktree() {
    assertNull(GitInfo.worktreeName(Path.of("/repo"), ".git", ".git", "/repo"));
  }

  @Test
  public void linkedWorktreeIsNamedAfterItsFolder() {
    assertEquals("feature-login", GitInfo.worktreeName(Path.of("/repo/.claude/worktrees/feature-login"),
      "/repo/.git/worktrees/feature-login", "/repo/.git", "/repo/.claude/worktrees/feature-login"));
  }

  @Test
  public void noPullRequestWhenCliPrintsNothing() {
    assertNull(GitInfo.parseGitHub(null));
    assertNull(GitInfo.parseGitLab("not json"));
  }

  @Test
  public void knownPullRequestIsAskedForByItsUrlOrNumber() {
    assertEquals(List.of("gh", "pr", "view", "https://github.com/o/r/pull/7", "--json", "number,title,url,state"),
      GitInfo.viewCommand(new GitInfo.PullRequest("#7", "Fix", "https://github.com/o/r/pull/7", "open")));
    assertEquals(List.of("glab", "mr", "view", "12", "-F", "json"),
      GitInfo.viewCommand(new GitInfo.PullRequest("!12", "Fix", "https://gitlab.com/o/r/-/merge_requests/12",
        "open")));
  }

  @Test
  public void strangePullRequestIsNotAskedFor() {
    assertNull(GitInfo.viewCommand(new GitInfo.PullRequest("#7", "Fix", "--web", "open")));
    assertNull(GitInfo.viewCommand(new GitInfo.PullRequest("!-1", "Fix", "https://gitlab.com/x", "open")));
    assertNull(GitInfo.viewCommand(new GitInfo.PullRequest("#null", "Fix", "https://github.com/x", "open")));
    assertNull(GitInfo.viewCommand(new GitInfo.PullRequest("#", "Fix", "https://github.com/x", "open")));
    assertNull(GitInfo.viewCommand(new GitInfo.PullRequest("7", "Fix", "https://github.com/x", "open")));
  }
}
