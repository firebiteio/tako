package io.firebite.tako.ide.context;

import java.util.List;
import org.jetbrains.annotations.Nullable;

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

  public static String compose(String text, List<ContextItem> items, @Nullable String activeFile) {
    if (text.startsWith("/") || (items.isEmpty() && activeFile == null)) {
      return text;
    }
    StringBuilder prompt = new StringBuilder(text).append("\n\n").append(CONTEXT_OPEN).append('\n');
    for (ContextItem item : items) {
      prompt.append(item.text()).append("\n\n");
    }
    if (activeFile != null) {
      prompt.append("File open in the editor: ").append(activeFile).append('\n');
    }
    return prompt.append(CONTEXT_CLOSE).toString();
  }
}
