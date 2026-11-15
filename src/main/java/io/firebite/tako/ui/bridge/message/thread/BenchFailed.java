package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;

/**
 * No run of a {@link io.firebite.tako.chat.BenchRequest} started; gives its prompt back to its source thread.
 *
 * @param error why, empty when the person cancelled
 */
public record BenchFailed(
  Submit prompt,
  String error
) implements ThreadMessage {
}
