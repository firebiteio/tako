package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.ContextUsage;
import io.firebite.tako.ui.bridge.message.ThreadMessage;

public record ChatContextUsage(
  ContextUsage data
) implements ThreadMessage {
}
