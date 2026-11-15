package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;
import java.util.List;

public record Projects(
  List<ProjectItem> items
) implements WorkspaceMessage {
}
