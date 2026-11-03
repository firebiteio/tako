package io.firebite.tako.chat.watch;

import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.json.Json;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a pull or merge request with its forge's CLI: on GitHub {@code gh pr view} and the review comments, on GitLab
 * the merge request, its head pipeline's jobs and its notes.
 */
final class PullRequestReader {
  private static final Logger LOG = Logger.getInstance(PullRequestReader.class);

  private final ForgeCall.Runner runner;

  PullRequestReader(ForgeCall.Runner runner) {
    this.runner = runner;
  }

  /** The request as it is now, or why it could not be read. */
  Read read(Path folder, PullRequestUrl url) {
    return switch (url.forge()) {
      case GITHUB -> this.readGitHub(folder, url);
      case GITLAB -> this.readGitLab(folder, url);
    };
  }

  /** The login of the CLI's account on the request's host, as the output of a successful call. */
  ForgeCall.Result self(Path folder, PullRequestUrl url) {
    return switch (url.forge()) {
      case GITHUB -> this.run(folder, url, List.of("api", "--hostname", url.host(), "user", "--jq", ".login"));
      case GITLAB -> {
        ForgeCall.Result user = this.run(folder, url, List.of("api", "--hostname", url.host(), "user"));
        String login = user.exitCode() == 0 ? Json.string(Json.parseObject(user.out()), "username") : null;
        yield user.exitCode() != 0 || login == null ? user : new ForgeCall.Result(0, login, "");
      }
    };
  }

  /** A failed comment read leaves them unknown, as the view holds what matters most. */
  private Read readGitHub(Path folder, PullRequestUrl url) {
    ForgeCall.Result view = this.run(folder, url,
      List.of("pr", "view", url.webUrl(), "--json", PullRequestStatus.VIEW_FIELDS));
    ForgeCall.Failure failure = ForgeCall.classify(view);
    if (failure != null) {
      return Read.failed(failure);
    }
    ForgeCall.Result comments = this.run(folder, url, List.of("api", "--hostname", url.host(), url.commentsPath()));
    boolean commentsRead = ForgeCall.classify(comments) == null;
    if (!commentsRead) {
      LOG.debug("Could not read the review comments of " + url.webUrl() + ": " + comments.err());
    }
    return of(url, PullRequestStatus.parse(view.out(), commentsRead ? comments.out() : null), commentsRead);
  }

  /** Every call must answer: approvals come with the notes, so a read without them would tell old ones as new. */
  private Read readGitLab(Path folder, PullRequestUrl url) {
    ForgeCall.Result request = this.run(folder, url, List.of("api", "--hostname", url.host(), url.apiPath()));
    ForgeCall.Failure failure = ForgeCall.classify(request);
    if (failure != null) {
      return Read.failed(failure);
    }
    String jobs = null;
    PullRequestStatus.Pipeline pipeline = PullRequestStatus.headPipeline(request.out());
    if (pipeline != null) {
      ForgeCall.Result read = this.run(folder, url, List.of("api", "--hostname", url.host(), pipeline.jobsPath()));
      failure = ForgeCall.classify(read);
      if (failure != null) {
        return Read.failed(failure);
      }
      jobs = read.out();
    }
    ForgeCall.Result notes = this.run(folder, url, List.of("api", "--hostname", url.host(), url.commentsPath()));
    failure = ForgeCall.classify(notes);
    if (failure != null) {
      return Read.failed(failure);
    }
    return of(url, PullRequestStatus.parseGitLab(request.out(), jobs, notes.out()), true);
  }

  private ForgeCall.Result run(Path folder, PullRequestUrl url, List<String> args) {
    return this.runner.run(url.forge(), folder, args);
  }

  private static Read of(PullRequestUrl url, @Nullable PullRequestStatus status, boolean commentsRead) {
    if (status == null) {
      return Read.failed(new ForgeCall.Failure(ForgeCall.Kind.OTHER, url.forge().cli() + " gave no pull request for "
        + url.webUrl()));
    }
    return new Read(null, status, commentsRead);
  }

  /**
   * @param failure      why it could not be read, null when it was
   * @param status       the request, null when it could not be read
   * @param commentsRead whether {@code status} holds the review comments
   */
  record Read(
    @Nullable ForgeCall.Failure failure,
    @Nullable PullRequestStatus status,
    boolean commentsRead
  ) {
    static Read failed(ForgeCall.Failure failure) {
      return new Read(failure, null, false);
    }
  }
}
