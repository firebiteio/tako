package io.firebite.tako.agent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Finds an agent's executable. It tries the configured path, then PATH, then where installers usually put it. */
public final class ExecutableLocator {
  private static final String[] WINDOWS_SUFFIXES = {".exe", ".cmd", ".bat", ""};

  private ExecutableLocator() {
  }

  /**
   * @param configured  a path the person set, or blank to search
   * @param name        the executable's name without extension
   * @param extraFolders searched after PATH and npm's global folders
   */
  public static @Nullable String locate(String configured, String name, List<Path> extraFolders,
    Map<String, String> environment, boolean isWindows) {
    if (!configured.isBlank()) {
      return Files.isRegularFile(Path.of(configured)) ? configured : null;
    }
    String[] suffixes = isWindows ? WINDOWS_SUFFIXES : new String[]{""};
    for (Path directory : candidateFolders(extraFolders, environment, isWindows)) {
      for (String suffix : suffixes) {
        Path candidate = directory.resolve(name + suffix);
        if (Files.isRegularFile(candidate)) {
          return candidate.toString();
        }
      }
    }
    return null;
  }

  private static List<Path> candidateFolders(List<Path> extraFolders, Map<String, String> environment,
    boolean isWindows) {
    List<Path> folders = new ArrayList<>();
    String path = environment.get(isWindows ? pathKey(environment) : "PATH");
    if (path != null) {
      for (String entry : path.split(File.pathSeparator)) {
        if (!entry.isBlank()) {
          folders.add(Path.of(entry.trim()));
        }
      }
    }
    String home = System.getProperty("user.home");
    folders.add(Path.of(home, ".local", "bin"));
    if (isWindows) {
      String appData = environment.get("APPDATA");
      if (appData != null) {
        folders.add(Path.of(appData, "npm"));
      }
    } else {
      folders.add(Path.of("/usr/local/bin"));
      folders.add(Path.of("/opt/homebrew/bin"));
      folders.add(Path.of(home, ".npm-global", "bin"));
    }
    folders.addAll(extraFolders);
    return folders;
  }

  private static String pathKey(Map<String, String> environment) {
    for (String key : environment.keySet()) {
      if (key.equalsIgnoreCase("PATH")) {
        return key;
      }
    }
    return "PATH";
  }
}
