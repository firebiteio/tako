package io.firebite.tako.ui.bridge.message.usage;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Cost and tokens of every session of the workspace's project folders, at API list prices. */
public record Usage(
  double totalCost,
  long totalTokens,
  int sessions,
  int estimatedSessions,
  double estimatedCost,
  int retentionDays,
  @Nullable String oldestDay,
  List<ModelCost> models,
  List<DayCost> days,
  List<ProjectCost> projects,
  List<SessionCost> top
) {
}
