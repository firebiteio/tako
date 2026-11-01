package io.firebite.tako.chat.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A pull request as one poll saw it, read from {@code gh pr view --json} and the REST list of its review comments.
 *
 * @param state     {@code OPEN}, {@code MERGED} or {@code CLOSED}
 * @param mergeable {@code MERGEABLE}, {@code CONFLICTING} or {@code UNKNOWN} while GitHub computes it
 */
public record PullRequestStatus(
  int number,
  String url,
  String title,
  String state,
  String headSha,
  String baseBranch,
  String mergeable,
  List<Check> checks,
  List<Review> reviews,
  List<Comment> comments
) {
  /** The {@code gh pr view --json} fields {@link #parse} reads. */
  public static final String VIEW_FIELDS = "number,url,title,state,headRefOid,baseRefName,mergeable,"
    + "statusCheckRollup,reviews";
  private static final Set<String> FAILED_CONCLUSIONS = Set.of("FAILURE", "TIMED_OUT", "CANCELLED",
    "ACTION_REQUIRED", "STARTUP_FAILURE");

  /**
   * @param viewJson     the output of {@code gh pr view --json} {@link #VIEW_FIELDS}
   * @param commentsJson the REST array of review comments; null or malformed reads as none
   * @return null when the view is not a pull request
   */
  public static @Nullable PullRequestStatus parse(@Nullable String viewJson, @Nullable String commentsJson) {
    JsonObject view = Json.parseObject(viewJson);
    String url = Json.string(view, "url");
    if (url == null) {
      return null;
    }
    return new PullRequestStatus((int) Json.number(view, "number"), url, text(view, "title"), text(view, "state"),
      text(view, "headRefOid"), text(view, "baseRefName"), text(view, "mergeable"), checks(view), reviews(view),
      comments(commentsJson));
  }

  public boolean isOpen() {
    return "OPEN".equals(this.state);
  }

  /** {@code #12}, as the header shows it. */
  public String label() {
    return "#" + this.number;
  }

  public int count(Outcome outcome) {
    int count = 0;
    for (Check check : this.checks) {
      if (check.outcome() == outcome) {
        count++;
      }
    }
    return count;
  }

  /** The latest run of each check: GitHub lists a check again for every re-run. */
  private static List<Check> checks(@Nullable JsonObject view) {
    Map<String, JsonObject> latest = new LinkedHashMap<>();
    for (JsonObject item : Json.objects(view, "statusCheckRollup")) {
      String key = text(item, "__typename") + "\n" + name(item);
      JsonObject known = latest.get(key);
      if (known == null || text(item, "startedAt").compareTo(text(known, "startedAt")) >= 0) {
        latest.put(key, item);
      }
    }
    List<Check> checks = new ArrayList<>();
    for (JsonObject item : latest.values()) {
      if ("StatusContext".equals(text(item, "__typename"))) {
        checks.add(new Check(name(item), Json.string(item, "targetUrl"), contextOutcome(text(item, "state"))));
      } else {
        checks.add(new Check(name(item), Json.string(item, "detailsUrl"), runOutcome(item)));
      }
    }
    return checks;
  }

  private static String name(JsonObject item) {
    if ("StatusContext".equals(text(item, "__typename"))) {
      return text(item, "context");
    }
    String workflow = text(item, "workflowName");
    String name = text(item, "name");
    return workflow.isEmpty() ? name : workflow + " / " + name;
  }

  private static Outcome runOutcome(JsonObject item) {
    if (!"COMPLETED".equals(text(item, "status"))) {
      return Outcome.PENDING;
    }
    return FAILED_CONCLUSIONS.contains(text(item, "conclusion")) ? Outcome.FAILED : Outcome.PASSED;
  }

  private static Outcome contextOutcome(String state) {
    return switch (state) {
      case "PENDING", "EXPECTED", "" -> Outcome.PENDING;
      case "FAILURE", "ERROR" -> Outcome.FAILED;
      default -> Outcome.PASSED;
    };
  }

  private static List<Review> reviews(@Nullable JsonObject view) {
    List<Review> reviews = new ArrayList<>();
    for (JsonObject item : Json.objects(view, "reviews")) {
      String id = Json.string(item, "id");
      if (id != null) {
        reviews.add(new Review(id, text(Json.object(item, "author"), "login"), text(item, "state"),
          text(item, "body")));
      }
    }
    return reviews;
  }

  private static List<Comment> comments(@Nullable String json) {
    List<Comment> comments = new ArrayList<>();
    JsonArray array = parseArray(json);
    if (array == null) {
      return comments;
    }
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject item = element.getAsJsonObject();
      Long id = Json.optionalNumber(item, "id");
      if (id != null) {
        comments.add(new Comment(id, text(Json.object(item, "user"), "login"), text(item, "path"),
          optionalInt(item, "line"), optionalInt(item, "original_line"), text(item, "body"),
          text(item, "html_url")));
      }
    }
    return comments;
  }

  private static @Nullable JsonArray parseArray(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonElement element = JsonParser.parseString(json);
      return element.isJsonArray() ? element.getAsJsonArray() : null;
    } catch (JsonParseException e) {
      return null;
    }
  }

  private static @Nullable Integer optionalInt(JsonObject item, String key) {
    Long value = Json.optionalNumber(item, key);
    return value == null ? null : value.intValue();
  }

  private static String text(@Nullable JsonObject object, String key) {
    return Objects.requireNonNullElse(Json.string(object, key), "");
  }

  public enum Outcome {
    PENDING,
    PASSED,
    FAILED
  }

  public record Check(
    String name,
    @Nullable String url,
    Outcome outcome
  ) {
  }

  /** @param state {@code APPROVED}, {@code CHANGES_REQUESTED}, {@code COMMENTED} or {@code DISMISSED} */
  public record Review(
    String id,
    String author,
    String state,
    String body
  ) {
  }

  /** An inline review comment. @param line null when the code it was on changed since */
  public record Comment(
    long id,
    String author,
    String path,
    @Nullable Integer line,
    @Nullable Integer originalLine,
    String body,
    String url
  ) {
  }
}
