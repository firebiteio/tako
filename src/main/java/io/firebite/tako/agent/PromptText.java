package io.firebite.tako.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A prompt's text compared the way the chat shows it and the agent's session keeps it. */
public final class PromptText {
  private static final Pattern COMMAND = Pattern.compile("/[A-Za-z0-9:_-]+(?= |$)");

  private PromptText() {
  }

  /**
   * The text stripped, with runs of whitespace as one space. A slash command keeps only its name, since sessions keep
   * only that; a text that starts with a path stays whole.
   */
  public static String normalize(String text) {
    String normalized = text.strip().replaceAll("\\s+", " ");
    Matcher command = COMMAND.matcher(normalized);
    return command.lookingAt() ? command.group() : normalized;
  }

  public static boolean same(String one, String other) {
    return normalize(one).equals(normalize(other));
  }

  /** Whether the session's prompts start with the expected ones, compared one by one. */
  public static boolean startsWith(List<String> session, List<String> expected) {
    if (session.size() < expected.size()) {
      return false;
    }
    for (int i = 0; i < expected.size(); i++) {
      if (!same(session.get(i), expected.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** The SHA-256 of the normalized text, in hex. */
  public static String hash(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalize(text).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
