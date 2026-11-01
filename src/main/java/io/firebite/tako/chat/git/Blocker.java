package io.firebite.tako.chat.git;

/** Why a git action is not possible now. */
public enum Blocker {
  NOT_REPOSITORY,
  /** A git action runs in the same repository, started from any thread. */
  RUNNING,
  /** The thread's agent works on a turn or waits for an answer. */
  AGENT_BUSY,
  /** Another thread's agent works in the same repository, so committing everything would take its edits. */
  OTHER_THREAD_BUSY,
  DETACHED,
  CONFLICTS,
  NOTHING_TO_COMMIT,
  NO_REMOTE,
  NOTHING_TO_PUSH,
  /** Pull requests need {@code origin} on GitHub as the push remote. */
  NOT_GITHUB,
  GH_MISSING,
  GH_LOGGED_OUT,
  PULL_REQUEST_OPEN,
  DEFAULT_BRANCH
}
