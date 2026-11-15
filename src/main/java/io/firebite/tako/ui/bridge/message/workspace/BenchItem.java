package io.firebite.tako.ui.bridge.message.workspace;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * @param baseCommit the short hash of the commit the runs started from
 * @param threads    the runs' threads in run order, including closed ones
 */
public record BenchItem(
  String id,
  String title,
  @Nullable String projectPath,
  String baseCommit,
  List<String> threads
) {
}
