package io.firebite.tako.chat.thread;

import io.firebite.tako.chat.store.ThreadPast;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What a thread tells the agent it switched to: the conversation so far and where it stands, within a token budget.
 * Recent turns stay whole, older ones are shortened and the oldest left out, but the first request is kept.
 */
public final class Handoff {
  /** The budget T3 Code uses for its handoffs by default. */
  public static final int MAX_TOKENS = 16_000;
  /** Counting one token per three UTF-8 bytes over-counts English and is about right for CJK text. */
  private static final int BYTES_PER_TOKEN = 3;
  /** How much of a prompt or answer an older turn keeps. */
  static final int SHORT_CHARS = 600;
  private static final int TODOS_CHARS = 4000;
  private static final int PLAN_CHARS = 8000;
  private static final int FILES = 100;
  private static final int MIN_CHARS = 40;
  /** Room for the at most two notes on left out turns, which the turns' budget does not count, and a newline. */
  private static final int NOTES_BYTES = 80;
  private static final String CUT = " […] ";
  private static final String TURN_SEPARATOR = "\n\n";

  private Handoff() {
  }

  /** The handoff, starting with {@code <handoff>} and ending with {@code </handoff>}. */
  public static String text(Input input, Facts facts, int maxTokens) {
    StringBuilder text = new StringBuilder("<handoff>\n");
    text.append("This thread ran with ").append(facts.from()).append(" until now. You continue it in a new session. "
      + "The person's new request is the text before <ide_context>. Below is what happened before, for context only: "
      + "do not redo or answer earlier requests unless the new one asks. Images from earlier turns are not included.\n");
    if (facts.folder() != null) {
      text.append("\nFolder: ").append(facts.folder());
    }
    if (facts.branch() != null) {
      text.append("\nBranch: ").append(facts.branch());
      if (facts.worktree() != null) {
        text.append(" (worktree ").append(facts.worktree()).append(')');
      }
    }
    if (facts.folder() != null || facts.branch() != null) {
      text.append('\n');
    }
    if (input.todos() != null && !input.todos().isBlank()) {
      text.append("\nLatest todo list:\n").append(shorten(input.todos().strip(), TODOS_CHARS)).append('\n');
    }
    if (input.plan() != null && !input.plan().isBlank()) {
      text.append("\nLatest plan:\n").append(shorten(input.plan().strip(), PLAN_CHARS)).append('\n');
    }
    appendFiles(text, input.files());
    String heading = "\nConversation so far, oldest first:\n";
    String end = "</handoff>";
    int budget = maxTokens * BYTES_PER_TOKEN - bytes(text) - bytes(heading) - bytes(end) - NOTES_BYTES;
    List<String> turns = turns(input.turns(), budget);
    if (!turns.isEmpty()) {
      text.append(heading).append(String.join(TURN_SEPARATOR, turns)).append('\n');
    }
    return text.append(end).toString();
  }

  /** A conservative token count for {@code text}. */
  static int tokens(CharSequence text) {
    return (bytes(text) + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN;
  }

  private static void appendFiles(StringBuilder text, List<String> files) {
    if (files.isEmpty()) {
      return;
    }
    text.append("\nFiles changed in this thread:\n");
    int from = Math.max(0, files.size() - FILES);
    for (int i = from; i < files.size(); i++) {
      text.append("- ").append(files.get(i)).append('\n');
    }
    if (from > 0) {
      text.append("- and ").append(from).append(" more\n");
    }
  }

  /** The turns that fit in {@code budget} bytes, oldest first, with a note where turns were left out. */
  static List<String> turns(List<ThreadPast.Turn> turns, int budget) {
    int count = turns.size();
    if (count == 0 || budget <= 0) {
      return count == 0 ? List.of() : List.of(leftOut(count));
    }
    String[] kept = new String[count];
    int lowest = count > 1 ? 1 : 0;
    String firstShort = count > 1 ? render(turns.getFirst(), SHORT_CHARS) : null;
    int reserved = firstShort != null && cost(firstShort) <= budget ? cost(firstShort) : 0;
    int free = budget - reserved;
    int used = 0;
    int index = count - 1;
    while (index >= lowest) {
      String whole = render(turns.get(index), Integer.MAX_VALUE);
      int cost = cost(whole);
      if (used + cost > free) {
        break;
      }
      kept[index] = whole;
      used += cost;
      index--;
    }
    if (index == count - 1) {
      String fitted = fit(turns.get(index), free - used - bytes(TURN_SEPARATOR));
      if (fitted != null) {
        kept[index] = fitted;
        used += cost(fitted);
      }
      index--;
    }
    while (index >= lowest) {
      String shortened = render(turns.get(index), SHORT_CHARS);
      int cost = cost(shortened);
      if (used + cost > free) {
        break;
      }
      kept[index] = shortened;
      used += cost;
      index--;
    }
    if (firstShort != null) {
      String whole = render(turns.getFirst(), Integer.MAX_VALUE);
      if (used + cost(whole) <= budget) {
        kept[0] = whole;
      } else if (reserved > 0) {
        kept[0] = firstShort;
      }
    }
    List<String> blocks = new ArrayList<>();
    int gap = 0;
    for (String block : kept) {
      if (block == null) {
        gap++;
        continue;
      }
      if (gap > 0) {
        blocks.add(leftOut(gap));
        gap = 0;
      }
      blocks.add(block);
    }
    if (gap > 0) {
      blocks.add(leftOut(gap));
    }
    return blocks;
  }

  /**
   * The turn, which does not fit whole, shortened until it fits in {@code bytes}, or null when not even a short version
   * does. Starts near the bytes left, since a char takes at least one byte.
   */
  private static @Nullable String fit(ThreadPast.Turn turn, int bytes) {
    int longest = Math.max(turn.prompt() != null ? turn.prompt().length() : 0, turn.answer().length());
    for (int limit = Math.min(longest * 3 / 4, bytes); limit >= MIN_CHARS; limit = limit * 3 / 4) {
      String text = render(turn, limit);
      if (bytes(text) <= bytes) {
        return text;
      }
    }
    return null;
  }

  private static String render(ThreadPast.Turn turn, int maxChars) {
    StringBuilder text = new StringBuilder();
    if (turn.prompt() != null) {
      text.append("Person: ").append(shorten(turn.prompt(), maxChars));
    }
    if (!turn.answer().isBlank()) {
      text.append(text.isEmpty() ? "" : "\n").append("Agent: ").append(shorten(turn.answer(), maxChars));
    }
    return text.toString();
  }

  private static String leftOut(int turns) {
    return "[" + turns + (turns == 1 ? " turn" : " turns") + " left out]";
  }

  /** The text's start and end with {@link #CUT} between, in about {@code maxChars}. Keeps surrogate pairs whole. */
  static String shorten(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    int keep = Math.max(0, maxChars - CUT.length());
    int head = keep / 2;
    int tailStart = text.length() - (keep - head);
    if (head > 0 && Character.isHighSurrogate(text.charAt(head - 1))) {
      head--;
    }
    if (tailStart < text.length() && Character.isLowSurrogate(text.charAt(tailStart))) {
      tailStart++;
    }
    return text.substring(0, head) + CUT + text.substring(tailStart);
  }

  private static int cost(String block) {
    return bytes(block) + bytes(TURN_SEPARATOR);
  }

  private static int bytes(CharSequence text) {
    return text.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * What the thread holds, from {@link Conversation#handoffInput}.
   *
   * @param files paths changed in the thread, oldest first
   */
  public record Input(
    List<ThreadPast.Turn> turns,
    List<String> files,
    @Nullable String todos,
    @Nullable String plan
  ) {
  }

  /**
   * Where the thread stands.
   *
   * @param from the agent the thread switched away from, by name
   */
  public record Facts(
    String from,
    @Nullable String folder,
    @Nullable String branch,
    @Nullable String worktree
  ) {
  }
}
