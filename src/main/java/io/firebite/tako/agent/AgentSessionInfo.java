package io.firebite.tako.agent;

/** A past session of an agent that a thread can resume. */
public record AgentSessionInfo(
  String id,
  String title,
  long lastModified
) {
  private static final int TITLE_LENGTH = 80;

  /** A title from a session's first prompt or name, on one line and cut to fit the history menu. */
  public static String titleOf(String text) {
    String line = text.replace('\n', ' ').trim();
    return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH - 1) + "…";
  }
}
