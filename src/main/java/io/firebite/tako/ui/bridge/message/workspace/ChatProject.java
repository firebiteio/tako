package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;
import org.jetbrains.annotations.Nullable;

public record ChatProject(
  @Nullable String projectRoot,
  String projectName
) implements WorkspaceMessage {
}
