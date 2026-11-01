package io.firebite.tako.chat.watch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Recorded and hand-built gh output for the watch tests. */
final class Gh {
  static final String URL = "https://github.com/firebiteio/tako/pull/12";
  static final String SHA_A = "aaaaaaa1111111111111111111111111111111111";
  static final String SHA_B = "bbbbbbb2222222222222222222222222222222222";
  private static final Gson GSON = new Gson();

  private Gh() {
  }

  /** A file under {@code src/test/resources/gh}. */
  static String recorded(String name) {
    try (InputStream stream = Gh.class.getClassLoader().getResourceAsStream("gh/" + name)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static PullRequestStatus status(String view, String comments) {
    return PullRequestStatus.parse(view, comments);
  }

  static String view(String state, String sha, String mergeable, List<JsonObject> checks, List<JsonObject> reviews) {
    JsonObject view = new JsonObject();
    view.addProperty("number", 12);
    view.addProperty("url", URL);
    view.addProperty("title", "Watch");
    view.addProperty("state", state);
    view.addProperty("headRefOid", sha);
    view.addProperty("baseRefName", "master");
    view.addProperty("mergeable", mergeable);
    JsonArray rollup = new JsonArray();
    for (JsonObject check : checks) {
      rollup.add(check);
    }
    view.add("statusCheckRollup", rollup);
    JsonArray reviewArray = new JsonArray();
    for (JsonObject review : reviews) {
      reviewArray.add(review);
    }
    view.add("reviews", reviewArray);
    return GSON.toJson(view);
  }

  static String open(String sha, JsonObject... checks) {
    return view("OPEN", sha, "MERGEABLE", List.of(checks), List.of());
  }

  /** @param conclusion empty while the run is not done */
  static JsonObject run(String name, String conclusion) {
    JsonObject run = new JsonObject();
    run.addProperty("__typename", "CheckRun");
    run.addProperty("workflowName", "CI");
    run.addProperty("name", name);
    run.addProperty("status", conclusion.isEmpty() ? "IN_PROGRESS" : "COMPLETED");
    run.addProperty("conclusion", conclusion);
    run.addProperty("detailsUrl", "https://github.com/firebiteio/tako/actions/runs/111/job/222");
    run.addProperty("startedAt", "2026-10-30T09:01:00Z");
    return run;
  }

  static JsonObject review(String id, String author, String state, String body) {
    JsonObject review = new JsonObject();
    review.addProperty("id", id);
    JsonObject login = new JsonObject();
    login.addProperty("login", author);
    review.add("author", login);
    review.addProperty("state", state);
    review.addProperty("body", body);
    return review;
  }

  static JsonObject comment(long id, String author, String body) {
    JsonObject comment = new JsonObject();
    comment.addProperty("id", id);
    JsonObject user = new JsonObject();
    user.addProperty("login", author);
    comment.add("user", user);
    comment.addProperty("path", "src/Foo.java");
    comment.addProperty("line", 3);
    comment.addProperty("original_line", 3);
    comment.addProperty("body", body);
    comment.addProperty("html_url", URL + "#discussion_r" + id);
    return comment;
  }

  static String comments(JsonObject... comments) {
    JsonArray array = new JsonArray();
    for (JsonObject comment : comments) {
      array.add(comment);
    }
    return GSON.toJson(array);
  }
}
