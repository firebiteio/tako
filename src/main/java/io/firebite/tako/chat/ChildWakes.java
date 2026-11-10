package io.firebite.tako.chat;

import io.firebite.tako.chat.thread.ChatController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Which threads to wake once a thread they handed a prompt to with wake set is done: idle or stopped. One wake per
 * handing thread, however many prompts it handed meanwhile, and none when a wait for the thread returned its result.
 * Kept in memory only, so the wakes still owed are lost when the IDE restarts.
 */
final class ChildWakes {
  /** Thread id to the ids of the threads owed a wake once it is done. Guarded by {@code this}. */
  private final Map<String, Set<String>> owed = new HashMap<>();
  /** Thread id to the ids of the threads waiting for it, once per running wait. Guarded by {@code this}. */
  private final Map<String, List<String>> waits = new HashMap<>();

  /** Whether a thread in this state is done with what it was handed. Null for a closed one. */
  static boolean isDone(ChatController.@Nullable State state) {
    return state == null || state == ChatController.State.IDLE || state == ChatController.State.STOPPED;
  }

  synchronized void handed(String parent, String child) {
    this.owed.computeIfAbsent(child, id -> new LinkedHashSet<>()).add(parent);
  }

  synchronized void waitStarted(String parent, String child) {
    this.waits.computeIfAbsent(child, id -> new ArrayList<>()).add(parent);
  }

  /** @param delivered the wait returned with the child {@link #isDone done}, so its result reached the parent */
  synchronized void waitEnded(String parent, String child, boolean delivered) {
    List<String> waiting = this.waits.get(child);
    if (waiting != null) {
      waiting.remove(parent);
      if (waiting.isEmpty()) {
        this.waits.remove(child);
      }
    }
    Set<String> parents = this.owed.get(child);
    if (delivered && parents != null) {
      parents.remove(parent);
      if (parents.isEmpty()) {
        this.owed.remove(child);
      }
    }
  }

  /**
   * The thread's state changed.
   *
   * @return the threads to wake now: those owed a wake once it is done, unless they wait for it at the moment
   */
  synchronized List<String> changed(String child, ChatController.State state) {
    Set<String> parents = isDone(state) ? this.owed.remove(child) : null;
    if (parents == null) {
      return List.of();
    }
    List<String> waiting = this.waits.getOrDefault(child, List.of());
    List<String> woken = new ArrayList<>();
    for (String parent : parents) {
      if (!waiting.contains(parent)) {
        woken.add(parent);
      }
    }
    return woken;
  }

  /** The thread closed, so nobody gets a wake for it. */
  synchronized void closed(String child) {
    this.owed.remove(child);
  }
}
