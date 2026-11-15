package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ui.bridge.message.WorkspaceMessage;

/**
 * Shows the side chat in its main thread's pane.
 *
 * @param started the side chat was just made, so the main thread may start another
 */
public record ShowSideChat(
  String thread,
  boolean started
) implements WorkspaceMessage {
}
