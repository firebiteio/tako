package io.firebite.tako.agent.claude;

import io.firebite.tako.TakoBundle;
import io.firebite.tako.settings.AuthMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** The command line and environment of one {@code claude} process. */
public record ClaudeLaunch(
  List<String> command,
  Map<String, String> environment
) {
  static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";

  /** Values of {@code --permission-mode}. The empty string keeps the CLI default. */
  public static final List<String> PERMISSION_MODES = List.of("", "manual", "acceptEdits", "plan", "auto", "dontAsk",
    "bypassPermissions");

  /** Values of {@code --effort}. The empty string keeps the CLI default. */
  public static final List<String> EFFORT_LEVELS = List.of("", "low", "medium", "high", "xhigh", "max");

  /** @throws IllegalStateException in API key mode without a key */
  public static ClaudeLaunch build(String executable, AuthMode authMode, @Nullable String apiKey, Options options,
    Map<String, String> baseEnvironment) {
    List<String> command = new ArrayList<>(List.of(executable, "-p",
      "--input-format", "stream-json",
      "--output-format", "stream-json",
      "--include-partial-messages",
      "--verbose",
      "--permission-prompt-tool", "stdio"));
    addFlag(command, "--model", options.model());
    addFlag(command, "--permission-mode", options.permissionMode());
    addFlag(command, "--effort", options.effort());
    for (String directory : options.additionalDirectories()) {
      addFlag(command, "--add-dir", directory);
    }
    addFlag(command, "--mcp-config", options.mcpConfig());
    addFlag(command, "--resume", options.resumeSessionId());

    Map<String, String> environment = new HashMap<>(baseEnvironment);
    if (authMode == AuthMode.API_KEY) {
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalStateException(TakoBundle.message("error.claude.no.api.key"));
      }
      environment.put(API_KEY_VARIABLE, apiKey);
    } else {
      environment.remove(API_KEY_VARIABLE);
    }
    return new ClaudeLaunch(List.copyOf(command), Map.copyOf(environment));
  }

  private static void addFlag(List<String> command, String flag, @Nullable String value) {
    if (value != null && !value.isBlank()) {
      command.add(flag);
      command.add(value);
    }
  }

  /**
   * What one thread asks of its process. Blank values keep the CLI's defaults.
   *
   * @param additionalDirectories folders besides the working directory Claude may read and edit
   * @param mcpConfig             a file of MCP servers to load besides the configured ones
   */
  public record Options(
    @Nullable String model,
    @Nullable String permissionMode,
    @Nullable String effort,
    @Nullable String resumeSessionId,
    List<String> additionalDirectories,
    @Nullable String mcpConfig
  ) {

    public static Options defaults() {
      return new Options(null, null, null, null, List.of(), null);
    }
  }
}
