package io.firebite.tako.chat.watch;

import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.project.Forge;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** The prompt that wakes a thread's agent with what happened on its watched pull request. */
public final class WakePrompt {
  static final int LISTED = 10;
  static final int BODY_LENGTH = 600;
  private static final int SHORT_SHA = 7;
  private static final Pattern RUN = Pattern.compile("/actions/runs/(\\d+)");
  private static final Pattern JOB = Pattern.compile("/-/jobs/(\\d+)");
  private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private WakePrompt() {
  }

  /** @param watch the watch before the update, which names the pull request */
  public static String compose(PullRequestWatch watch, PullRequestWatch.Update update) {
    PullRequestUrl url = PullRequestUrl.parse(watch.url);
    boolean isGitLab = url != null && url.forge() == Forge.GITLAB;
    String noun = isGitLab ? "merge request" : "pull request";
    StringBuilder text = new StringBuilder("Update on ").append(noun).append(' ').append(watch.number).append(" (")
      .append(watch.url).append("), which Tako is watching for you:\n");
    List<String> remarks = new ArrayList<>();
    for (WatchEvent event : update.events()) {
      switch (event) {
        case WatchEvent.ChecksFailed failed -> appendFailed(text, failed, url);
        case WatchEvent.ChecksPassed passed -> text.append("- All ").append(passed.count())
          .append(passed.count() == 1 ? " check" : " checks").append(" passed").append(on(passed.headSha()))
          .append(".\n");
        case WatchEvent.Reviewed reviewed -> remarks.add(review(reviewed.review(), isGitLab));
        case WatchEvent.Commented commented -> remarks.add(comment(commented.comment()));
        case WatchEvent.Conflicting conflicting -> text.append("- The branch now has merge conflicts with ")
          .append(conflicting.baseBranch().isEmpty() ? "its base branch" : conflicting.baseBranch()).append(".\n");
        case WatchEvent.Merged merged -> text.append("- The ").append(noun)
          .append(" was merged. Tako stopped watching it.\n");
        case WatchEvent.Closed closed -> text.append("- The ").append(noun)
          .append(" was closed. Tako stopped watching it.\n");
      }
    }
    String readAll;
    if (url == null) {
      readAll = "";
    } else if (isGitLab) {
      readAll = "; read them all with glab api --paginate --hostname " + url.host() + " " + url.allCommentsPath();
    } else {
      readAll = "; read them all with gh api --paginate --hostname " + url.host() + " " + url.allCommentsPath()
        + " and gh pr view " + url.number() + " -R " + url.repository() + " --json reviews";
    }
    appendListed(text, remarks, "- ", readAll);
    if (update.end() == PullRequestWatch.End.COMMENT_LOOP) {
      text.append("\nTako stopped watching after ").append(PullRequestWatch.COMMENT_WAKE_LIMIT)
        .append(" comment-only updates in a row. Call watch_pull_request to watch it again.");
    } else if (update.end() == null) {
      text.append("\nLook into each item and act on it as your task requires. End your turn when you are done; Tako ")
        .append("wakes you on the next change. When you hand the work back to the person, stop the watch with ")
        .append("unwatch_pull_request, or ask them to click the eye in Tako's header.");
    }
    return text.toString().stripTrailing();
  }

  private static void appendFailed(StringBuilder text, WatchEvent.ChecksFailed failed, @Nullable PullRequestUrl url) {
    text.append("- Checks failed").append(on(failed.headSha())).append(":\n");
    List<String> lines = new ArrayList<>();
    for (PullRequestStatus.Check check : failed.failed()) {
      StringBuilder line = new StringBuilder(check.name());
      if (check.url() != null && !check.url().isBlank()) {
        line.append(' ').append(check.url()).append(logs(check.url(), url));
      }
      lines.add(line.toString());
    }
    String rest;
    if (url == null) {
      rest = "";
    } else if (url.forge() == Forge.GITLAB) {
      rest = "; see " + url.webUrl() + "/pipelines";
    } else {
      rest = "; see gh pr checks " + url.number() + " -R " + url.repository();
    }
    appendListed(text, lines, "  - ", rest);
  }

  /** How the agent reads a failed check's log, empty when its address names no run or job. */
  private static String logs(String checkUrl, @Nullable PullRequestUrl url) {
    if (url == null) {
      return "";
    }
    Matcher matcher = (url.forge() == Forge.GITLAB ? JOB : RUN).matcher(checkUrl);
    if (!matcher.find()) {
      return "";
    }
    return switch (url.forge()) {
      case GITHUB -> " (logs: gh run view " + matcher.group(1) + " -R " + url.repository() + " --log-failed)";
      case GITLAB -> " (logs: glab ci trace " + matcher.group(1) + " -R " + url.repository() + ")";
    };
  }

  /** @param where how the agent reads the items left out, after the count of them */
  private static void appendListed(StringBuilder text, List<String> lines, String bullet, String where) {
    for (int i = 0; i < lines.size() && i < LISTED; i++) {
      text.append(bullet).append(lines.get(i)).append('\n');
    }
    if (lines.size() > LISTED) {
      text.append(bullet).append("and ").append(lines.size() - LISTED).append(" more").append(where).append('\n');
    }
  }

  /** @param isGitLab whether it is a GitLab note, which reads as a comment unless it approves or requests changes */
  private static String review(PullRequestStatus.Review review, boolean isGitLab) {
    String state = switch (review.state()) {
      case "APPROVED" -> " (approved)";
      case "CHANGES_REQUESTED" -> " (changes requested)";
      default -> "";
    };
    String verb = isGitLab && state.isEmpty() ? " commented" : " reviewed";
    String body = body(review.body());
    return author(review.author()) + verb + state + (body.isEmpty() ? "" : ": \"" + body + "\"");
  }

  private static String comment(PullRequestStatus.Comment comment) {
    String where = comment.path();
    if (comment.line() != null) {
      where += ":" + comment.line();
    } else if (comment.originalLine() != null) {
      where += ":" + comment.originalLine() + " (outdated)";
    }
    return author(comment.author()) + " commented on " + where + ": \"" + body(comment.body()) + "\""
      + (comment.url().isEmpty() ? "" : " " + comment.url());
  }

  private static String author(String login) {
    return login.isEmpty() ? "Someone" : login;
  }

  /** The text on one line, without HTML comments, cut to {@link #BODY_LENGTH}. */
  static String body(String text) {
    String plain = WHITESPACE.matcher(HTML_COMMENT.matcher(text).replaceAll(" ")).replaceAll(" ").trim();
    return ChatController.cut(plain, BODY_LENGTH);
  }

  private static String on(String sha) {
    return sha.isEmpty() ? "" : " on " + sha.substring(0, Math.min(SHORT_SHA, sha.length()));
  }
}
