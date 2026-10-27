package io.firebite.tako.chat.thread;

import io.firebite.tako.agent.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** The prompt that asks an agent to name a thread, and the title read from its answer. */
public final class ThreadTitles {
  private static final int RECENT_MESSAGES = 6;
  private static final int MESSAGE_LENGTH = 800;
  private static final String LABEL = "title:";

  private ThreadTitles() {
  }

  /** The conversation's first request and its last few messages, with each message cut short. */
  public static String prompt(List<Message> conversation) {
    int first = 0;
    while (first < conversation.size() && !conversation.get(first).isUser()) {
      first++;
    }
    StringBuilder prompt = new StringBuilder("Write a title of at most six words for this conversation between a "
      + "developer and a coding agent. Answer with the title only, without quotes.\n");
    if (first < conversation.size()) {
      prompt.append("\nFirst request:\n").append(cut(conversation.get(first).text())).append('\n');
    }
    int recent = Math.max(first + 1, conversation.size() - RECENT_MESSAGES);
    if (recent < conversation.size()) {
      prompt.append("\nLatest messages:\n");
      for (Message message : conversation.subList(recent, conversation.size())) {
        prompt.append(message.isUser() ? "Developer: " : "Agent: ").append(cut(message.text())).append('\n');
      }
    }
    return prompt.toString();
  }

  /** The answer's first line without a label, quotes, markdown or periods around it, cut to a title. Null if empty. */
  public static @Nullable String title(String answer) {
    for (String line : answer.split("\\R")) {
      String title = line.strip();
      if (title.toLowerCase(Locale.ROOT).startsWith(LABEL)) {
        title = title.substring(LABEL.length());
      }
      title = strip(title, "\"'`*#_. ");
      if (!title.isBlank()) {
        return ChatController.titleOf(title);
      }
    }
    return null;
  }

  /** The prompts and the agent's own answers, without what its subagents said. */
  public static List<Message> fromEvents(List<AgentEvent> events) {
    List<Message> conversation = new ArrayList<>();
    for (AgentEvent event : events) {
      if (event instanceof AgentEvent.Prompt prompt) {
        conversation.add(new Message(true, prompt.text()));
      } else if (event instanceof AgentEvent.Text text && text.parentId() == null) {
        conversation.add(new Message(false, text.text()));
      }
    }
    return conversation;
  }

  private static String cut(String text) {
    String trimmed = text.strip();
    return trimmed.length() <= MESSAGE_LENGTH ? trimmed : trimmed.substring(0, MESSAGE_LENGTH - 1) + "…";
  }

  private static String strip(String text, String characters) {
    int start = 0;
    int end = text.length();
    while (start < end && characters.indexOf(text.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && characters.indexOf(text.charAt(end - 1)) >= 0) {
      end--;
    }
    return text.substring(start, end);
  }

  /** One prompt or answer of a conversation. */
  public record Message(
    boolean isUser,
    String text
  ) {
  }
}
