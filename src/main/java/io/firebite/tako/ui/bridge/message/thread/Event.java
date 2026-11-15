package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.ui.bridge.message.ThreadMessage;

public record Event(
  AgentEvent event
) implements ThreadMessage {
}
