package io.firebite.tako.mcp;

import com.google.gson.JsonObject;
import java.util.List;

/** Tools one MCP client may call. Calls arrive on the server's threads, never the EDT. */
public interface McpTools {

  List<Tool> tools();

  /**
   * @return the text the agent reads
   * @throws McpToolException when the call fails in a way the agent should hear about, e.g. for an unknown name
   */
  String call(String name, JsonObject arguments) throws McpToolException;

  /** @param inputSchema the JSON schema of the arguments */
  record Tool(
    String name,
    String description,
    JsonObject inputSchema
  ) {
  }
}
