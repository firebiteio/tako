package io.firebite.tako.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.acp.AcpBackend;
import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.Test;

public class AgentRegistryTest {

  @Test
  public void acpAgentsAreNamedCommandLines() {
    assertEquals(List.of(new AgentRegistry.AcpAgent("Gemini CLI", "gemini --acp"),
      new AgentRegistry.AcpAgent("OpenCode", "opencode acp")),
      AgentRegistry.acpAgents("Gemini CLI = gemini --acp\n\nno separator\nEmpty =\nOpenCode=opencode acp"));
  }

  @Test
  public void choicesNameEveryAgent() {
    assertEquals(List.of(new AgentRegistry.Choice("claude", "Claude Code"), new AgentRegistry.Choice("codex", "Codex"),
      new AgentRegistry.Choice("acp:Gemini CLI", "Gemini CLI")), AgentRegistry.choices("Gemini CLI = gemini --acp"));
  }

  @Test
  public void commandLinesKeepQuotedParts() {
    assertEquals(List.of("C:\\Program Files\\agent.exe", "--acp", "--name", "two words"),
      AcpBackend.split("\"C:\\Program Files\\agent.exe\" --acp  --name \"two words\""));
  }

  /** The page tells events apart by {@code type}, which a field of that name would overwrite. */
  @Test
  public void noEventHasATypeField() {
    for (Class<?> event : AgentEvent.class.getPermittedSubclasses()) {
      for (RecordComponent component : event.getRecordComponents()) {
        assertTrue(event.getSimpleName(), !component.getName().equals("type"));
      }
    }
  }
}
