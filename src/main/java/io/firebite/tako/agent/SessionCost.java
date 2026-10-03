package io.firebite.tako.agent;

/** What a session consumed so far, at the agent's own list prices. */
public record SessionCost(
  double costUsd,
  long inputTokens,
  long outputTokens,
  long cacheReadTokens,
  long cacheWriteTokens
) {
}
