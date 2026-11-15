package io.firebite.tako.chat;

/** Shortens texts for previews, titles and prompts. */
public final class Texts {
  private Texts() {
  }

  /** The text, or its start and an ellipsis in {@code length} characters. Does not split a surrogate pair. */
  public static String cut(String text, int length) {
    if (text.length() <= length) {
      return text;
    }
    int end = length - 1;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end) + "…";
  }
}
