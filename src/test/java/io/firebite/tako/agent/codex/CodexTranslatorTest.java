package io.firebite.tako.agent.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.firebite.tako.agent.AgentEvent;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

public class CodexTranslatorTest {
  private static final long NOW = 1_800_000_000_000L;
  private static final JsonObject FULL_5H = json("{\"usedPercent\":100,\"windowDurationMins\":300,"
    + "\"resetsAt\":1900000000}");
  private static final JsonObject FULL_7D = json("{\"usedPercent\":100.0,\"windowDurationMins\":10080,"
    + "\"resetsAt\":1900500000}");
  private static final JsonObject HALF_7D = json("{\"usedPercent\":50,\"windowDurationMins\":10080,"
    + "\"resetsAt\":1900500000}");

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  private static JsonObject failed(String errorInfo) {
    return json("{\"status\":\"failed\",\"error\":{\"message\":\"stop\",\"codexErrorInfo\":" + errorInfo + "}}");
  }

  private static @Nullable AgentEvent.LimitReached limit(JsonObject turn, @Nullable JsonObject primary,
    @Nullable JsonObject secondary) {
    return CodexTranslator.limitReached(turn, primary, secondary, NOW);
  }

  @Test
  public void aWindowWhoseResetPassedIsOldNews() {
    JsonObject turn = failed("\"usageLimitExceeded\"");
    JsonObject yesterday = json("{\"usedPercent\":100,\"windowDurationMins\":300,\"resetsAt\":1799900000}");
    assertEquals(new AgentEvent.LimitReached(null, null), limit(turn, yesterday, null));
    assertEquals(new AgentEvent.LimitReached("7d", 1_900_500_000_000L), limit(turn, yesterday, FULL_7D));
  }

  @Test
  public void theUsedUpWindowNamesTheLimitAndItsReset() {
    assertEquals(new AgentEvent.LimitReached("5h", 1_900_000_000_000L),
      limit(failed("\"usageLimitExceeded\""), FULL_5H, HALF_7D));
  }

  @Test
  public void withBothWindowsUsedUpTheLaterResetCounts() {
    assertEquals(new AgentEvent.LimitReached("7d", 1_900_500_000_000L),
      limit(failed("\"rateLimitExceeded\""), FULL_5H, FULL_7D));
  }

  @Test
  public void retriesExhaustedOnTooManyRequestsCountAsALimit() {
    assertEquals(new AgentEvent.LimitReached("5h", 1_900_000_000_000L),
      limit(failed("{\"responseTooManyFailedAttempts\":{\"httpStatusCode\":429}}"), FULL_5H, null));
    assertNull(limit(failed("{\"responseTooManyFailedAttempts\":{\"httpStatusCode\":500}}"), FULL_5H, null));
    assertNull(limit(failed("{\"responseTooManyFailedAttempts\":null}"), FULL_5H, null));
  }

  @Test
  public void otherFailuresAndFinishedTurnsAreNoLimit() {
    assertNull(limit(failed("\"contextWindowExceeded\""), FULL_5H, null));
    assertNull(limit(failed("null"), FULL_5H, null));
    assertNull(limit(failed("[]"), FULL_5H, null));
    assertNull(limit(json("{\"status\":\"failed\"}"), FULL_5H, null));
    assertNull(limit(json("{\"status\":\"completed\",\"error\":{\"codexErrorInfo\":\"usageLimitExceeded\"}}"),
      FULL_5H, null));
    assertNull(limit(json("{\"status\":\"interrupted\"}"), FULL_5H, null));
  }

  @Test
  public void theResetIsUnknownWithoutAUsedUpWindowWithAReset() {
    JsonObject turn = failed("\"usageLimitExceeded\"");
    assertEquals(new AgentEvent.LimitReached(null, null), limit(turn, null, null));
    assertEquals(new AgentEvent.LimitReached(null, null), limit(turn, HALF_7D, null));
    assertEquals(new AgentEvent.LimitReached(null, null),
      limit(turn, json("{\"usedPercent\":100,\"windowDurationMins\":300}"), FULL_7D));
    assertEquals(new AgentEvent.LimitReached(null, null),
      limit(turn, json("{\"usedPercent\":100,\"resetsAt\":\"soon\"}"), null));
    assertEquals(new AgentEvent.LimitReached(null, null),
      limit(turn, json("{\"usedPercent\":100,\"resetsAt\":1e300}"), null));
    assertEquals(new AgentEvent.LimitReached(null, null),
      limit(turn, json("{\"usedPercent\":100,\"resetsAt\":-5}"), null));
  }

  @Test
  public void aWindowWithoutItsLengthHasNoLabel() {
    assertEquals(new AgentEvent.LimitReached(null, 1_900_000_000_000L),
      limit(failed("\"usageLimitExceeded\""), json("{\"usedPercent\":100,\"resetsAt\":1900000000}"), null));
  }
}
