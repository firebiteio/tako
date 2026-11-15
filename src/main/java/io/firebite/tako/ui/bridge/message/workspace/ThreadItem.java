package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ide.project.GitInfo;
import org.jetbrains.annotations.Nullable;

public record ThreadItem(
  String id,
  String title,
  boolean archived,
  long updatedAt,
  boolean hasSession,
  double costUsd,
  long tokens,
  @Nullable String branch,
  @Nullable String worktree,
  @Nullable String projectPath,
  @Nullable GitInfo.PullRequest pullRequest,
  String agent,
  long firstTurnMillis,
  @Nullable String parentId,
  @Nullable String watching,
  boolean pinned,
  boolean unread,
  long snoozedUntil,
  @Nullable LimitItem limit,
  boolean sideChat,
  int inheritedPrompts
) {
}
