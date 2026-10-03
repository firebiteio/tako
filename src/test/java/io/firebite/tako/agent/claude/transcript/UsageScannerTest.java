package io.firebite.tako.agent.claude.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.Test;

public class UsageScannerTest {
  private static final String MEASURED = "11111111-1111-1111-1111-111111111111";
  private static final String KILLED = "22222222-2222-2222-2222-222222222222";

  private static String assistant(String id, String timestamp, int outputTokens) {
    return "{\"type\":\"assistant\",\"timestamp\":\"" + timestamp + "\",\"message\":{\"id\":\"" + id
      + "\",\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":0,\"output_tokens\":" + outputTokens
      + ",\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}}";
  }

  private static SessionUsage find(List<SessionUsage> sessions, String id) {
    for (SessionUsage session : sessions) {
      if (session.sessionId().equals(id)) {
        return session;
      }
    }
    throw new AssertionError("No session " + id);
  }

  @Test
  public void splitsMeasuredCostOverDaysAndEstimatesKilledSessions() throws Exception {
    Path directory = Files.createTempDirectory("usage");
    Files.write(directory.resolve(MEASURED + ".jsonl"), List.of(
      assistant("m1", "2026-09-01T10:00:00Z", 100),
      assistant("m1", "2026-09-01T10:00:01Z", 100),
      assistant("m2", "2026-09-02T10:00:00Z", 100),
      "{\"type\":\"cost-state\",\"sessionId\":\"" + MEASURED + "\",\"totalCostUSD\":3.0,\"startTime\":1,"
        + "\"modelUsage\":{\"claude-opus-5-5\":{\"inputTokens\":0,\"outputTokens\":400,\"costUSD\":3.0}}}"),
      StandardCharsets.UTF_8);
    Path subagents = Files.createDirectories(directory.resolve(MEASURED).resolve("subagents"));
    Files.write(subagents.resolve("agent-1.jsonl"), List.of(assistant("m3", "2026-09-02T11:00:00Z", 100)),
      StandardCharsets.UTF_8);
    Files.write(directory.resolve(KILLED + ".jsonl"), List.of(assistant("k1", "2026-09-03T10:00:00Z", 200)),
      StandardCharsets.UTF_8);

    List<SessionUsage> sessions = UsageScanner.scan(directory, ZoneOffset.UTC);

    SessionUsage measured = find(sessions, MEASURED);
    assertFalse(measured.estimated());
    assertEquals(3.0, measured.costUsd(), 1e-9);
    assertEquals(1.0, measured.costByDay().get(LocalDate.of(2026, 9, 1)), 1e-9);
    assertEquals(2.0, measured.costByDay().get(LocalDate.of(2026, 9, 2)), 1e-9);

    SessionUsage killed = find(sessions, KILLED);
    assertTrue(killed.estimated());
    assertEquals(2.0, killed.costUsd(), 1e-9);
    assertEquals(200, killed.models().get(0).outputTokens());
    assertEquals(2.0, killed.costByDay().get(LocalDate.of(2026, 9, 3)), 1e-9);
  }

  @Test
  public void oneHourCacheWritesWeighDouble() throws Exception {
    Path directory = Files.createTempDirectory("usage");
    Files.write(directory.resolve(MEASURED + ".jsonl"), List.of(
      "{\"type\":\"assistant\",\"timestamp\":\"2026-09-01T10:00:00Z\",\"message\":{\"id\":\"a\",\"model\":\"m\",\"usage\":"
        + "{\"input_tokens\":0,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":100,"
        + "\"cache_creation\":{\"ephemeral_1h_input_tokens\":100,\"ephemeral_5m_input_tokens\":0}}}}",
      "{\"type\":\"assistant\",\"timestamp\":\"2026-09-02T10:00:00Z\",\"message\":{\"id\":\"b\",\"model\":\"m\",\"usage\":"
        + "{\"input_tokens\":200,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}}",
      "{\"type\":\"cost-state\",\"sessionId\":\"" + MEASURED + "\",\"totalCostUSD\":4.0,"
        + "\"modelUsage\":{\"m\":{\"costUSD\":4.0}}}"),
      StandardCharsets.UTF_8);

    SessionUsage usage = UsageScanner.scan(directory, ZoneOffset.UTC).get(0);

    assertEquals(2.0, usage.costByDay().get(LocalDate.of(2026, 9, 1)), 1e-9);
    assertEquals(2.0, usage.costByDay().get(LocalDate.of(2026, 9, 2)), 1e-9);
  }

  @Test
  public void estimatesWithRatesFromOtherProjects() throws Exception {
    Path projects = Files.createTempDirectory("projects");
    Path measuredProject = Files.createDirectories(projects.resolve("a"));
    Path killedProject = Files.createDirectories(projects.resolve("b"));
    Files.write(measuredProject.resolve(MEASURED + ".jsonl"), List.of(
      assistant("m1", "2026-09-01T10:00:00Z", 100),
      "{\"type\":\"cost-state\",\"sessionId\":\"" + MEASURED + "\",\"totalCostUSD\":1.0,"
        + "\"modelUsage\":{\"claude-opus-5-5\":{\"outputTokens\":100,\"costUSD\":1.0}}}"),
      StandardCharsets.UTF_8);
    Files.write(killedProject.resolve(KILLED + ".jsonl"), List.of(assistant("k1", "2026-09-03T10:00:00Z", 300)),
      StandardCharsets.UTF_8);

    List<SessionUsage> sessions = UsageScanner.scan(killedProject, projects, ZoneOffset.UTC);

    assertEquals(1, sessions.size());
    assertEquals(3.0, sessions.get(0).costUsd(), 1e-9);
  }
}
