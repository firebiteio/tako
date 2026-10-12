package io.firebite.tako.agent.claude;

import io.firebite.tako.agent.AgentEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jetbrains.annotations.Nullable;

/**
 * Tells which prompts wait for the running turn and when the CLI takes them. The CLI echoes a prompt when it takes it:
 * into the running turn after a tool call, else as the next turn. Local slash commands are never echoed.
 */
final class PromptEchoes {
  private final Deque<Sent> unechoed = new ArrayDeque<>();
  /** The CLI works on a prompt or has one waiting, so the next prompt waits. */
  private boolean isBusy;
  /** The CLI took a prompt since the last result, so one it takes now joins that turn. */
  private boolean isInTurn;

  /** True if the prompt waits for the running turn. */
  synchronized boolean sent(String text, int key) {
    boolean waits = this.isBusy;
    this.isBusy = true;
    this.unechoed.add(new Sent(text.isBlank() ? "" : text, key, waits));
    return waits;
  }

  /** The echoed prompt, if it waited. Forgets it and the prompts sent before it. */
  synchronized AgentEvent.@Nullable PromptTaken echoed(String text) {
    for (Sent sent : this.unechoed) {
      if (sent.text().equals(text)) {
        Sent skipped;
        do {
          skipped = this.unechoed.removeFirst();
        } while (skipped != sent);
        boolean startsTurn = !this.isInTurn;
        this.isInTurn = true;
        return sent.waits() ? new AgentEvent.PromptTaken(sent.key(), startsTurn) : null;
      }
    }
    return null;
  }

  /** The CLI starts the next turn right away if a prompt waits. */
  synchronized void turnEnded() {
    this.isInTurn = false;
    this.isBusy = false;
    for (Sent sent : this.unechoed) {
      this.isBusy |= sent.waits();
    }
  }

  private record Sent(
    String text,
    int key,
    boolean waits
  ) {
  }
}
