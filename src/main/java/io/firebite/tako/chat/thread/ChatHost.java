package io.firebite.tako.chat.thread;

import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** What a thread tells the service it lives in. Called on the EDT. */
public interface ChatHost {
  void setTitle(String title);

  /** The agent waits for the person, e.g. on a permission prompt. */
  void requestAttention(String reason);

  /**
   * The agent ended its turn or waits for the person, so the thread is unread unless shown.
   *
   * @param asks whether it waits for an answer, which ends a snooze early
   */
  void needsLook(boolean asks);

  /** The thread's record changed and must be saved, e.g. its session id, model, mode or effort. */
  void recordChanged();

  /** The person sent a prompt. */
  void activity();

  /**
   * A prompt is about to go out, as typed. Comes before the agent can answer it, unlike {@link #activity}, so before
   * any {@link #limitReached} of its turn.
   */
  void prompted(String typed);

  /**
   * The turn stopped on a usage or rate limit.
   *
   * @param label    the limit's window, e.g. {@code 5h}, when the agent named it
   * @param resetsAt   when it resets, in epoch millis, when the agent said
   * @param namesReset it only names the reset of the limit the last turn reported without one
   */
  void limitReached(@Nullable String label, @Nullable Long resetsAt, boolean namesReset);

  /** The agent's {@link ChatController.State} changed. */
  void stateChanged();

  /** The workspace's other project folders, which the thread may use too ({@code --add-dir}). */
  List<String> additionalDirectories();

  /** Where the thread's agent reaches Tako's tools over MCP, or null without them. */
  @Nullable
  String toolsUrl();

  /**
   * The title of another open thread whose agent works or waits for an answer in the repository with the top folder
   * {@code topLevel}, or in a folder holding it, else null. Runs git, so call off the EDT.
   */
  @Nullable
  String busyThreadIn(String topLevel);

  /** Where the thread keeps what its earlier agents did. Unlike the others, may be called from any thread. */
  Path pastFile();
}
