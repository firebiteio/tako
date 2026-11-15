package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.ui.bridge.message.ThreadMessage;
import java.util.List;

/** The thread's past, replayed when the thread opens. */
public record Replay(
  List<AgentEvent> events
) implements ThreadMessage {
}
