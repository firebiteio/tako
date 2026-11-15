package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;

public record Exit(
  int code,
  String stderr
) implements ThreadMessage {
}
