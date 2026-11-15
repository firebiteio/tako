package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;

/**
 * The thread closed with prompts queued, which went to the prompt stash.
 *
 * @param count how many the stash kept
 */
public record QueueStashed(
  int count
) implements ThreadMessage {
}
