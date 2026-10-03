package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.firebite.tako.agent.ContextUsage;
import org.junit.Test;

public class ClaudeContextUsageTest {

  @Test
  public void keepsCategoriesAndAggregatesMcpToolsPerServer() {
    JsonObject usage = JsonParser.parseString("{\"categories\":[{\"name\":\"System prompt\",\"tokens\":6609,"
      + "\"color\":\"promptBorder\",\"kind\":\"used\"},{\"name\":\"Free space\",\"tokens\":173656,\"kind\":\"free\"}],"
      + "\"totalTokens\":26344,\"maxTokens\":200000,\"percentage\":13,\"gridRows\":[[{}]],"
      + "\"autoCompactThreshold\":167000,\"isAutoCompactEnabled\":true,"
      + "\"memoryFiles\":[{\"path\":\"C:/u/CLAUDE.md\",\"type\":\"User\",\"tokens\":86}],"
      + "\"mcpTools\":[{\"name\":\"a\",\"serverName\":\"laby\",\"tokens\":100,\"isLoaded\":false},"
      + "{\"name\":\"b\",\"serverName\":\"laby\",\"tokens\":50,\"isLoaded\":true}],"
      + "\"messageBreakdown\":{\"toolResultTokens\":900,\"userMessageTokens\":40,\"toolCallsByType\":[]}}")
      .getAsJsonObject();

    ContextUsage compact = ClaudeContextUsage.parse(usage);

    assertEquals(Long.valueOf(26344), compact.totalTokens());
    assertEquals(Long.valueOf(167000), compact.autoCompactThreshold());
    assertEquals("System prompt", compact.categories().get(0).name());
    ContextUsage.McpServer server = compact.mcpServers().get(0);
    assertEquals("laby", server.name());
    assertEquals(50, server.loadedTokens());
    assertEquals(100, server.deferredTokens());
    assertEquals(2, server.tools());
    assertEquals(900, compact.messages().toolResultTokens());
  }
}
