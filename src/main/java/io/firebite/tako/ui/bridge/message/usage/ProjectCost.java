package io.firebite.tako.ui.bridge.message.usage;

public record ProjectCost(
  String name,
  String path,
  double cost,
  long tokens,
  int sessions
) {
}
