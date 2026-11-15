package io.firebite.tako.chat.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;

/** The part of the chat that shows one thread. {@link #post} is safe from any thread. */
public interface ChatView {
  void post(ThreadMessage message);
}
