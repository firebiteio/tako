package io.firebite.tako.agent.claude;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.json.Json;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Tells from the CLI's lines whether a turn stopped on a usage or rate limit, and when that resets. Watches the turn's
 * {@code rate_limit_event} lines and rate-limited assistant messages, then decides at its {@code result}.
 */
final class ClaudeLimits {
  private static final int TOO_MANY_REQUESTS = 429;
  private static final String RATE_LIMIT_INFO = "rate_limit_info";
  private static final String RESETS_AT = "resetsAt";
  private static final Set<String> OVERAGE_ALLOWED = Set.of("allowed", "allowed_warning");

  /** The last {@code rate_limit_info} that blocked the turn, until one allowed it again. */
  private @Nullable JsonObject rejection;
  /** The {@code rate_limit_info} of the last rate-limited assistant message, or an empty one without it. */
  private @Nullable JsonObject rateLimited;
  private boolean isInterrupted;

  synchronized void observe(CliMessage message) {
    JsonObject json = message.json();
    if (Json.string(json, "parent_tool_use_id") != null) {
      return;
    }
    if (message.type() == CliMessage.Type.ASSISTANT && "rate_limit".equals(Json.string(json, "error"))) {
      JsonObject info = Json.object(Json.object(json, "api_error_params"), RATE_LIMIT_INFO);
      this.rateLimited = info != null ? info : new JsonObject();
    } else if (message.type() == CliMessage.Type.OTHER && "rate_limit_event".equals(Json.string(json, "type"))) {
      JsonObject info = Json.object(json, RATE_LIMIT_INFO);
      if (info != null) {
        this.rejection = isBlocking(info) ? info : null;
      }
    }
  }

  /** The person stopped the turn, so its end is not a limit's doing. */
  synchronized void interrupted() {
    this.isInterrupted = true;
  }

  /** A prompt went out, which starts or joins a turn the person did not stop. */
  synchronized void prompted() {
    this.isInterrupted = false;
  }

  /** The limit the turn ending with {@code result} stopped on, or null. Starts over for the next turn. */
  synchronized @Nullable AgentEvent.LimitReached atResult(CliMessage result) {
    try {
      JsonObject json = result.json();
      if (this.isInterrupted) {
        return null;
      }
      String subtype = Json.string(json, "subtype");
      boolean failed = Json.bool(json, "is_error") || subtype != null && !"success".equals(subtype);
      boolean tooMany = Json.number(json, "api_error_status") == TOO_MANY_REQUESTS;
      if (!tooMany && !(failed && (this.rejection != null || this.rateLimited != null))) {
        return null;
      }
      JsonObject info = this.rejection != null ? this.rejection : this.rateLimited;
      return new AgentEvent.LimitReached(label(Json.string(info, "rateLimitType")),
        AgentEvent.LimitReached.epochSeconds(info, RESETS_AT));
    } finally {
      this.rejection = null;
      this.rateLimited = null;
      this.isInterrupted = false;
    }
  }

  /** A rejection the account cannot pay its way past with overage. */
  private static boolean isBlocking(JsonObject info) {
    return "rejected".equals(Json.string(info, "status"))
      && !OVERAGE_ALLOWED.contains(String.valueOf(Json.string(info, "overageStatus")))
      && !Json.bool(info, "isUsingOverage") && !Json.bool(info, "overageInUse");
  }

  private static @Nullable String label(@Nullable String type) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "five_hour" -> "5h";
      case "seven_day", "seven_day_overage_included" -> "7d";
      case "seven_day_opus" -> "7d Opus";
      case "seven_day_sonnet" -> "7d Sonnet";
      default -> null;
    };
  }
}
