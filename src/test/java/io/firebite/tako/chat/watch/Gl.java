package io.firebite.tako.chat.watch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Hand-built glab api output in GitLab's shapes for the watch tests. */
final class Gl {
  static final String URL = "https://git.example.com/group/sub/app/-/merge_requests/7";
  static final String API = "projects/group%2Fsub%2Fapp/merge_requests/7";

  private Gl() {
  }

  /** A file under {@code src/test/resources/gl}. */
  static String recorded(String name) {
    try (InputStream stream = Gl.class.getClassLoader().getResourceAsStream("gl/" + name)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static PullRequestStatus status() {
    return PullRequestStatus.parseGitLab(recorded("merge-request.json"), recorded("jobs.json"),
      recorded("notes.json"));
  }

  /** The recorded notes after newer ones, newest first as GitLab lists them. */
  static String notesAfter(String... newer) {
    String notes = recorded("notes.json");
    return "[" + String.join(",", newer) + "," + notes.substring(notes.indexOf('[') + 1);
  }

  static String note(long id, String author, boolean system, String body) {
    return "{\"id\": " + id + ", \"type\": null, \"system\": " + system + ", \"body\": \"" + body + "\", "
      + "\"author\": {\"username\": \"" + author + "\"}}";
  }
}
