package io.firebite.tako.chat.watch;

import io.firebite.tako.ide.project.Forge;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Where a GitHub pull request or GitLab merge request lives, read from its web address. Host and path are lowercase, so
 * two addresses of the same request are equal.
 *
 * @param path {@code owner/repo} on GitHub, {@code group/subgroup/project} on GitLab
 */
public record PullRequestUrl(
  Forge forge,
  String host,
  String path,
  int number
) {
  private static final Pattern GITHUB = Pattern
    .compile("https?://([^/\\s]+)/([^/\\s]+/[^/\\s]+)/pull/(\\d{1,9})(?:[/?#].*)?");
  private static final Pattern GITLAB = Pattern
    .compile("https?://([^/\\s]+)/([^/\\s]+(?:/[^/\\s]+)+)/-/merge_requests/(\\d{1,9})(?:[/?#].*)?");

  /**
   * The request of {@code https://<host>/<owner>/<repo>/pull/<n>} or {@code https://<host>/<path>/-/merge_requests/<n>},
   * also with a tail like {@code /files} or {@code /diffs}.
   */
  public static @Nullable PullRequestUrl parse(@Nullable String url) {
    if (url == null) {
      return null;
    }
    String text = url.trim();
    Matcher gitLab = GITLAB.matcher(text);
    if (gitLab.matches()) {
      return of(Forge.GITLAB, gitLab);
    }
    Matcher gitHub = GITHUB.matcher(text);
    return gitHub.matches() ? of(Forge.GITHUB, gitHub) : null;
  }

  private static @Nullable PullRequestUrl of(Forge forge, Matcher matcher) {
    int number = Integer.parseInt(matcher.group(3));
    if (number <= 0) {
      return null;
    }
    return new PullRequestUrl(forge, matcher.group(1).toLowerCase(Locale.ROOT),
      matcher.group(2).toLowerCase(Locale.ROOT), number);
  }

  /** {@code host/path}, as {@code gh -R} and {@code glab -R} take it. */
  public String repository() {
    return this.host + "/" + this.path;
  }

  /** {@code #12} or {@code !7}, as the header shows it. */
  public String label() {
    return this.forge.sign() + String.valueOf(this.number);
  }

  public String webUrl() {
    return switch (this.forge) {
      case GITHUB -> "https://" + this.host + "/" + this.path + "/pull/" + this.number;
      case GITLAB -> "https://" + this.host + "/" + this.path + "/-/merge_requests/" + this.number;
    };
  }

  /** The REST path of the request itself. */
  public String apiPath() {
    return switch (this.forge) {
      case GITHUB -> "repos/" + this.path + "/pulls/" + this.number;
      case GITLAB -> "projects/" + URLEncoder.encode(this.path, StandardCharsets.UTF_8) + "/merge_requests/"
        + this.number;
    };
  }

  /** The REST path of the review comments, on GitLab of all notes, all of them when paginated. */
  public String allCommentsPath() {
    return switch (this.forge) {
      case GITHUB -> this.apiPath() + "/comments";
      case GITLAB -> this.apiPath() + "/notes";
    };
  }

  /** The REST path of the newest 100 review comments, on GitLab notes, newest first. */
  public String commentsPath() {
    return switch (this.forge) {
      case GITHUB ->
        this.allCommentsPath() + "?sort=created&direction=desc&per_page=" + PullRequestWatch.COMMENT_WINDOW;
      case GITLAB -> this.allCommentsPath() + "?sort=desc&order_by=created_at&per_page="
        + PullRequestWatch.COMMENT_WINDOW;
    };
  }
}
