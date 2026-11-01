package io.firebite.tako.chat.watch;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Where a GitHub pull request lives, read from its web address. Host, owner and repository are lowercase, so two
 * addresses of the same pull request are equal.
 */
public record PullRequestUrl(
  String host,
  String owner,
  String repo,
  int number
) {
  private static final Pattern URL = Pattern
    .compile("https?://([^/\\s]+)/([^/\\s]+)/([^/\\s]+)/pull/(\\d{1,9})(?:[/?#].*)?");

  /** The pull request of {@code https://<host>/<owner>/<repo>/pull/<n>}, also with a tail like {@code /files}. */
  public static @Nullable PullRequestUrl parse(@Nullable String url) {
    if (url == null) {
      return null;
    }
    Matcher matcher = URL.matcher(url.trim());
    if (!matcher.matches()) {
      return null;
    }
    int number = Integer.parseInt(matcher.group(4));
    if (number <= 0) {
      return null;
    }
    return new PullRequestUrl(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).toLowerCase(Locale.ROOT),
      matcher.group(3).toLowerCase(Locale.ROOT), number);
  }

  /** {@code host/owner/repo}, as {@code gh -R} takes it. */
  public String repository() {
    return this.host + "/" + this.owner + "/" + this.repo;
  }

  /** The REST path of the pull request's review comments, newest first. */
  public String commentsPath() {
    return "repos/" + this.owner + "/" + this.repo + "/pulls/" + this.number
      + "/comments?sort=created&direction=desc&per_page=100";
  }
}
