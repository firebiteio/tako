package io.firebite.tako.chat.thread;

import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.context.PromptComposer;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/** Which prompt carries the handoff to the agent the thread switched to, and whether it arrived. */
final class HandoffTracker {
  /** {@link #turn} while no prompt carries the handoff. */
  private static final int NONE = Integer.MIN_VALUE;

  private final ThreadStore.Record record;
  private final Conversation conversation;
  /** The turn whose prompt carries the handoff, until that turn ended. */
  private final AtomicInteger turn = new AtomicInteger(NONE);
  /** The agent took the prompt of {@link #turn}, so the next turn end tells whether the handoff arrived. */
  private volatile boolean isTaken;

  HandoffTracker(ThreadStore.Record record, Conversation conversation) {
    this.record = record;
    this.conversation = conversation;
  }

  /**
   * The prompt with the handoff, while it has not arrived yet. Only one prompt at a time carries it, and no slash
   * command.
   *
   * @param folder the project folder the thread's worktree is a copy of
   */
  String withHandoff(String typed, String prompt, int turn, @Nullable String projectPath, @Nullable String folder) {
    String from = this.record.handoffFrom;
    if (from == null || typed.startsWith("/") || !this.turn.compareAndSet(NONE, turn)) {
      return prompt;
    }
    Handoff.Facts facts = new Handoff.Facts(from, projectPath, this.record.branch, this.record.worktree);
    String handoff = Handoff.text(this.conversation.handoffInput(), facts, Handoff.MAX_TOKENS);
    if (this.record.workPath != null && folder != null) {
      handoff = PromptComposer.rebase(handoff, folder, this.record.workPath);
    }
    return PromptComposer.withHandoff(prompt, handoff);
  }

  /** The agent refused the turn's steered prompt, so a later prompt carries the handoff. */
  void refused(int turn) {
    this.turn.compareAndSet(turn, NONE);
  }

  void taken(int turn) {
    if (turn == this.turn.get()) {
      this.isTaken = true;
    }
  }

  /**
   * @param delivered the turn ended without an error or was stopped by the person, so the session holds the prompt
   * @return whether the handoff arrived with this turn
   */
  boolean turnEnded(boolean delivered) {
    if (!this.isTaken) {
      return false;
    }
    this.reset();
    return delivered;
  }

  void reset() {
    this.isTaken = false;
    this.turn.set(NONE);
  }
}
