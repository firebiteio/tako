package io.firebite.tako.ui.bridge.message;

/** @param start the agent failed to start, so the error goes once it starts */
public record ChatError(
  String text,
  boolean start
) implements WorkspaceMessage, ThreadMessage {
  public ChatError(String text) {
    this(text, false);
  }
}
