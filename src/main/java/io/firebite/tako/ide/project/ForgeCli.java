package io.firebite.tako.ide.project;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Pull or merge requests on a forge through its CLI, {@code gh} or {@code glab}. An interface, so tests need no real
 * remote.
 */
public interface ForgeCli {
  /** The CLI of the forge. */
  static ForgeCli of(Forge forge) {
    return switch (forge) {
      case GITHUB -> GitHubCli.INSTANCE;
      case GITLAB -> GitLabCli.INSTANCE;
    };
  }

  /** Whether the CLI is installed and logged in to the host. Asks the host, so call off the EDT. */
  State state(String host, Map<String, String> environment);

  /** The repository's default branch, which pull requests go into. */
  String defaultBranch(Path directory, Map<String, String> environment, GitCommands.HostedRepo repository,
    BooleanSupplier cancelled) throws GitCommands.CommandException;

  /** Opens a pull or merge request from {@code head} into {@code base}. */
  GitInfo.PullRequest create(Path directory, Map<String, String> environment, GitCommands.HostedRepo repository,
    String head, String base, String title, String body, BooleanSupplier cancelled)
    throws GitCommands.CommandException;

  enum State {
    READY,
    MISSING,
    LOGGED_OUT
  }
}
