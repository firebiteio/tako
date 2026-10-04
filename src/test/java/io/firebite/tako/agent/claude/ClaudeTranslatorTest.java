package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonParser;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.SessionCost;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.agent.claude.protocol.FileChange;
import java.util.List;
import org.junit.Test;

public class ClaudeTranslatorTest {

  private static List<AgentEvent> translate(String line, boolean replaying) {
    return ClaudeTranslator.translate(new CliMessage(JsonParser.parseString(line).getAsJsonObject()), null, replaying);
  }

  @Test
  public void streamEventsBecomeBlocks() {
    assertEquals(List.of(new AgentEvent.MessageStarted("m1"), new AgentEvent.Usage(10, 5, 20000, 3000, 0, 3000, null,
      null)), translate(
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\",\"message\":{\"id\":\"m1\","
          + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":20000,"
          + "\"cache_creation_input_tokens\":3000,\"cache_creation\":{\"ephemeral_5m_input_tokens\":3000}}}}}",
        false));
    assertEquals(List.of(new AgentEvent.TextDelta("1", "Hi")), translate("{\"type\":\"stream_event\",\"event\":{"
      + "\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}}", false));
    assertEquals(List.of(), translate("{\"type\":\"stream_event\",\"parent_tool_use_id\":\"t\",\"event\":{"
      + "\"type\":\"content_block_stop\",\"index\":0}}", false));
  }

  @Test
  public void initCarriesTheScratchpad() {
    assertEquals(List.of(new AgentEvent.SessionStarted("s", "/p", "opus", "default", "/tmp/s/scratchpad")), translate(
      "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\",\"cwd\":\"/p\",\"model\":\"opus\","
        + "\"permissionMode\":\"default\",\"scratchpad_path\":\"/tmp/s/scratchpad\"}",
      false));
  }

  @Test
  public void toolCallsCarryTheirKindAndResultsTheirChange() {
    List<AgentEvent> started = translate("{\"type\":\"assistant\",\"message\":{\"id\":\"m\",\"content\":[{\"type\":"
      + "\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}", false);
    assertEquals(ToolKind.COMMAND, ((AgentEvent.ToolStarted) started.getFirst()).kind());

    List<AgentEvent> finished = ClaudeTranslator.translate(new CliMessage(JsonParser.parseString("{\"type\":\"user\","
      + "\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":[{\"type\":\"text\","
      + "\"text\":\"ok\"},{\"type\":\"image\"}]}]}}").getAsJsonObject()), new FileChange("a.txt", "x", 2, 1, false),
      false);
    assertEquals(List.of(new AgentEvent.ToolFinished("t2", "ok\n[image]", false, new AgentEvent.Change("a.txt", 2, 1,
      false))), finished);
  }

  @Test
  public void replayedPromptsDropTheIdeContextAndCommandMarkup() {
    assertEquals(List.of(new AgentEvent.Prompt("fix it", true, List.of())), translate("{\"type\":\"user\",\"message\":{"
      + "\"content\":[{\"type\":\"text\",\"text\":\"fix it\\n\\n<ide_context>\\nx\\n</ide_context>\"}]}}", true));
    assertEquals(List.of(new AgentEvent.Prompt("/compact", false, List.of())), translate("{\"type\":\"user\","
      + "\"message\":{\"content\":\"<command-name>/compact</command-name>\"}}", true));
    assertEquals(List.of(), translate("{\"type\":\"user\",\"message\":{\"content\":\"live prompt\"}}", false));
  }

  @Test
  public void resultsEndTheTurnWithTheirError() {
    assertEquals(new AgentEvent.TurnEnded(1000L, "Boom", null), translate("{\"type\":\"result\",\"subtype\":"
      + "\"error_during_execution\",\"is_error\":true,\"result\":\"Boom\",\"duration_ms\":1000}", false).getFirst());
    AgentEvent.TurnEnded ended = (AgentEvent.TurnEnded) translate("{\"type\":\"result\",\"subtype\":\"success\","
      + "\"total_cost_usd\":0.5,\"modelUsage\":{\"m\":{\"inputTokens\":3,\"outputTokens\":4}}}", false).getFirst();
    assertEquals(new SessionCost(0.5, 3, 4, 0, 0), ended.cost());
  }

  @Test
  public void taskEventsUseNeutralTypes() {
    AgentEvent.TaskUpdated task = (AgentEvent.TaskUpdated) translate("{\"type\":\"system\",\"subtype\":"
      + "\"task_updated\",\"task_id\":\"a1\",\"task_type\":\"local_bash\",\"status\":\"running\",\"patch\":{"
      + "\"status\":\"completed\",\"end_time\":99}}", false).getFirst();
    assertEquals("shell", task.taskType());
    assertEquals("completed", task.status());
    assertEquals(Long.valueOf(99), task.endedAt());
  }
}
