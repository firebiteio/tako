package io.firebite.tako.agent.claude.transcript;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.PriceRatios;
import io.firebite.tako.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a project's transcripts into usage per session and per day.
 *
 * <p>A session's total comes from its last {@code cost-state} line, the CLI's own list-price figure. The scanner
 * splits it across days by the session's assistant messages, each weighted by Anthropic's fixed price ratios between
 * token kinds, so it needs no absolute price table. A killed process leaves no {@code cost-state}, so its session is
 * estimated from its tokens at the per-model rate the other sessions paid.
 */
final class UsageScanner {
  private UsageScanner() {
  }

  private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

  /** A project's sessions alone, with estimate rates from that project only. */
  static List<SessionUsage> scan(Path directory, ZoneId zone) throws IOException {
    return finish(readDirectory(directory, zone), List.of());
  }

  /**
   * A project's sessions, estimating killed sessions at rates learned from every project under {@code projects}.
   * A project may have no measured session of a model yet.
   */
  static List<SessionUsage> scan(Path directory, Path projects, ZoneId zone) throws IOException {
    List<Transcript> project = readDirectory(directory, zone);
    List<Transcript> all = new ArrayList<>();
    if (Files.isDirectory(projects)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(projects)) {
        for (Path other : stream) {
          if (Files.isDirectory(other) && !other.equals(directory)) {
            all.addAll(readDirectory(other, zone));
          }
        }
      }
    }
    return finish(project, all);
  }

  private static List<Transcript> readDirectory(Path directory, ZoneId zone) throws IOException {
    List<Transcript> transcripts = new ArrayList<>();
    if (!Files.isDirectory(directory)) {
      return transcripts;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SessionStore.TRANSCRIPT_EXTENSION)) {
      for (Path file : stream) {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        Cached cached = CACHE.get(file);
        if (cached == null || cached.size() != size || cached.modified() != modified) {
          cached = new Cached(size, modified, read(file, zone));
          CACHE.put(file, cached);
        }
        transcripts.add(cached.transcript());
      }
    }
    return transcripts;
  }

  static Transcript read(Path file, ZoneId zone) throws IOException {
    String sessionId = SessionStore.sessionId(file);
    Transcript transcript = new Transcript(sessionId, Files.getLastModifiedTime(file).toMillis());
    Map<String, Message> messages = new LinkedHashMap<>();
    readMessages(file, zone, messages, transcript);
    Path subagents = file.resolveSibling(sessionId).resolve("subagents");
    if (Files.isDirectory(subagents)) {
      try (
        DirectoryStream<Path> stream = Files.newDirectoryStream(subagents, "*" + SessionStore.TRANSCRIPT_EXTENSION)) {
        for (Path subagent : stream) {
          readMessages(subagent, zone, messages, null);
        }
      }
    }
    for (Message message : messages.values()) {
      long[] tokens = transcript.tokensByModel.computeIfAbsent(message.model(), model -> new long[4]);
      tokens[0] += message.input();
      tokens[1] += message.output();
      tokens[2] += message.cacheRead();
      tokens[3] += message.cacheWrite5m() + message.cacheWrite1h();
      transcript.unitsByModelDay.computeIfAbsent(message.model(), model -> new TreeMap<>())
        .merge(message.day(), message.units(), Double::sum);
      if (transcript.firstDay == null || message.day().isBefore(transcript.firstDay)) {
        transcript.firstDay = message.day();
      }
    }
    return transcript;
  }

  private static void readMessages(Path file, ZoneId zone, Map<String, Message> messages,
    @Nullable Transcript costStateTarget) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (costStateTarget != null && line.contains("\"type\":\"cost-state\"")) {
          costStateTarget.costState = Json.parseObject(line);
        } else if (line.contains("\"type\":\"assistant\"") && line.contains("\"usage\"")) {
          JsonObject entry = Json.parseObject(line);
          JsonObject message = Json.object(entry, "message");
          String id = Json.string(message, "id");
          String model = Json.string(message, "model");
          JsonObject usage = Json.object(message, "usage");
          LocalDate day = day(Json.string(entry, "timestamp"), zone);
          if (id == null || model == null || usage == null || day == null || model.startsWith("<")) {
            continue;
          }
          long cacheWrite = Json.number(usage, "cache_creation_input_tokens");
          JsonObject split = Json.object(usage, "cache_creation");
          long cacheWrite1h = split == null ? 0 : Json.number(split, "ephemeral_1h_input_tokens");
          messages.put(id,
            new Message(model, day, Json.number(usage, "input_tokens"), Json.number(usage, "output_tokens"),
              Json.number(usage, "cache_read_input_tokens"), Math.max(0, cacheWrite - cacheWrite1h), cacheWrite1h));
        }
      }
    }
  }

  static List<SessionUsage> finish(List<Transcript> transcripts, List<Transcript> others) {
    Map<String, double[]> calibration = new HashMap<>();
    List<Transcript> measured = new ArrayList<>(transcripts);
    measured.addAll(others);
    for (Transcript transcript : measured) {
      if (transcript.costState == null) {
        continue;
      }
      for (SessionUsage.ModelUsage model : SessionUsage.models(Json.object(transcript.costState, "modelUsage"))) {
        double units = transcript.units(model.model());
        if (units > 0 && model.costUsd() > 0) {
          double[] sums = calibration.computeIfAbsent(model.model(), name -> new double[2]);
          sums[0] += model.costUsd();
          sums[1] += units;
        }
      }
    }

    List<SessionUsage> sessions = new ArrayList<>();
    for (Transcript transcript : transcripts) {
      SessionUsage usage = transcript.costState != null ? measured(transcript) : estimated(transcript, calibration);
      if (usage != null) {
        sessions.add(usage);
      }
    }
    return sessions;
  }

  private static SessionUsage measured(Transcript transcript) {
    JsonObject costState = transcript.costState;
    List<SessionUsage.ModelUsage> models = SessionUsage.models(Json.object(costState, "modelUsage"));
    long startTime = Json.number(costState, "startTime");
    LocalDate fallbackDay = transcript.firstDay != null
      ? transcript.firstDay
      : Instant.ofEpochMilli(startTime > 0 ? startTime : transcript.lastModified).atZone(ZoneId.systemDefault())
        .toLocalDate();
    Map<LocalDate, Double> costByDay = new TreeMap<>();
    double assigned = 0;
    for (SessionUsage.ModelUsage model : models) {
      double units = transcript.units(model.model());
      Map<LocalDate, Double> days = transcript.unitsByModelDay.get(model.model());
      if (units <= 0 || days == null) {
        costByDay.merge(fallbackDay, model.costUsd(), Double::sum);
      } else {
        for (Map.Entry<LocalDate, Double> day : days.entrySet()) {
          costByDay.merge(day.getKey(), model.costUsd() * day.getValue() / units, Double::sum);
        }
      }
      assigned += model.costUsd();
    }
    double total = Json.decimal(costState, "totalCostUSD");
    if (total - assigned > 1e-9) {
      costByDay.merge(fallbackDay, total - assigned, Double::sum);
    }
    return new SessionUsage(transcript.sessionId, total, models, startTime, transcript.lastModified, costByDay, false);
  }

  private static @Nullable SessionUsage estimated(Transcript transcript, Map<String, double[]> calibration) {
    if (transcript.tokensByModel.isEmpty()) {
      return null;
    }
    List<SessionUsage.ModelUsage> models = new ArrayList<>();
    Map<LocalDate, Double> costByDay = new TreeMap<>();
    double total = 0;
    for (Map.Entry<String, long[]> entry : transcript.tokensByModel.entrySet()) {
      double[] sums = calibration.get(entry.getKey());
      double rate = sums == null || sums[1] <= 0 ? 0 : sums[0] / sums[1];
      double cost = 0;
      for (Map.Entry<LocalDate, Double> day : transcript.unitsByModelDay.get(entry.getKey()).entrySet()) {
        double dayCost = day.getValue() * rate;
        costByDay.merge(day.getKey(), dayCost, Double::sum);
        cost += dayCost;
      }
      long[] tokens = entry.getValue();
      models.add(new SessionUsage.ModelUsage(entry.getKey(), tokens[0], tokens[1], tokens[2], tokens[3], cost));
      total += cost;
    }
    long startTime = transcript.firstDay == null
      ? transcript.lastModified
      : transcript.firstDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    return new SessionUsage(transcript.sessionId, total, models, startTime, transcript.lastModified, costByDay, true);
  }

  private static @Nullable LocalDate day(@Nullable String timestamp, ZoneId zone) {
    if (timestamp == null) {
      return null;
    }
    try {
      return Instant.parse(timestamp).atZone(zone).toLocalDate();
    } catch (DateTimeException e) {
      return null;
    }
  }

  static final class Transcript {
    final String sessionId;
    final long lastModified;
    @Nullable
    JsonObject costState;
    final Map<String, long[]> tokensByModel = new TreeMap<>();
    final Map<String, Map<LocalDate, Double>> unitsByModelDay = new TreeMap<>();
    @Nullable
    LocalDate firstDay;

    Transcript(String sessionId, long lastModified) {
      this.sessionId = sessionId;
      this.lastModified = lastModified;
    }

    double units(String model) {
      double total = 0;
      Map<LocalDate, Double> days = this.unitsByModelDay.get(model);
      if (days != null) {
        for (double units : days.values()) {
          total += units;
        }
      }
      return total;
    }
  }

  private record Message(
    String model,
    LocalDate day,
    long input,
    long output,
    long cacheRead,
    long cacheWrite5m,
    long cacheWrite1h
  ) {

    double units() {
      return PriceRatios.units(this.input, this.output, this.cacheRead, this.cacheWrite5m, this.cacheWrite1h);
    }
  }

  private record Cached(
    long size,
    long modified,
    Transcript transcript
  ) {
  }
}
