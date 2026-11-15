package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ui.bridge.message.ThreadMessage;
import java.util.List;

/** Sends a prompt as if the person typed it, e.g. the one an agent gave the thread it created. */
public record Submit(
  String text,
  List<ContextItem> context,
  List<ImageAttachment> images
) implements ThreadMessage {
}
