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
  /** Pull requests need the remote the branch pushes to on GitHub or GitLab. */
  NOT_HOSTED,
  /** The forge CLI's state is being asked for, see {@link ThreadGit#checkForge}. */
  CLI_CHECKING,
  CLI_MISSING,
  CLI_LOGGED_OUT,
  PULL_REQUEST_OPEN,
  DEFAULT_BRANCH
}
