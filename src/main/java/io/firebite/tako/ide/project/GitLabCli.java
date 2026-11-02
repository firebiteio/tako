package io.firebite.tako.ide.project;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/** Merge requests on GitLab through {@code glab}. Texts go in a JSON file, so quotes, line breaks and any script survive. */
public final class GitLabCli implements ForgeCli {
  static final GitLabCli INSTANCE = new GitLabCli();

  private GitLabCli() {
  }

  /**
   * Why GitLab refused, from the error body {@code glab api} prints, else glab's own error output. GitLab's
   * {@code message} is a text, a list of texts or texts by field.
   */
  static String parseError(String stdout, String stderr) {
    JsonObject json = Json.parseObject(stdout);
    List<String> reasons = new ArrayList<>();
    if (json != null) {
      collect(json.get("message"), "", reasons);
      collect(json.get("error"), "", reasons);
    }
    if (!reasons.isEmpty()) {
      return String.join("\n", reasons);
    }
    String error = stderr.strip();
    return error.isEmpty() ? stdout.strip() : error;
  }

  private static void collect(@Nullable JsonElement element, String field, List<String> reasons) {
    if (element == null || element.isJsonNull()) {
      return;
    }
    if (element.isJsonPrimitive()) {
      String text = element.getAsString().strip();
      if (!text.isEmpty()) {
        reasons.add(field.isEmpty() ? text : field + " " + text);
      }
    } else if (element.isJsonArray()) {
      for (JsonElement item : element.getAsJsonArray()) {
        collect(item, field, reasons);
      }
    } else {
      for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
        collect(entry.getValue(), entry.getKey(), reasons);
      }
    }
  }

  @Override
  public State state(String host, Map<String, String> environment) {
    Path home = Path.of(System.getProperty("user.home"));
    try {
      if (run(home, environment, GitCommands.NEVER_CANCELLED, List.of("--version")).exitCode() != 0) {
        return State.MISSING;
      }
      GitCommands.Output status = run(home, environment, GitCommands.NEVER_CANCELLED,
        List.of("auth", "status", "--hostname", host));
      return status.exitCode() == 0 ? State.READY : State.LOGGED_OUT;
    } catch (GitCommands.CommandException e) {
      return e.kind() == GitCommands.CommandException.Kind.FAILED ? State.MISSING : State.LOGGED_OUT;
    }
  }

  @Override
  public String defaultBranch(Path directory, Map<String, String> environment, GitCommands.HostedRepo repository,
    BooleanSupplier cancelled) throws GitCommands.CommandException {
    GitCommands.Output output = run(directory, environment, cancelled,
      List.of("api", "--hostname", repository.host(), repository.apiPath()));
    String branch = output.exitCode() == 0 ? Json.string(Json.parseObject(output.stdout()), "default_branch") : null;
    if (branch == null || branch.isBlank()) {
      throw new GitCommands.CommandException(GitCommands.CommandException.Kind.FAILED,
        parseError(output.stdout(), output.stderr()));
    }
    return branch.strip();
  }

  @Override
  public GitInfo.PullRequest create(Path directory, Map<String, String> environment,
    GitCommands.HostedRepo repository, String head, String base, String title, String body,
    BooleanSupplier cancelled) throws GitCommands.CommandException {
    JsonObject request = new JsonObject();
    request.addProperty("title", title);
    request.addProperty("source_branch", head);
    request.addProperty("target_branch", base);
    request.addProperty("description", body);
    Path input;
    try {
      input = Files.createTempFile("tako-merge-request", ".json");
      Files.writeString(input, request.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GitCommands.CommandException(GitCommands.CommandException.Kind.FAILED,
        String.valueOf(e.getMessage()));
    }
    try {
      GitCommands.Output output = run(directory, environment, cancelled, List.of("api", "--hostname",
        repository.host(), "-X", "POST", "-H", "Content-Type: application/json",
        repository.apiPath() + "/merge_requests", "--input", input.toString()));
      GitInfo.PullRequest created = output.exitCode() == 0 ? GitInfo.parseGitLab(output.stdout()) : null;
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
    List<String> command = new ArrayList<>(List.of("glab"));
    command.addAll(arguments);
    return GitCommands.exec(command, directory, environment, GitCommands.FORGE_TIMEOUT_SECONDS, cancelled);
  }
}
