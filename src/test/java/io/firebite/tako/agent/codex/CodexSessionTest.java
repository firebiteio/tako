package io.firebite.tako.agent.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.AgentDefaults;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.PermissionAnswer;
import io.firebite.tako.agent.RecordingListener;
import io.firebite.tako.agent.ToolKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CodexSessionTest {

  @Test
  public void turnStreamsTextAndAsksBeforeACommand() throws Exception {
    Path codex = FakeExecutable.write("codex", FakeCodex.class);
    RecordingListener listener = new RecordingListener();
    AgentLaunch launch = new AgentLaunch(Files.createTempDirectory("work"), null, "", "", "", List.of(),
      System.getenv(), null);
    AgentSession session = new CodexBackend(codex.toString(), AgentDefaults.NONE, Map.of()).start(launch, listener);
    try {
      AgentInfo info = session.initialize().get(20, TimeUnit.SECONDS);
      assertEquals("Signed in with ChatGPT (plus)", info.authDescription());
      assertEquals(List.of("low", "high"), info.models().getFirst().efforts());
      assertEquals("auto", info.mode());
      assertEquals("low", info.effort());
      AgentEvent.SessionStarted started = (AgentEvent.SessionStarted) listener.until(AgentEvent.SessionStarted.class)
        .getLast();
      assertEquals("019a0000-0000-7000-8000-000000000001", started.sessionId());

      session.prompt("list files", List.of(), 1);
      List<AgentEvent> beforeApproval = listener.until(AgentEvent.ToolStarted.class);
      assertTrue(beforeApproval.contains(new AgentEvent.TextDelta("m1", "Hel")));
      assertTrue(beforeApproval.contains(new AgentEvent.Text("m1", "Hello", null)));
      AgentEvent.ToolStarted command = (AgentEvent.ToolStarted) beforeApproval.getLast();
      assertEquals(ToolKind.COMMAND, command.kind());
      assertEquals("ls", command.input().get("command").getAsString());

      RecordingListener.Permission permission = listener.nextPermission();
      assertEquals("List files", permission.prompt().description());
      session.answer(permission.requestId(), new PermissionAnswer(true, null, false, Map.of(), 0));

      List<AgentEvent> rest = listener.until(AgentEvent.TurnEnded.class);
      assertTrue(rest.contains(new AgentEvent.ToolFinished("c1", "acceptForSession", false, null)));
      AgentEvent.Usage usage = (AgentEvent.Usage) rest.stream().filter(AgentEvent.Usage.class::isInstance)
        .findFirst().orElseThrow();
      assertEquals(Long.valueOf(400000), usage.contextWindow());
      assertEquals(new AgentEvent.TurnEnded(1500L, null, null), rest.getLast());
    } finally {
      session.stop();
    }
  }
}
