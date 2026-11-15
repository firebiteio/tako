package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;

/**
 * What the settings let the chat offer.
 *
 * @param steers a prompt sent while the agent works steers its turn by default, instead of queuing
 */
public record Settings(
  boolean parallelRuns,
  boolean steers
) implements WorkspaceMessage {
}
