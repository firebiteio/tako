package io.firebite.tako.ide.project;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * The branch a working directory is on, the linked worktree it is in and the pull or merge request open for the
 * branch. The worktree is null for the main checkout.
 */
public record GitInfo(
  @Nullable String branch,
  @Nullable String worktree,
  @Nullable PullRequest pullRequest
) {
  private static final Logger LOG = Logger.getInstance(GitInfo.class);
  private static final long TIMEOUT_SECONDS = 8;

  /** Branch and worktree only, read locally without network. Works before the first commit. */
  public static GitInfo local(Path directory, Map<String, String> environment) {
    String locations = run(directory, environment,
      List.of("git", "rev-parse", "--git-dir", "--git-common-dir", "--show-toplevel"));
    String[] lines = locations == null ? new String[0] : locations.trim().split("\\R");
    if (lines.length != 3) {
      return new GitInfo(null, null, null);
    }
    String worktree = worktreeName(directory, lines[0], lines[1], lines[2]);
    String branch = run(directory, environment, List.of("git", "symbolic-ref", "--short", "-q", "HEAD"));
    if (branch == null || branch.isBlank()) {
      branch = run(directory, environment, List.of("git", "rev-parse", "--short", "HEAD"));
    }
    return new GitInfo(branch == null || branch.isBlank() ? null : branch.trim(), worktree, null);
  }

  /**
   * The open pull or merge request of the checked-out branch. Asks {@code gh} or {@code glab} over the network. When
   * the branch pushes to a GitHub remote other than {@code origin}, also looks in that repository, where the thread's
   * git actions open pull requests.
   */
  public static @Nullable PullRequest pullRequest(Path directory, Map<String, String> environment) {
    String remote = run(directory, environment, List.of("git", "remote", "get-url", "origin"));
    String host = remote == null ? "" : remote.toLowerCase(Locale.ROOT);
    if (host.contains("gitlab")) {
      return parseGitLab(run(directory, environment, List.of("glab", "mr", "view", "-F", "json")));
    }
    if (host.contains("github")) {
      PullRequest pullRequest = parseGitHub(run(directory, environment, List.of("gh", "pr", "view", "--json",
        "number,title,url,state")));
      if (pullRequest != null) {
        return pullRequest;
      }
    }
    GitCommands.Status status = GitCommands.status(directory, environment);
    GitCommands.GitHubRepo pushed = status.gitHub();
    if (pushed == null || status.branch() == null || "origin".equals(status.remote())) {
      return null;
    }
    return parseGitHub(run(directory, environment, List.of("gh", "pr", "view", status.branch(), "--repo",
      pushed.host() + "/" + pushed.owner() + "/" + pushed.name(), "--json", "number,title,url,state")));
  }

  /**
   * The pull or merge request as it is now, asked by its url or number, so it is found after its branch was deleted or
   * switched. Null when {@code gh} or {@code glab} cannot tell. Asks over the network.
   *
   * @param directory a folder of the repository, for {@code glab} to know the project
   */
  public static @Nullable PullRequest pullRequest(Path directory, Map<String, String> environment,
    PullRequest known) {
    List<String> command = viewCommand(known);
    if (command == null) {
      return null;
    }
    String output = run(directory, environment, command);
    return known.number().startsWith("!") ? parseGitLab(output) : parseGitHub(output);
  }

  /** The command that shows the known pull or merge request, or null when its number is not one Tako wrote. */
  static @Nullable List<String> viewCommand(PullRequest known) {
    String number = known.number();
    if (number.length() < 2) {
      return null;
    }
    String id = number.substring(1);
    for (int i = 0; i < id.length(); i++) {
      if (!Character.isDigit(id.charAt(i))) {
        return null;
      }
    }
    return switch (number.charAt(0)) {
      case '#' -> known.url().startsWith("https://") || known.url().startsWith("http://")
        ? List.of("gh", "pr", "view", known.url(), "--json", "number,title,url,state")
        : null;
      case '!' -> List.of("glab", "mr", "view", id, "-F", "json");
      default -> null;
    };
  }

  /** The worktree's folder name when {@code gitDir} is a linked worktree's, else null. */
  static @Nullable String worktreeName(Path directory, String gitDir, String commonDir, String topLevel) {
    Path git = directory.resolve(gitDir.trim()).normalize();
    Path common = directory.resolve(commonDir.trim()).normalize();
    if (git.equals(common)) {
      return null;
    }
    Path top = Path.of(topLevel.trim()).getFileName();
    return top == null ? null : top.toString();
  }

  static @Nullable PullRequest parseGitHub(@Nullable String output) {
    JsonObject json = Json.parseObject(output);
    String url = Json.string(json, "url");
    if (url == null) {
      return null;
    }
    return new PullRequest("#" + Json.string(json, "number"), String.valueOf(Json.string(json, "title")), url,
      String.valueOf(Json.string(json, "state")).toLowerCase(Locale.ROOT));
  }

  static @Nullable PullRequest parseGitLab(@Nullable String output) {
    JsonObject json = Json.parseObject(output);
    String url = Json.string(json, "web_url");
    if (url == null) {
      return null;
    }
    String state = String.valueOf(Json.string(json, "state")).toLowerCase(Locale.ROOT);
    return new PullRequest("!" + Json.string(json, "iid"), String.valueOf(Json.string(json, "title")), url,
      "opened".equals(state) ? "open" : state);
  }

  private static @Nullable String run(Path directory, Map<String, String> environment, List<String> command) {
    return run(directory, environment, command, TIMEOUT_SECONDS);
  }

  /** The command's output, or null when it fails or takes longer than {@code timeoutSeconds}. */
  static @Nullable String run(Path directory, Map<String, String> environment, List<String> command,
    long timeoutSeconds) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.environment().put("GH_PROMPT_DISABLED", "1");
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Path output = null;
    try {
      output = Files.createTempFile("tako-git", ".out");
      builder.redirectOutput(output.toFile());
      Process process = builder.start();
      process.getOutputStream().close();
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      return process.exitValue() == 0 ? Files.readString(output, StandardCharsets.UTF_8) : null;
    } catch (IOException e) {
      LOG.debug("Could not run " + command.get(0), e);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      if (output != null) {
        try {
          Files.deleteIfExists(output);
        } catch (IOException ignored) {
        }
      }
    }
  }

  public record PullRequest(
    String number,
    String title,
    String url,
    String state
  ) {
    /** Whether {@code gh} found it. GitLab merge requests are numbered {@code !7}. */
    public boolean isGitHub() {
      return this.number.startsWith("#");
    }
  }
}
