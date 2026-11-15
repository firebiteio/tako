package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;

/** Something the person should know that does not fail the turn. */
public record Notice(
  String text
) implements ThreadMessage {
}
