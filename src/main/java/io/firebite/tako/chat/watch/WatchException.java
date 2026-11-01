package io.firebite.tako.chat.watch;

/** Why a watch could not start. The message is English, for the agent; the chat shows {@link #reason} translated. */
public final class WatchException extends Exception {
  private final Reason reason;
  private final String detail;

  /** @param detail the pull request's number or gh's error, which the chat's text names */
  public WatchException(Reason reason, String detail, String message) {
    super(message);
    this.reason = reason;
    this.detail = detail;
  }

  public Reason reason() {
    return this.reason;
  }

  public String detail() {
    return this.detail;
  }

  public enum Reason {
    NO_THREAD,
    ARCHIVED,
    NO_PULL_REQUEST,
    BAD_URL,
    NOT_OPEN,
    STARTING,
    MISSING,
    LOGGED_OUT,
    RATE_LIMITED,
    FAILED
  }
}
