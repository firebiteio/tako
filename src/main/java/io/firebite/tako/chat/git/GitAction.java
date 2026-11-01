package io.firebite.tako.chat.git;

/** What the thread header's git menu offers. Each commits first when there is something to commit. */
public enum GitAction {
  COMMIT,
  COMMIT_AND_PUSH,
  COMMIT_AND_PULL_REQUEST;

  public boolean pushes() {
    return this != COMMIT;
  }

  public boolean createsPullRequest() {
    return this == COMMIT_AND_PULL_REQUEST;
  }
}
