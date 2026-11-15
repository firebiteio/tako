package io.firebite.tako.ui.bridge.message.thread;

public enum SetupPhase {
  RUNNING,
  /** Failed or cancelled; the thread's prompts wait for a retry or a start without setup. */
  FAILED,
  DONE,
  /** The person started the agent without setup. */
  SKIPPED
}
