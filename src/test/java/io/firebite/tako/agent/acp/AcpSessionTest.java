package io.firebite.tako.agent.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.EditPreview;
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

public class AcpSessionTest {

  private static AgentSession start(RecordingListener listener, String resume) throws Exception {
    Path agent = FakeExecutable.write("fake-acp", FakeAcpAgent.class);
    AgentLaunch launch = new AgentLaunch(Files.createTempDirectory("work"), resume, "", "", "", List.of(),
      System.getenv(), null);
    return new AcpBackend("Fake", List.of(agent.toString()), Map.of()).start(launch, listener);
  }

  @Test
  public void promptStreamsTextAndReportsAnApprovedEditAsAChange() throws Exception {
    RecordingListener listener = new RecordingListener();
    AgentSession session = start(listener, null);
    try {
      AgentInfo info = session.initialize().get(20, TimeUnit.SECONDS);
      assertEquals("acp:Fake", info.agent());
      assertEquals("fast", info.model());
      assertEquals(List.of("fast", "smart"), info.models().stream().map(AgentInfo.Model::value).toList());
      assertEquals("ask", info.mode());
      assertEquals("Edits freely", info.modes().get(1).description());
      assertTrue(info.capabilities().images());
      assertFalse(session.takesTools());

      session.prompt("change a.txt", List.of(), 1);
      List<AgentEvent> streamed = listener.until(AgentEvent.ToolStarted.class);
      assertTrue(streamed.contains(new AgentEvent.TextDelta("message-1", "Sure, ")));
      assertTrue(streamed.contains(new AgentEvent.Text("message-1", "Sure, editing.", null)));
      assertTrue(streamed.contains(new AgentEvent.BlockEnded("thought-0")));
      AgentEvent.ToolStarted edit = (AgentEvent.ToolStarted) streamed.getLast();
      assertEquals(ToolKind.EDIT, edit.kind());
      assertEquals("a.txt", edit.input().get("file_path").getAsString());

      RecordingListener.Permission permission = listener.nextPermission();
      assertEquals("Always allow edits", permission.prompt().suggestions().getFirst().label());
      assertTrue(permission.prompt().canDiff());
      assertEquals(new EditPreview("a.txt", "one\ntwo", "one\nthree"), session.proposedEdit(permission.requestId()));
      session.answer(permission.requestId(), new PermissionAnswer(true, null, false, Map.of(), 0));

      List<AgentEvent> rest = listener.until(AgentEvent.TurnEnded.class);
      assertTrue(rest.contains(new AgentEvent.ToolFinished("t1", "always", false,
        new AgentEvent.Change("a.txt", 1, 1, false))));
      assertTrue(rest.contains(new AgentEvent.Usage(5000, 0, 0, 0, 0, 0, null, 200000L)));
      assertEquals(null, ((AgentEvent.TurnEnded) rest.getLast()).error());
      assertEquals(List.of("a.txt=one\ntwo"), listener.editedFiles);
    } finally {
      session.stop();
    }
  }

  @Test
  public void loadingASessionReplaysItAsHistory() throws Exception {
    RecordingListener listener = new RecordingListener();
    AgentSession session = start(listener, "s0");
    try {
      session.initialize().get(20, TimeUnit.SECONDS);

      List<AgentEvent> history = listener.histories.getFirst();
      assertEquals(new AgentEvent.Prompt("earlier question", false, List.of()), history.getFirst());
      assertTrue(history.contains(new AgentEvent.Text("message-0", "earlier answer", null)));
      AgentEvent.SessionStarted started = (AgentEvent.SessionStarted) listener.until(AgentEvent.SessionStarted.class)
        .getLast();
      assertEquals("s0", started.sessionId());
    } finally {
      session.stop();
    }
  }
}
