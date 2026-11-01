package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.agent.RecordingListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ClaudeSessionTest {
  @Test
  public void scratchpadLivesUnderTheCliTempFolder() {
    assertEquals(Path.of("/t", "claude", "D--work-app", "s1", "scratchpad"),
      ClaudeSession.scratchpadDirectory(Map.of("TEMP", "/t"), "D:\\work\\app", "s1"));
    assertEquals(Path.of("/own", "claude", "-work-app", "s1", "scratchpad"),
      ClaudeSession.scratchpadDirectory(Map.of("CLAUDE_CODE_TMPDIR", "/own", "TMPDIR", "/t"), "/work/app", "s1"));
  }

  @Test
  public void promptSentAsTheTurnEndsIsTakenAtOnce() throws Exception {
    RecordingListener recorder = new RecordingListener();
    AtomicReference<ClaudeSession> session = new AtomicReference<>();
    AtomicBoolean isSent = new AtomicBoolean();
    AgentListener listener = new AgentListener() {
      @Override
      public void onEvent(AgentEvent event) {
        recorder.onEvent(event);
        if (event instanceof AgentEvent.TurnEnded && !isSent.getAndSet(true)) {
          session.get().prompt("/cost", List.of(), 2);
        }
      }

      @Override
      public void onHistory(List<AgentEvent> events) {
      }

      @Override
      public void onPermission(String requestId, PermissionPrompt prompt) {
      }

      @Override
      public void onFileEdited(String path, String originalContent) {
      }

      @Override
      public void onExit(int exitCode, String stderrTail) {
      }
    };
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    ClaudeLaunch command = new ClaudeLaunch(List.of(java.toString(), "-cp",
      FakeExecutable.classes(FakeClaude.class).toString(), FakeClaude.class.getName()), Map.copyOf(System.getenv()));
    AgentLaunch launch = new AgentLaunch(Files.createTempDirectory("work"), null, "", "", "", List.of(),
      System.getenv(), null);
    session.set(new ClaudeSession(command, launch, "", Map.of(), listener));
    try {
      session.get().initialize().get(20, TimeUnit.SECONDS);
      session.get().prompt("fail", List.of(), 1);
      assertEquals(new AgentEvent.PromptTaken(1, true), recorder.until(AgentEvent.PromptTaken.class).getLast());
      recorder.until(AgentEvent.TurnEnded.class);
      List<AgentEvent> after = recorder.until(AgentEvent.PromptTaken.class);
      assertEquals("a slash command is never echoed, so it is taken as it is sent",
        new AgentEvent.PromptTaken(2, true), after.getLast());
      assertTrue(session.get().steer("more", List.of(), 3).get(20, TimeUnit.SECONDS));
    } finally {
      session.get().stop();
    }
  }
}
