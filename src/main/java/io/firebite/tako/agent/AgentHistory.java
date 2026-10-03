package io.firebite.tako.agent;

import java.util.List;

/** Where a resumed session's past comes from. */
public sealed interface AgentHistory {

  /** Read from the agent's records before it starts. */
  record Loaded(
    List<AgentEvent> events
  ) implements AgentHistory {
  }

  /** The session is gone, so the thread starts a new one. */
  record Missing() implements AgentHistory {
  }

  /** The agent sends it through {@link AgentListener#onHistory} after it started. */
  record FromSession() implements AgentHistory {
  }
}
