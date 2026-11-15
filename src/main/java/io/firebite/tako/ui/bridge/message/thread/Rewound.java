package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;
import org.jetbrains.annotations.Nullable;

/**
 * A rewind ended.
 *
 * @param rewound whether the agent forgot the prompt, so it goes back into the composer
 * @param error   what went wrong, or null
 */
public record Rewound(
  boolean rewound,
  @Nullable String error
) implements ThreadMessage {
}
