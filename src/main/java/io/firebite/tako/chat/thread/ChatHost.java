package io.firebite.tako.chat.thread;

import java.util.List;

/** What a thread tells the service it lives in. Called on the EDT. */
public interface ChatHost {
  void setTitle(String title);

  /** The agent waits for the person, e.g. on a permission prompt. */
  void requestAttention(String reason);

  /** The thread's record changed and must be saved, e.g. its session id, model, mode or effort. */
  void recordChanged();

  /** The person sent a prompt. */
  void activity();

  /** The workspace's other project folders, which the thread may use too ({@code --add-dir}). */
  List<String> additionalDirectories();
}
