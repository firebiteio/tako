package io.firebite.tako.chat.thread;

import io.firebite.tako.agent.AgentEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** A thread's prompts and the agent's answers as plain text, without tool calls or thinking, for another agent to read. */
public final class Conversation {
  private final List<Turn> turns = new ArrayList<>();
  /** Prompts sent that the agent has not taken yet, by their key. */
  private final Map<Integer, String> sent = new HashMap<>();

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

  /** A prompt as typed, without the IDE context sent along. It counts once the agent takes it, see {@link #take}. */
  public synchronized void send(int key, String text) {
    this.sent.put(key, text);
  }

  /** The agent took the prompt sent with {@code key}, so the answer text that follows belongs to it. */
  public synchronized void take(int key) {
    String text = this.sent.remove(key);
    if (text != null) {
      this.prompt(text);
    }
  }

  /** Slash commands are left out; a prompt of only images shows as such. */
  private void prompt(String text) {
    String prompt = text.strip();
    if (!prompt.startsWith("/")) {
      this.turns.add(new Turn(prompt.isEmpty() ? "[images]" : prompt));
    }
  }

  /**
   * The conversation, oldest turn first. When it is longer than {@code maxChars}, the oldest turns are left out and a
   * note says how many. A newest turn longer than that keeps its end.
   */
  public synchronized String text(int maxChars) {
    List<String> kept = new ArrayList<>();
    int length = 0;
    for (int i = this.turns.size() - 1; i >= 0; i--) {
      String turn = this.turns.get(i).text();
      if (!kept.isEmpty() && length + turn.length() > maxChars) {
        break;
      }
      kept.add(turn.length() > maxChars ? "[cut] " + turn.substring(turn.length() - maxChars) : turn);
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
