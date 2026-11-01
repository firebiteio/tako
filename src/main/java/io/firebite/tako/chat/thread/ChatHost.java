package io.firebite.tako.chat.thread;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/** What a thread tells the service it lives in. Called on the EDT. */
public interface ChatHost {
  void setTitle(String title);

  /** The agent waits for the person, e.g. on a permission prompt. */
  void requestAttention(String reason);

  /** The thread's record changed and must be saved, e.g. its session id, model, mode or effort. */
  void recordChanged();

  /** The person sent a prompt. */
  void activity();

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
}
