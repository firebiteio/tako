package io.firebite.tako.ide.project;

import com.google.gson.JsonObject;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/** Pull requests on GitHub through the GitHub CLI. An interface, so tests need no real remote. */
public interface GitHubCli {
  GitHubCli GH = new Gh();

  /** Whether {@code gh} is installed and logged in to the host. Asks the host, so call off the EDT. */
  State state(String host, Map<String, String> environment);

  /** The repository's default branch, which pull requests go into. */
  String defaultBranch(Path directory, Map<String, String> environment, GitCommands.GitHubRepo repository,
    BooleanSupplier cancelled) throws GitCommands.CommandException;

  /** Opens a pull request from {@code head} into {@code base}. */
  GitInfo.PullRequest create(Path directory, Map<String, String> environment, GitCommands.GitHubRepo repository,
    String head, String base, String title, String body, BooleanSupplier cancelled)
    throws GitCommands.CommandException;

  /** The pull request GitHub answered a create request with, numbered like {@link GitInfo#parseGitHub}. */
  static @Nullable GitInfo.PullRequest parseCreated(@Nullable String output) {
    JsonObject json = Json.parseObject(output);
    String url = Json.string(json, "html_url");
    String number = Json.string(json, "number");
    if (url == null || number == null) {
      return null;
    }
    String state = Json.string(json, "state");
    return new GitInfo.PullRequest("#" + number, String.valueOf(Json.string(json, "title")), url,
      state == null ? "open" : state.toLowerCase(Locale.ROOT));
  }

  /** Why GitHub refused, from the error body {@code gh api} prints, else gh's own error output. */
  static String parseError(String stdout, String stderr) {
    JsonObject json = Json.parseObject(stdout);
    List<String> reasons = new ArrayList<>();
    for (JsonObject error : Json.objects(json, "errors")) {
      String message = Json.string(error, "message");
      if (message == null) {
        String code = Json.string(error, "code");
        String field = Json.string(error, "field");
        message = code == null ? null : field == null ? code : code + " " + field;
      }
      if (message != null && !message.isBlank()) {
        reasons.add(message.strip());
      }
    }
    if (!reasons.isEmpty()) {
      return String.join("\n", reasons);
    }
    String message = Json.string(json, "message");
    if (message != null && !message.isBlank()) {
      return message.strip();
    }
    String error = stderr.strip();
    return error.isEmpty() ? stdout.strip() : error;
  }

  enum State {
    READY,
    MISSING,
    LOGGED_OUT
  }

  /** Runs {@code gh}. Texts go in a JSON file, so quotes, line breaks and any script survive the command line. */
  final class Gh implements GitHubCli {
    private Gh() {
    }

    @Override
    public State state(String host, Map<String, String> environment) {
      Path home = Path.of(System.getProperty("user.home"));
      try {
        if (run(home, environment, GitCommands.NEVER_CANCELLED, List.of("--version")).exitCode() != 0) {
          return State.MISSING;
        }
        GitCommands.Output active = run(home, environment, GitCommands.NEVER_CANCELLED,
          List.of("auth", "status", "--active", "--hostname", host));
        if (active.exitCode() != 0 && active.stderr().contains("unknown flag")) {
          active = run(home, environment, GitCommands.NEVER_CANCELLED, List.of("auth", "status", "--hostname", host));
        }
        return active.exitCode() == 0 ? State.READY : State.LOGGED_OUT;
      } catch (GitCommands.CommandException e) {
        return e.kind() == GitCommands.CommandException.Kind.FAILED ? State.MISSING : State.LOGGED_OUT;
      }
    }

    @Override
    public String defaultBranch(Path directory, Map<String, String> environment, GitCommands.GitHubRepo repository,
      BooleanSupplier cancelled) throws GitCommands.CommandException {
      GitCommands.Output output = run(directory, environment, cancelled,
        List.of("api", "--hostname", repository.host(), repository.apiPath(), "--jq", ".default_branch"));
      String branch = output.stdout().strip();
      if (output.exitCode() != 0 || branch.isEmpty()) {
        throw new GitCommands.CommandException(GitCommands.CommandException.Kind.FAILED,
          parseError(output.stdout(), output.stderr()));
      }
      return branch;
    }

    @Override
    public GitInfo.PullRequest create(Path directory, Map<String, String> environment,
      GitCommands.GitHubRepo repository, String head, String base, String title, String body,
      BooleanSupplier cancelled) throws GitCommands.CommandException {
      JsonObject request = new JsonObject();
      request.addProperty("title", title);
      request.addProperty("head", head);
      request.addProperty("base", base);
      request.addProperty("body", body);
      Path input;
      try {
        input = Files.createTempFile("tako-pull-request", ".json");
        Files.writeString(input, request.toString(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new GitCommands.CommandException(GitCommands.CommandException.Kind.FAILED,
          String.valueOf(e.getMessage()));
      }
      try {
        GitCommands.Output output = run(directory, environment, cancelled, List.of("api", "--hostname",
          repository.host(), "-X", "POST", repository.apiPath() + "/pulls", "--input", input.toString()));
        GitInfo.PullRequest created = output.exitCode() == 0 ? parseCreated(output.stdout()) : null;
        if (created == null) {
          throw new GitCommands.CommandException(GitCommands.CommandException.Kind.FAILED,
            parseError(output.stdout(), output.stderr()));
        }
        return created;
      } finally {
        try {
          Files.deleteIfExists(input);
        } catch (IOException ignored) {
        }
      }
    }

    private static GitCommands.Output run(Path directory, Map<String, String> environment, BooleanSupplier cancelled,
      List<String> arguments) throws GitCommands.CommandException {
      List<String> command = new ArrayList<>(List.of("gh"));
      command.addAll(arguments);
      return GitCommands.exec(command, directory, environment, GitCommands.GH_TIMEOUT_SECONDS, cancelled);
    }
  }
}
