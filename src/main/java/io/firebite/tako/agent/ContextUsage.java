package io.firebite.tako.agent;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/** What fills the agent's context window, as the context panel shows it. Null numbers were not reported. */
public record ContextUsage(
  @Nullable Long totalTokens,
  @Nullable Long maxTokens,
  @Nullable Long autoCompactThreshold,
  @Nullable Boolean isAutoCompactEnabled,
  List<Category> categories,
  List<MemoryFile> memoryFiles,
  List<McpServer> mcpServers,
  @Nullable Messages messages
) {
  /** @param kind {@code used}, {@code free} or {@code deferred} */
  public record Category(
    String name,
    long tokens,
    String kind
  ) {
  }

  public record MemoryFile(
    String path,
    @Nullable String type,
    long tokens
  ) {
  }

  /** One MCP server's tools, split into those loaded into the context and those that wait until a tool is used. */
  public record McpServer(
    String name,
    long loadedTokens,
    long deferredTokens,
    int tools
  ) {
  }

  public record Messages(
    long toolCallTokens,
    long toolResultTokens,
    long attachmentTokens,
    long assistantMessageTokens,
    long userMessageTokens,
    long redirectedContextTokens,
    long unattributedTokens
  ) {
  }
}
