package io.firebite.tako.agent;

/** An agent could not start. The message tells the person what to do. */
public final class AgentException extends Exception {
  public AgentException(String message) {
    super(message);
  }

  public AgentException(String message, Throwable cause) {
    super(message, cause);
  }

  /** What to tell the person. That is the first agent message in the chain of causes, else the innermost cause's. */
  public static String messageOf(Throwable error) {
    Throwable cause = error;
    while (true) {
      if (cause instanceof AgentException) {
        return String.valueOf(cause.getMessage());
      }
      if (cause.getCause() == null || cause.getCause() == cause) {
        return String.valueOf(cause.getMessage());
      }
      cause = cause.getCause();
    }
  }
}
