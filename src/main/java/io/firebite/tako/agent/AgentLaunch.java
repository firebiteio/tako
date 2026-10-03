package io.firebite.tako.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * How one thread starts its agent. Blank values keep the agent's defaults.
 *
 * @param additionalDirectories folders besides the working directory the agent may read and edit
 */
public record AgentLaunch(
  Path workDirectory,
  @Nullable String resumeSessionId,
  String model,
  String mode,
  String effort,
  List<String> additionalDirectories,
  Map<String, String> environment
) {

  /** This launch with blank choices filled from those defaults. */
  public AgentLaunch withDefaults(AgentDefaults defaults) {
    return new AgentLaunch(this.workDirectory, this.resumeSessionId, orDefault(this.model, defaults.model()),
      orDefault(this.mode, defaults.mode()), orDefault(this.effort, defaults.effort()), this.additionalDirectories,
      this.environment);
  }

  /** The file's text, with relative paths resolved against the working directory. Null if it cannot be read. */
  public @Nullable String readFile(String filePath) {
    try {
      Path path = this.workDirectory.resolve(filePath).normalize();
      return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    } catch (IOException | InvalidPathException e) {
      return null;
    }
  }

  private static String orDefault(String value, String fallback) {
    return value.isEmpty() ? fallback : value;
  }
}
