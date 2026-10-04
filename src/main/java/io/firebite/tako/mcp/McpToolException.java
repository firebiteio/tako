package io.firebite.tako.mcp;

/** A tool call failed. The message goes to the agent as the call's error result. */
public final class McpToolException extends Exception {

  public McpToolException(String message) {
    super(message);
  }
}
