package io.firebite.tako.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Prompts sent while a turn runs, for agents that take one turn at a time. Each waits for the one before. */
public final class PromptQueue {
  private final Deque<Queued> waiting = new ArrayDeque<>();
  private boolean isTurnRunning;

  /** True if the prompt starts a turn now, else it waits for {@link #next}. */
  public synchronized boolean offer(String text, List<ImageAttachment> images, int key) {
    if (this.isTurnRunning) {
      this.waiting.add(new Queued(text, images, key));
      return false;
    }
    this.isTurnRunning = true;
    return true;
  }

  /** The turn ended. Returns the prompt to start next, or null when no turn runs now. */
  public synchronized @Nullable Queued next() {
    Queued next = this.waiting.poll();
    this.isTurnRunning = next != null;
    return next;
  }

  public record Queued(
    String text,
    List<ImageAttachment> images,
    int key
  ) {
  }
}
