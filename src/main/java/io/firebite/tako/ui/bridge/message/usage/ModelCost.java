package io.firebite.tako.ui.bridge.message.usage;

public record ModelCost(
  String model,
  long input,
  long output,
  long cacheRead,
  long cacheWrite,
  double cost
) {
}
