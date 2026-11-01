package io.firebite.tako.chat.git;

/** The texts of a git action: the commit message, and the pull request's title and description. */
public record Draft(
  String commitMessage,
  String title,
  String body
) {
  public static final Draft EMPTY = new Draft("", "", "");

  /** Whether the texts the action needs are written: a commit message to commit, a title to open a pull request. */
  public boolean isComplete(boolean commits, boolean pullRequest) {
    return (!commits || !this.commitMessage.isBlank()) && (!pullRequest || !this.title.isBlank());
  }
}
