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
  public static final SessionCost NONE = new SessionCost(0, 0, 0, 0, 0, null);

  /**
   * This usage plus what {@code reported} counts beyond {@code restored}. An agent resumed on a session counts on from
   * the figure it restored, which misses whatever a killed run spent after saving it.
   */
  public SessionCost plusSince(SessionCost reported, SessionCost restored) {
    return new SessionCost(
      this.costUsd + Math.max(0, reported.costUsd - restored.costUsd),
      this.inputTokens + Math.max(0, reported.inputTokens - restored.inputTokens),
      this.outputTokens + Math.max(0, reported.outputTokens - restored.outputTokens),
      this.cacheReadTokens + Math.max(0, reported.cacheReadTokens - restored.cacheReadTokens),
      this.cacheWriteTokens + Math.max(0, reported.cacheWriteTokens - restored.cacheWriteTokens),
      reported.inputRate);
  }
}
