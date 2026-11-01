package io.firebite.tako.ide.project;

/**
 * The end of a command's output as a terminal would show it: a lone carriage return goes back to the line's start, as
 * progress bars use it, and ANSI escape sequences such as colors are left out. Keeps the last {@link #LIMIT}
 * characters. Text may arrive in any pieces: an escape sequence or line break split between two is held back until the
 * rest arrives. Not thread safe.
 */
public final class SetupOutput {
  public static final int LIMIT = 20_000;
  /** Longer held back text is no escape sequence after all. */
  private static final int MAX_PENDING = 64;
  /** How much more than needed a cut drops to start at a line. */
  private static final int MAX_LINE_CUT = 1000;
  private static final char ESCAPE = '\u001b';
  private static final char BELL = '\u0007';
  private static final String CUT = "…\n";

  private final StringBuilder text = new StringBuilder();
  private String pending = "";
  /** Where the line being written starts in {@link #text}. */
  private int lineStart;
  private boolean isCut;

  public void append(String chunk) {
    String input = this.pending + chunk;
    this.pending = "";
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (c == ESCAPE) {
        int end = escapeEnd(input, i);
        if (end < 0) {
          this.holdBack(input.substring(i));
          break;
        }
        i = end;
        continue;
      }
      if (c == '\r') {
        if (i + 1 == input.length()) {
          this.pending = "\r";
          break;
        }
        if (input.charAt(i + 1) != '\n') {
          this.text.setLength(this.lineStart);
        }
      } else if (c == '\n') {
        this.text.append('\n');
        this.lineStart = this.text.length();
      } else {
        this.text.append(c);
      }
      i++;
    }
    this.cut();
  }

  /** The output ended: what is still held back is dropped. */
  public void finish() {
    this.pending = "";
  }

  public String text() {
    return this.isCut ? CUT + this.text : this.text.toString();
  }

  public boolean isEmpty() {
    return this.text.isEmpty();
  }

  /** An escape sequence that never ends shows as text, without its escape character. */
  private void holdBack(String rest) {
    if (rest.length() <= MAX_PENDING) {
      this.pending = rest;
      return;
    }
    this.pending = "";
    this.append(rest.substring(1));
  }

  /**
   * Where the escape sequence at {@code start} ends, exclusive: a CSI sequence like {@code ESC [ 3 1 m} at its final
   * byte, an OSC sequence like a window title at its bell or {@code ESC \}, any other after one character.
   *
   * @return -1 when the text ends first
   */
  private static int escapeEnd(String text, int start) {
    if (start + 1 >= text.length()) {
      return -1;
    }
    char kind = text.charAt(start + 1);
    if (kind == '[') {
      for (int i = start + 2; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c >= '@' && c <= '~') {
          return i + 1;
        }
      }
      return -1;
    }
    if (kind == ']') {
      for (int i = start + 2; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == BELL) {
          return i + 1;
        }
        if (c == ESCAPE && i + 1 < text.length() && text.charAt(i + 1) == '\\') {
          return i + 2;
        }
      }
      return -1;
    }
    if (kind == '(' || kind == ')') {
      return start + 2 < text.length() ? start + 3 : -1;
    }
    return start + 2;
  }

  /** Drops the oldest output beyond the limit, up to the next line break so no half line stays. */
  private void cut() {
    int excess = this.text.length() - LIMIT;
    if (excess <= 0) {
      return;
    }
    int newline = this.text.indexOf("\n", excess);
    boolean isNear = newline >= 0 && newline < this.lineStart && newline - excess < MAX_LINE_CUT;
    int end = isNear ? newline + 1 : excess;
    this.text.delete(0, end);
    this.lineStart = Math.max(0, this.lineStart - end);
    this.isCut = true;
  }
}
