package io.firebite.tako.chat.thread;

import io.firebite.tako.chat.thread.ChatController.State;
import io.firebite.tako.ide.Async;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The thread's {@link State}, which callers can wait on until the agent is no longer busy. Its monitor guards each
 * change, so a caller that holds it can check and change the state at once.
 */
final class StateCell {
  /** Runs on the EDT after each change. */
  private final Runnable changed;
  private volatile State state = State.STARTING;
  /** Guarded by this. */
  private boolean isClosed;
  /** Completes once the agent is no longer busy, replaced when it gets busy again. Guarded by this. */
  private CompletableFuture<State> settled = new CompletableFuture<>();

  StateCell(Runnable changed) {
    this.changed = changed;
  }

  State get() {
    return this.state;
  }

  void set(State state) {
    synchronized (this) {
      this.state = state;
      this.wake();
    }
    Async.onEdt(this.changed);
  }

  /** The agent is ready. A prompt sent to it meanwhile already made the thread work, which stays so. */
  void idleIfStarting() {
    synchronized (this) {
      if (this.state != State.STARTING) {
        return;
      }
      this.state = State.IDLE;
      this.wake();
    }
    Async.onEdt(this.changed);
  }

  /** The thread closed, so waiters stop waiting. */
  synchronized void close() {
    this.isClosed = true;
    this.wake();
  }

  /**
   * Waits until the agent is no longer {@link State#isBusy busy}, the timeout passed or the thread closed.
   *
   * @return the state then
   */
  synchronized State awaitSettled(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    long left = deadline - System.nanoTime();
    while (this.state.isBusy() && !this.isClosed && left > 0) {
      TimeUnit.NANOSECONDS.timedWait(this, left);
      left = deadline - System.nanoTime();
    }
    return this.state;
  }

  /** Like {@link #awaitSettled} without blocking. */
  synchronized CompletableFuture<State> settled() {
    return this.settled.copy();
  }

  /** Under this: wakes the waiters on a change of {@link #state}. */
  private void wake() {
    this.notifyAll();
    if (this.state.isBusy() && !this.isClosed) {
      if (this.settled.isDone()) {
        this.settled = new CompletableFuture<>();
      }
    } else {
      this.settled.complete(this.state);
    }
  }
}
