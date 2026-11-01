package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonParser;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import org.junit.Test;

public class ClaudeLimitsTest {
  private static final String REJECTED = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\","
    + "\"resetsAt\":1900000000,\"rateLimitType\":\"five_hour\",\"overageStatus\":\"rejected\"}}";
  private static final String ALLOWED = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\","
    + "\"resetsAt\":1900000000,\"rateLimitType\":\"five_hour\"}}";
  private static final String FAILED = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
    + "\"result\":\"You've hit your limit\"}";
  private static final String SUCCEEDED = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
    + "\"result\":\"done\"}";

  private final ClaudeLimits limits = new ClaudeLimits();

  private static CliMessage line(String json) {
    return new CliMessage(JsonParser.parseString(json).getAsJsonObject());
  }

  private void observe(String... lines) {
    for (String json : lines) {
      this.limits.observe(line(json));
    }
  }

  private AgentEvent.LimitReached end(String result) {
    CliMessage message = line(result);
    this.limits.observe(message);
    return this.limits.atResult(message);
  }

  @Test
  public void aRejectionThenAFailedTurnIsTheLimitWithItsReset() {
    this.observe(REJECTED);
    assertEquals(new AgentEvent.LimitReached("5h", 1_900_000_000_000L), this.end(FAILED));
  }

  @Test
  public void eachLimitTypeHasItsLabel() {
    String[][] cases = {{"seven_day", "7d"}, {"seven_day_overage_included", "7d"}, {"seven_day_opus", "7d Opus"},
      {"seven_day_sonnet", "7d Sonnet"}, {"overage", null}, {"something_new", null}};
    for (String[] each : cases) {
      this.observe(REJECTED.replace("five_hour", each[0]));
      assertEquals(each[0], each[1], this.end(FAILED).label());
    }
  }

  @Test
  public void tooManyRequestsIsALimitEvenWithoutAnEventOrAnError() {
    assertEquals(new AgentEvent.LimitReached(null, null),
      this.end("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"api_error_status\":429}"));
    assertEquals(new AgentEvent.LimitReached(null, null),
      this.end("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"api_error_status\":429}"));
  }

  @Test
  public void aRateLimitedAnswerCarriesItsReset() {
    this.observe("{\"type\":\"assistant\",\"error\":\"rate_limit\",\"api_error_params\":{\"rate_limit_info\":"
      + "{\"status\":\"rejected\",\"rateLimitType\":\"seven_day\",\"resetsAt\":1900500000}},\"message\":"
      + "{\"content\":[]}}");
    assertEquals(new AgentEvent.LimitReached("7d", 1_900_500_000_000L), this.end(FAILED));
  }

  @Test
  public void aRateLimitedAnswerWithoutDetailsHasNoReset() {
    this.observe("{\"type\":\"assistant\",\"error\":\"rate_limit\",\"message\":{\"content\":[]}}");
    assertEquals(new AgentEvent.LimitReached(null, null), this.end(FAILED));
  }

  @Test
  public void aSubagentsLinesDoNotCount() {
    this.observe("{\"type\":\"assistant\",\"parent_tool_use_id\":\"t1\",\"error\":\"rate_limit\",\"message\":"
      + "{\"content\":[]}}", REJECTED.replace("{\"type\"", "{\"parent_tool_use_id\":\"t1\",\"type\""));
    assertNull(this.end(FAILED));
  }

  @Test
  public void aLaterAllowedEventLiftsTheRejection() {
    this.observe(REJECTED, ALLOWED);
    assertNull(this.end(FAILED));
  }

  @Test
  public void overageThatIsAllowedKeepsTheTurnGoing() {
    this.observe(REJECTED.replace("\"overageStatus\":\"rejected\"", "\"overageStatus\":\"allowed\""));
    assertNull(this.end(FAILED));
    this.observe(REJECTED.replace("\"overageStatus\":\"rejected\"", "\"isUsingOverage\":true"));
    assertNull(this.end(FAILED));
    this.observe(REJECTED.replace("\"overageStatus\":\"rejected\"", "\"overageInUse\":true"));
    assertNull(this.end(FAILED));
  }

  @Test
  public void aTurnThatFinishedAfterARejectionIsNoLimitAndTheNextStartsOver() {
    this.observe(REJECTED);
    assertNull(this.end(SUCCEEDED));
    assertNull(this.end(FAILED));
  }

  @Test
  public void otherFailuresAreNoLimit() {
    assertNull(this.end(FAILED));
    assertNull(this.end("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"api_error_status\":500}"));
    this.observe("{\"type\":\"assistant\",\"error\":\"authentication_failed\",\"message\":{\"content\":[]}}");
    assertNull(this.end(FAILED));
  }

  @Test
  public void aTurnThePersonStoppedIsNoLimit() {
    this.observe(REJECTED);
    this.limits.interrupted();
    assertNull(this.end(FAILED));
    this.observe(REJECTED);
    assertEquals("the stop ended with that turn", "5h", this.end(FAILED).label());
  }

  @Test
  public void stoppingAnIdleSessionDoesNotHideTheNextTurnsLimit() {
    this.limits.interrupted();
    this.limits.prompted();
    this.observe(REJECTED);
    assertEquals("5h", this.end(FAILED).label());
  }

  @Test
  public void malformedResetTimesAreUnknown() {
    String[] resets = {"\"soon\"", "-1", "0", "1e300", "null", "{}", "[1]"};
    for (String reset : resets) {
      this.observe(REJECTED.replace("1900000000", reset));
      assertEquals(reset, new AgentEvent.LimitReached("5h", null), this.end(FAILED));
    }
    this.observe(REJECTED.replace(",\"resetsAt\":1900000000", ""));
    assertEquals(new AgentEvent.LimitReached("5h", null), this.end(FAILED));
  }

  @Test
  public void malformedEventsAreIgnored() {
    this.observe("{\"type\":\"rate_limit_event\"}", "{\"type\":\"rate_limit_event\",\"rate_limit_info\":\"x\"}",
      "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{}}", "{\"type\":\"assistant\",\"error\":7}");
    assertNull(this.end(FAILED));
  }
}
