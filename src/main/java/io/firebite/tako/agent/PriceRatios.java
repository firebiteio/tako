package io.firebite.tako.agent;

/** Anthropic's fixed price ratios between token kinds, relative to an input token. */
public final class PriceRatios {
  public static final double OUTPUT = 5;
  public static final double CACHE_READ = 0.1;
  public static final double CACHE_WRITE_5M = 1.25;
  public static final double CACHE_WRITE_1H = 2;

  private PriceRatios() {
  }

  /** The tokens' price in input tokens. */
  public static double units(long input, long output, long cacheRead, long cacheWrite5m, long cacheWrite1h) {
    return input + output * OUTPUT + cacheRead * CACHE_READ + cacheWrite5m * CACHE_WRITE_5M
      + cacheWrite1h * CACHE_WRITE_1H;
  }
}
