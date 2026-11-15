package io.firebite.tako.ui.bridge.message.thread;

import io.firebite.tako.ui.bridge.message.ThreadMessage;
import java.util.List;

/**
 * The prompts the agent has not taken that the chat shows as queued, in order.
 *
 * @param held    they wait until a prompt is sent, after a stop, an error or the agent's end
 * @param running a turn runs or a prompt is on its way to the agent
 */
public record Queue(
  List<QueueItem> items,
  boolean held,
  boolean running
) implements ThreadMessage {
}
