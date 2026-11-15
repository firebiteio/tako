package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;
import java.util.List;

/** The agents a thread can run, the default first. */
public record Agents(
  List<AgentOption> items
) implements WorkspaceMessage {
}
