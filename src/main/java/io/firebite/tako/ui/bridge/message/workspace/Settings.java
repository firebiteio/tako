package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;

/**
 * What the settings let the chat offer.
 *
 * @param steers      a prompt sent while the agent works steers its turn by default, instead of queuing
 * @param agentPicked the person picked the agent of new threads, so empty threads stop offering the agents
 */
public record Settings(
  boolean parallelRuns,
  boolean steers,
  boolean agentPicked
) implements WorkspaceMessage {
}
