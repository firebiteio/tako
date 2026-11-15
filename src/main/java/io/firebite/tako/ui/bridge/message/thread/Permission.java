package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.ui.bridge.message.ThreadMessage;

/** The agent waits for the person. */
public record Permission(
  String requestId,
  PermissionPrompt prompt
) implements ThreadMessage {
}
