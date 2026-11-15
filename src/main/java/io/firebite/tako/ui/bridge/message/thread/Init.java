package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.ui.bridge.message.ThreadMessage;
import org.jetbrains.annotations.Nullable;

/** The thread's agent is ready, with what it offers the composer. */
public record Init(
  AgentInfo info,
  @Nullable String projectRoot
) implements ThreadMessage {
}
