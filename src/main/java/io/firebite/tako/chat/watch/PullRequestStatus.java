package io.firebite.tako.chat.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.firebite.tako.ide.project.Forge;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A pull request as one poll saw it: on GitHub read from {@code gh pr view --json} and the REST list of its review
 * comments, on GitLab from the merge request, its head pipeline's jobs and its notes, in GitHub's terms.
 *
 * @param state     {@code OPEN}, {@code MERGED} or {@code CLOSED}
 * @param mergeable {@code MERGEABLE}, {@code CONFLICTING} or {@code UNKNOWN} while the forge computes it
 */
public record PullRequestStatus(
  Forge forge,
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
  /** GitLab job states that are not done yet. */
  private static final Set<String> GITLAB_RUNNING = Set.of("created", "pending", "running", "preparing",
    "waiting_for_resource", "waiting_for_callback", "scheduled");
  /** GitLab merge states that mean it is still computing whether the branch merges. */
  private static final Set<String> GITLAB_CHECKING = Set.of("unchecked", "checking", "preparing",
    "cannot_be_merged_recheck");

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
    return new PullRequestStatus(Forge.GITHUB, (int) Json.number(view, "number"), url, text(view, "title"),
      text(view, "state"), text(view, "headRefOid"), text(view, "baseRefName"), text(view, "mergeable"),
      checks(view), reviews(view), comments(commentsJson));
  }

  /**
   * A GitLab merge request. Approvals and requested changes come from the system notes, other notes outside the diff
   * are reviews with a body, and notes on the diff are review comments.
   *
   * @param mergeRequestJson the REST merge request, with its {@code head_pipeline}
   * @param jobsJson         the REST array of the head pipeline's jobs; null without a pipeline
   * @param notesJson        the REST array of the newest notes, newest first
   * @return null when the answer is not a merge request
   */
  public static @Nullable PullRequestStatus parseGitLab(@Nullable String mergeRequestJson, @Nullable String jobsJson,
    @Nullable String notesJson) {
    JsonObject request = Json.parseObject(mergeRequestJson);
    String url = Json.string(request, "web_url");
    if (url == null) {
      return null;
    }
    String state = switch (text(request, "state")) {
      case "opened" -> "OPEN";
      case "merged" -> "MERGED";
      default -> "CLOSED";
    };
    String mergeable;
    if (GITLAB_CHECKING.contains(text(request, "merge_status"))
      || GITLAB_CHECKING.contains(text(request, "detailed_merge_status"))) {
      mergeable = "UNKNOWN";
    } else {
      boolean isConflicting = Json.bool(request, "has_conflicts")
        || "conflict".equals(text(request, "detailed_merge_status"));
      mergeable = isConflicting ? "CONFLICTING" : "MERGEABLE";
    }
    List<Review> reviews = new ArrayList<>();
    List<Comment> comments = new ArrayList<>();
    notes(url, parseArray(notesJson), reviews, comments);
    return new PullRequestStatus(Forge.GITLAB, (int) Json.number(request, "iid"), url, text(request, "title"), state,
      text(request, "sha"), text(request, "target_branch"), mergeable, jobs(parseArray(jobsJson)), reviews, comments);
  }

  /**
   * The head pipeline, whose jobs are the checks of the merge request's head, or null without one. A pipeline of an
   * older commit does not count; one on the merge ref tests the head merged into its target.
   */
  public static @Nullable Pipeline headPipeline(@Nullable String mergeRequestJson) {
    JsonObject request = Json.parseObject(mergeRequestJson);
    JsonObject pipeline = Json.object(request, "head_pipeline");
    Long id = Json.optionalNumber(pipeline, "id");
    Long project = Json.optionalNumber(pipeline, "project_id");
    if (id == null || project == null) {
      return null;
    }
    boolean isHead = text(pipeline, "sha").equals(text(request, "sha")) || text(pipeline, "ref").endsWith("/merge");
    return isHead ? new Pipeline(project, id) : null;
  }

  public boolean isOpen() {
    return "OPEN".equals(this.state);
  }

  /** {@code #12} or {@code !7}, as the header shows it. */
  public String label() {
    return this.forge.sign() + String.valueOf(this.number);
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

  /** GitLab lists only each job's latest run. A failure that may fail does not count, as GitLab merges anyway. */
  private static List<Check> jobs(@Nullable JsonArray array) {
    List<Check> checks = new ArrayList<>();
    for (JsonObject job : objects(array)) {
      String status = text(job, "status");
      Outcome outcome;
      if (GITLAB_RUNNING.contains(status)) {
        outcome = Outcome.PENDING;
      } else if ("canceled".equals(status) || "failed".equals(status) && !Json.bool(job, "allow_failure")) {
        outcome = Outcome.FAILED;
      } else {
        outcome = Outcome.PASSED;
      }
      checks.add(new Check(text(job, "name"), Json.string(job, "web_url"), outcome));
    }
    return checks;
  }

  /** Splits the notes, newest first, into reviews, oldest first, and comments on the diff. */
  private static void notes(String url, @Nullable JsonArray array, List<Review> reviews, List<Comment> comments) {
    for (JsonObject note : objects(array)) {
      Long id = Json.optionalNumber(note, "id");
      if (id == null) {
        continue;
      }
      String author = text(Json.object(note, "author"), "username");
      String body = text(note, "body");
      if (Json.bool(note, "system")) {
        if (body.startsWith("approved this merge request")) {
          reviews.addFirst(new Review(String.valueOf(id), author, "APPROVED", ""));
        } else if (body.startsWith("requested changes")) {
          reviews.addFirst(new Review(String.valueOf(id), author, "CHANGES_REQUESTED", ""));
        }
        continue;
      }
      JsonObject position = Json.object(note, "position");
      if (position == null) {
        reviews.addFirst(new Review(String.valueOf(id), author, "COMMENTED", body));
        continue;
      }
      String path = Json.string(position, "new_path");
      Integer line = optionalInt(position, "new_line");
      comments.add(new Comment(id, author, path != null ? path : text(position, "old_path"),
        line != null ? line : optionalInt(position, "old_line"), null, body, url + "#note_" + id));
    }
  }

  private static List<JsonObject> objects(@Nullable JsonArray array) {
    List<JsonObject> objects = new ArrayList<>();
    if (array != null) {
      for (JsonElement element : array) {
        if (element.isJsonObject()) {
          objects.add(element.getAsJsonObject());
        }
      }
    }
    return objects;
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

  /** A GitLab pipeline. @param project its project's id, which differs for a fork's merge request */
  public record Pipeline(
    long project,
    long id
  ) {
    /** The REST path of its jobs, each with its latest run. */
    public String jobsPath() {
      return "projects/" + this.project + "/pipelines/" + this.id + "/jobs?per_page=100";
    }
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
