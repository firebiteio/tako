package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ui.bridge.message.ThreadMessage;

public record ChatImage(
  ImageAttachment image
) implements ThreadMessage {
}
