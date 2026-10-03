package io.firebite.tako.chat.thread;

import io.firebite.tako.ui.bridge.PageMessage;

/** The part of the chat page that shows one thread. {@link #post} is safe from any thread. */
public interface ChatView {
  void post(PageMessage.ThreadMessage message);
}
