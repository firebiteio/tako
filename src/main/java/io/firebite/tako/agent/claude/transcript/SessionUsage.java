package io.firebite.tako.agent.claude.transcript;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.agent.PriceRatios;
import io.firebite.tako.agent.SessionCost;
import io.firebite.tako.json.Json;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What one session consumed, as the CLI reports it in the {@code modelUsage} of a {@code result} or of a
 * transcript's last {@code cost-state} line. Both cover the whole session, resumes included.
 *
 * @param costUsd   at API list prices ({@code costBasis: list})
 * @param costByDay the cost split over the days the session's messages were sent
 * @param estimated the process was killed before it wrote a {@code cost-state}, so the cost comes from tokens
 */
public record SessionUsage(
  String sessionId,
  double costUsd,
  List<ModelUsage> models,
  long startTime,
  long lastModified,
  Map<LocalDate, Double> costByDay,
  boolean estimated
) {
  public static List<ModelUsage> models(@Nullable JsonObject modelUsage) {
    List<ModelUsage> models = new ArrayList<>();
    if (modelUsage == null) {
      return models;
    }
    for (Map.Entry<String, JsonElement> entry : modelUsage.entrySet()) {
      if (!entry.getValue().isJsonObject()) {
        continue;
      }
      JsonObject usage = entry.getValue().getAsJsonObject();
      models.add(new ModelUsage(entry.getKey(),
        Json.number(usage, "inputTokens"), Json.number(usage, "outputTokens"),
        Json.number(usage, "cacheReadInputTokens"), Json.number(usage, "cacheCreationInputTokens"),
        Json.decimal(usage, "costUSD")));
    }
    return models;
  }

  /** Reads a {@code cost-state} transcript line, or returns null for any other line. */
  public static @Nullable SessionUsage fromCostState(@Nullable JsonObject line, long lastModified) {
    if (line == null || !"cost-state".equals(Json.string(line, "type"))) {
      return null;
    }
    return new SessionUsage(String.valueOf(Json.string(line, "sessionId")), Json.decimal(line, "totalCostUSD"),
      models(Json.object(line, "modelUsage")), Json.number(line, "startTime"), lastModified, Map.of(), false);
  }

  /** The session's total over its models. */
  public static SessionCost cost(List<ModelUsage> models, double costUsd) {
    long input = 0;
    long output = 0;
    long cacheRead = 0;
    long cacheWrite = 0;
    ModelUsage main = null;
    for (ModelUsage model : models) {
      input += model.inputTokens();
      output += model.outputTokens();
      cacheRead += model.cacheReadTokens();
      cacheWrite += model.cacheWriteTokens();
      if (main == null || model.costUsd() > main.costUsd()) {
        main = model;
      }
    }
    return new SessionCost(costUsd, input, output, cacheRead, cacheWrite, main == null ? null : main.inputRate());
  }

  public SessionCost toCost() {
    return cost(this.models, this.costUsd);
  }

  public long totalTokens() {
    long total = 0;
    for (ModelUsage model : this.models) {
      total += model.totalTokens();
    }
    return total;
  }

  public record ModelUsage(
    String model,
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    double costUsd
  ) {

    public long totalTokens() {
      return this.inputTokens + this.outputTokens + this.cacheReadTokens + this.cacheWriteTokens;
    }

    /** USD per input token, reading cache writes as 5-minute ones since the CLI does not split them here. */
    @Nullable
    Double inputRate() {
      double units = PriceRatios.units(this.inputTokens, this.outputTokens, this.cacheReadTokens,
        this.cacheWriteTokens, 0);
      return this.costUsd > 0 && units > 0 ? this.costUsd / units : null;
    }
  }
}
