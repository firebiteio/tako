package io.firebite.tako.chat;

import io.firebite.tako.chat.thread.ChatController;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** The prompt that wakes a thread's agent once a thread it handed a prompt to with wake set is done. */
final class ThreadDonePrompt {
  static final int ANSWER_LENGTH = 4000;
  static final int ERROR_LENGTH = 1000;

  private ThreadDonePrompt() {
  }

  /**
   * @param answer the text its agent wrote since its last prompt
   * @param error  why its agent stopped or its last turn failed
   */
  static String compose(String threadId, String title, String agent, ChatController.State state, String answer,
    @Nullable String error) {
    StringBuilder text = new StringBuilder("Tako thread \"").append(title).append("\" (threadId ").append(threadId)
      .append(", agent ").append(agent).append(") is done with the prompt you handed it. State: ")
      .append(state.name().toLowerCase(Locale.ROOT)).append(".\n");
    if (error != null && !error.isBlank()) {
      text.append("Error: ").append(Texts.cut(error.strip(), ERROR_LENGTH)).append('\n');
    }
    String plain = answer.strip();
    if (plain.isEmpty()) {
      return text.append("It wrote no answer.").toString();
    }
    text.append("\nIts answer:\n").append(Texts.cut(plain, ANSWER_LENGTH));
    if (plain.length() > ANSWER_LENGTH) {
      text.append("\n\nThe answer is cut to ").append(ANSWER_LENGTH).append(" of its ").append(plain.length())
        .append(" characters. get_thread gives all of it.");
    }
    return text.toString();
  }
}
