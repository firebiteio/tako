package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.chat.thread.QueuedPrompts;

public record QueueItem(
  int turn,
  QueuedPrompts.Status status
) {
}
