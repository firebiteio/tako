package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;

public record ThreadUpdated(
  ThreadItem item
) implements WorkspaceMessage {
}
