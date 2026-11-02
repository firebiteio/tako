package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GitLabCliTest {

  @Test
  public void errorMessageInItsShapes() {
    assertEquals("Another open merge request already exists for this source branch: !5", GitLabCli.parseError(
      "{\"message\":[\"Another open merge request already exists for this source branch: !5\"]}", "glab: 409"));
    assertEquals("404 Project Not Found", GitLabCli.parseError("{\"message\":\"404 Project Not Found\"}", ""));
    assertEquals("title can't be blank", GitLabCli.parseError("{\"message\":{\"title\":[\"can't be blank\"]}}", ""));
    assertEquals("insufficient_scope", GitLabCli.parseError("{\"error\":\"insufficient_scope\"}", ""));
  }

  @Test
  public void withoutBodyTheErrorOutputTells() {
    assertEquals("glab: 502 Bad Gateway", GitLabCli.parseError("", " glab: 502 Bad Gateway\n"));
    assertEquals("<html>", GitLabCli.parseError("<html>", ""));
  }

  @Test
  public void eachForgeHasItsCli() {
    assertEquals(GitHubCli.INSTANCE, ForgeCli.of(Forge.GITHUB));
    assertEquals(GitLabCli.INSTANCE, ForgeCli.of(Forge.GITLAB));
  }
}
