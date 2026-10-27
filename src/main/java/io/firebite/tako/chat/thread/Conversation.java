package io.firebite.tako.chat.thread;

import io.firebite.tako.agent.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** A thread's prompts and the agent's answers as plain text, without tool calls or thinking, for another agent to read. */
public final class Conversation {
  private final List<Turn> turns = new ArrayList<>();

  public static Conversation of(List<AgentEvent> events) {
    Conversation conversation = new Conversation();
    conversation.reset(events);
    return conversation;
  }

  /** Starts over from a replayed session's events. */
  public synchronized void reset(List<AgentEvent> events) {
    this.turns.clear();
    for (AgentEvent event : events) {
      this.add(event);
    }
  }

  /** Keeps the prompts and the answer text of the main agent; subagents' text and everything else is left out. */
  public synchronized void add(AgentEvent event) {
    switch (event) {
      case AgentEvent.Prompt prompt -> this.prompt(prompt.text());
      case AgentEvent.Text text -> {
        if (text.parentId() == null && !text.text().isBlank()) {
          if (this.turns.isEmpty()) {
            this.turns.add(new Turn(null));
          }
          StringBuilder answer = this.turns.getLast().answer;
          answer.append(answer.isEmpty() ? "" : "\n\n").append(text.text().strip());
        }
      }
      default -> {
      }
    }
  }

  /** A prompt as typed, without the IDE context sent along. */
  public synchronized void prompt(String text) {
    this.turns.add(new Turn(text.strip()));
  }

  /**
   * The conversation, oldest turn first. When it is longer than {@code maxChars}, the oldest turns are left out and a
   * note says how many.
   */
  public synchronized String text(int maxChars) {
    List<String> kept = new ArrayList<>();
    int length = 0;
    for (int i = this.turns.size() - 1; i >= 0; i--) {
      String turn = this.turns.get(i).text();
      if (!kept.isEmpty() && length + turn.length() > maxChars) {
        break;
      }
      kept.add(turn.length() > maxChars ? turn.substring(0, maxChars) + " [cut]" : turn);
      length += turn.length();
    }
    StringBuilder text = new StringBuilder();
    int left = this.turns.size() - kept.size();
    if (left > 0) {
      text.append('[').append(left).append(left == 1 ? " earlier turn" : " earlier turns").append(" left out]\n\n");
    }
    for (int i = kept.size() - 1; i >= 0; i--) {
      text.append(kept.get(i)).append(i > 0 ? "\n\n" : "");
    }
    return text.toString();
  }

  private static final class Turn {
    private final @Nullable String prompt;
    private final StringBuilder answer = new StringBuilder();

    private Turn(@Nullable String prompt) {
      this.prompt = prompt;
    }

    private String text() {
      StringBuilder text = new StringBuilder();
      if (this.prompt != null) {
        text.append("Person: ").append(this.prompt);
      }
      if (!this.answer.isEmpty()) {
        text.append(text.isEmpty() ? "" : "\n\n").append("Agent: ").append(this.answer);
      }
      return text.toString();
    }
  }
}
