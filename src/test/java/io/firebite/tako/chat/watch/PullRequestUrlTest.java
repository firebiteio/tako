package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.firebite.tako.ide.project.Forge;
import org.junit.Test;

public class PullRequestUrlTest {

  @Test
  public void readsGitHubAddress() {
    PullRequestUrl url = PullRequestUrl.parse("https://github.com/firebiteio/Tako/pull/12");

    assertEquals(new PullRequestUrl(Forge.GITHUB, "github.com", "firebiteio/tako", 12), url);
    assertEquals("github.com/firebiteio/tako", url.repository());
    assertEquals("#12", url.label());
    assertEquals("repos/firebiteio/tako/pulls/12/comments?sort=created&direction=desc&per_page=100",
      url.commentsPath());
  }

  @Test
  public void enterpriseHostAndTailsNameTheSamePullRequest() {
    PullRequestUrl url = PullRequestUrl.parse(" https://GHE.example.com/Team/Repo/pull/7/files?w=1 ");

    assertEquals(new PullRequestUrl(Forge.GITHUB, "ghe.example.com", "team/repo", 7), url);
    assertEquals(url, PullRequestUrl.parse("https://ghe.example.com/team/repo/pull/7#discussion_r1"));
  }

  @Test
  public void readsGitLabAddressWithSubgroups() {
    PullRequestUrl url = PullRequestUrl.parse("https://Git.Example.com/Client/neo/app/-/merge_requests/4866/diffs");

    assertEquals(new PullRequestUrl(Forge.GITLAB, "git.example.com", "client/neo/app", 4866), url);
    assertEquals("git.example.com/client/neo/app", url.repository());
    assertEquals("!4866", url.label());
    assertEquals("https://git.example.com/client/neo/app/-/merge_requests/4866", url.webUrl());
    assertEquals("projects/client%2Fneo%2Fapp/merge_requests/4866", url.apiPath());
    assertEquals("projects/client%2Fneo%2Fapp/merge_requests/4866/notes?sort=desc&order_by=created_at&per_page=100",
      url.commentsPath());
    assertEquals(url, PullRequestUrl.parse("https://git.example.com/client/neo/app/-/merge_requests/4866#note_1"));
  }

  @Test
  public void notAPullRequest() {
    assertNull(PullRequestUrl.parse(null));
    assertNull(PullRequestUrl.parse(""));
    assertNull(PullRequestUrl.parse("https://github.com/o/r/issues/12"));
    assertNull(PullRequestUrl.parse("https://gitlab.com/o/-/merge_requests/7"));
    assertNull(PullRequestUrl.parse("https://gitlab.com/o/r/merge_requests/7"));
    assertNull(PullRequestUrl.parse("https://github.com/o/r/pull/0"));
    assertNull(PullRequestUrl.parse("https://github.com/o/r/pull/99999999999999999999"));
    assertNull(PullRequestUrl.parse("github.com/o/r/pull/1"));
    assertNull(PullRequestUrl.parse("https://github.com/o/r/pull/1x"));
  }
}
