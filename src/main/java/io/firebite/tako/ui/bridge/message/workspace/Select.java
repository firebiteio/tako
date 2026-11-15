package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;

public record Select(
  String thread
) implements WorkspaceMessage {
}
