package io.firebite.tako.ui.bridge.message.usage;

import org.jetbrains.annotations.Nullable;

/** @param thread the thread holding the session, if any */
public record SessionCost(
  String sessionId,
  String projectPath,
  String projectName,
  String title,
  double cost,
  long tokens,
  @Nullable String thread,
  boolean archived,
  boolean estimated
) {
}
