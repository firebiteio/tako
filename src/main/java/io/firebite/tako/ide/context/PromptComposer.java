package io.firebite.tako.ide.context;

import java.util.List;

/** Joins the typed prompt with the attached IDE context. */
public final class PromptComposer {
  /** Opens the block of IDE context appended to a prompt. Transcripts show the prompt without it. */
  private static final String CONTEXT_OPEN = "<ide_context>";
  private static final String CONTEXT_CLOSE = "</ide_context>";

  private PromptComposer() {
  }

  /** A sent prompt as it was typed, without the IDE context {@link #compose} added. */
  public static String typedPart(String prompt) {
    int context = prompt.indexOf(CONTEXT_OPEN);
    return context >= 0 ? prompt.substring(0, context).trim() : prompt;
  }

  public static boolean hasContext(String prompt) {
    return prompt.contains(CONTEXT_OPEN);
  }

  public static String compose(String text, List<ContextItem> items) {
    if (text.startsWith("/") || items.isEmpty()) {
      return text;
    }
    StringBuilder prompt = new StringBuilder(text).append("\n\n").append(CONTEXT_OPEN).append('\n');
    for (ContextItem item : items) {
      prompt.append(item.text()).append("\n\n");
    }
    return prompt.append(CONTEXT_CLOSE).toString();
  }

  /**
   * The prompt with paths in the folder {@code from} moved to {@code to}, so a run in a worktree reads its own copies
   * of the files the IDE named. Paths use {@code /}, as the IDE writes them. A folder that only starts with the same
   * name, such as {@code repo-tako} for {@code repo}, stays.
   */
  public static String rebase(String prompt, String from, String to) {
    if (from.isEmpty()) {
      return prompt;
    }
    StringBuilder rebased = new StringBuilder();
    int start = 0;
    int match = prompt.indexOf(from);
    while (match >= 0) {
      int end = match + from.length();
      boolean isWhole = end == prompt.length() || !isPathChar(prompt.charAt(end));
      rebased.append(prompt, start, match).append(isWhole ? to : from);
      start = end;
      match = prompt.indexOf(from, start);
    }
    return rebased.append(prompt.substring(start)).toString();
  }

  private static boolean isPathChar(char c) {
    return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
  }
}
