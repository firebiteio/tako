package io.firebite.tako.agent;

import org.jetbrains.annotations.Nullable;

/**
 * What a session consumed so far, at the agent's own list prices.
 *
 * @param inputRate USD per input token of the model the session spent the most on, when known
 */
public record SessionCost(
  double costUsd,
  long inputTokens,
  long outputTokens,
  long cacheReadTokens,
  long cacheWriteTokens,
  @Nullable Double inputRate
) {
}
