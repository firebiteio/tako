package io.firebite.tako.agent.claude.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.claude.protocol.CliMessage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class SessionStoreTest {
  private static final String SESSION = "9664106a-fa43-44b4-9cc9-5a11cbf1bb36";

  private static SessionStore fixture() throws URISyntaxException {
    return new SessionStore(Path.of(SessionStoreTest.class.getResource("/sessions").toURI()));
  }

  @Test
  public void encodesProjectPathsLikeTheCli() {
    assertEquals("D--Projects-my-app", SessionStore.encode("D:\\Projects\\my-app"));
    assertEquals("D--Projects-my-app", SessionStore.encode("D:/Projects/my-app"));
  }

  @Test
  public void listsSessionWithFirstPromptAsTitle() throws Exception {
    List<SessionStore.SessionInfo> sessions = fixture().list(10);

    assertEquals(1, sessions.size());
    assertEquals(SESSION, sessions.get(0).id());
    assertTrue(sessions.get(0).title().startsWith("Read README.md, then create test.txt"));
  }

  @Test
  public void loadsMainThreadMessages() throws Exception {
    List<SessionStore.TranscriptMessage> messages = fixture().load(SESSION);

    assertEquals(CliMessage.Type.USER, messages.get(0).message().type());
    int assistants = 0;
    for (SessionStore.TranscriptMessage message : messages) {
      if (message.message().type() == CliMessage.Type.ASSISTANT) {
        assistants++;
      }
    }
    assertTrue(assistants >= 4);
  }

  @Test(expected = IOException.class)
  public void rejectsPathTraversal() throws Exception {
    fixture().load("../../etc/passwd");
  }

  @Test
  public void readsCumulativeUsageFromTheLastCostState() throws Exception {
    SessionUsage usage = fixture().usage(SESSION);

    assertEquals(0.0737158, usage.costUsd(), 1e-9);
    assertEquals(1, usage.models().size());
    assertEquals("claude-haiku-4-5-20251001", usage.models().get(0).model());
    assertEquals(46, usage.models().get(0).inputTokens());
    assertEquals(1, fixture().allUsage().size());
  }
}
