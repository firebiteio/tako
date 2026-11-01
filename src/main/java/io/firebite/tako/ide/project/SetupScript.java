package io.firebite.tako.ide.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code .worktreesetup} file: shell commands, one per line, that Tako runs in each new worktree before its agent
 * gets the first prompt, e.g. {@code npm ci}. Empty lines and lines starting with {@code #} are skipped. Each line runs
 * in its own shell in the worktree's top folder: {@code cmd.exe} on Windows, {@code /bin/sh} elsewhere.
 */
public final class SetupScript {
  public static final String FILE = ".worktreesetup";
  public static final String WORKTREE_VARIABLE = "TAKO_WORKTREE";
  public static final String REPOSITORY_VARIABLE = "TAKO_REPOSITORY";
  private static final char BYTE_ORDER_MARK = 0xFEFF;

  private SetupScript() {
  }

  /**
   * The commands of the worktree's own file, which a commit brought along, else of the main checkout's, which may be
   * gitignored. Empty when neither has one.
   */
  public static List<String> read(Path worktree, Path repository) throws IOException {
    for (Path folder : List.of(worktree, repository)) {
      Path file = folder.resolve(FILE);
      if (Files.isRegularFile(file)) {
        return commands(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
      }
    }
    return List.of();
  }

  /** The trimmed lines that are neither empty nor comments. A byte order mark at the start is ignored. */
  public static List<String> commands(String text) {
    String content = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    List<String> commands = new ArrayList<>();
    for (String line : content.split("\\R")) {
      String command = line.strip();
      if (!command.isEmpty() && !command.startsWith("#")) {
        commands.add(command);
      }
    }
    return commands;
  }

  /**
   * The process to start for one command. On Windows the command goes to {@code cmd.exe /s /c} in quotes, which Java
   * passes on as they are and cmd takes off again, so the command reaches cmd exactly as written. A command ending in
   * a backslash gets a space after it, as Java would take {@code \"} for an escaped quote and quote it again.
   */
  public static List<String> shell(String command, boolean isWindows, Map<String, String> environment) {
    if (isWindows) {
      String end = command.endsWith("\\") ? " \"" : "\"";
      return List.of(comSpec(environment), "/d", "/s", "/c", "\"" + command + end);
    }
    return List.of("/bin/sh", "-c", command);
  }

  /** The environment with the worktree's and the main checkout's folders added. */
  public static Map<String, String> environment(Map<String, String> base, Path worktree, Path repository) {
    Map<String, String> environment = new HashMap<>(base);
    environment.put(WORKTREE_VARIABLE, worktree.toString());
    environment.put(REPOSITORY_VARIABLE, repository.toString());
    return environment;
  }

  /** Windows' command interpreter, by its full path so no {@code cmd.exe} in the current folder is taken instead. */
  private static String comSpec(Map<String, String> environment) {
    String systemRoot = null;
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      if (entry.getKey().equalsIgnoreCase("ComSpec") && !entry.getValue().isBlank()) {
        return entry.getValue();
      }
      if (entry.getKey().equalsIgnoreCase("SystemRoot") && !entry.getValue().isBlank()) {
        systemRoot = entry.getValue();
      }
    }
    return systemRoot != null ? Path.of(systemRoot, "System32", "cmd.exe").toString() : "cmd.exe";
  }
}
