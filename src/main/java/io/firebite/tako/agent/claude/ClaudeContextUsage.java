package io.firebite.tako.agent.claude;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.ContextUsage;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the CLI's {@code get_context_usage} answer. */
final class ClaudeContextUsage {
  private ClaudeContextUsage() {
  }

  static ContextUsage parse(JsonObject usage) {
    List<ContextUsage.Category> categories = new ArrayList<>();
    for (JsonObject category : Json.objects(usage, "categories")) {
      categories
        .add(new ContextUsage.Category(String.valueOf(Json.string(category, "name")), Json.number(category, "tokens"),
          String.valueOf(Json.string(category, "kind"))));
    }
    List<ContextUsage.MemoryFile> memoryFiles = new ArrayList<>();
    for (JsonObject file : Json.objects(usage, "memoryFiles")) {
      memoryFiles.add(new ContextUsage.MemoryFile(String.valueOf(Json.string(file, "path")), Json.string(file, "type"),
        Json.number(file, "tokens")));
    }
    JsonObject messages = Json.object(usage, "messageBreakdown");
    return new ContextUsage(
      Json.optionalNumber(usage, "totalTokens"),
      Json.optionalNumber(usage, "maxTokens"),
      Json.optionalNumber(usage, "autoCompactThreshold"),
      usage.has("isAutoCompactEnabled") ? Json.bool(usage, "isAutoCompactEnabled") : null,
      categories, memoryFiles, mcpServers(usage),
      messages == null
        ? null
        : new ContextUsage.Messages(
          Json.number(messages, "toolCallTokens"), Json.number(messages, "toolResultTokens"),
          Json.number(messages, "attachmentTokens"), Json.number(messages, "assistantMessageTokens"),
          Json.number(messages, "userMessageTokens"), Json.number(messages, "redirectedContextTokens"),
          Json.number(messages, "unattributedTokens")));
  }

  private static List<ContextUsage.McpServer> mcpServers(JsonObject usage) {
    Map<String, long[]> totals = new LinkedHashMap<>();
    for (JsonObject tool : Json.objects(usage, "mcpTools")) {
      long[] server = totals.computeIfAbsent(String.valueOf(Json.string(tool, "serverName")), name -> new long[3]);
      server[Json.bool(tool, "isLoaded") ? 0 : 1] += Json.number(tool, "tokens");
      server[2]++;
    }
    List<ContextUsage.McpServer> servers = new ArrayList<>();
    for (Map.Entry<String, long[]> server : totals.entrySet()) {
      long[] counts = server.getValue();
      servers.add(new ContextUsage.McpServer(server.getKey(), counts[0], counts[1], (int) counts[2]));
    }
    return servers;
  }
}
