package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ui.bridge.message.ThreadMessage;
import org.jetbrains.annotations.Nullable;

/** @param prefill a prompt to propose when the composer is empty */
public record Context(
  ContextItem item,
  @Nullable String prefill
) implements ThreadMessage {
}
