package io.firebite.tako.ide.project;

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Copies the gitignored files that the main checkout's {@code .worktreeinclude} lists into a new worktree, so local
 * config like {@code .env} or {@code .mcp.json} is there too. The file has {@code .gitignore} syntax. Tracked files,
 * symlinks and files the worktree already has are left alone. Folders git ignores as a whole, like {@code build}, are
 * only searched when a pattern names them or a path in them, so big build output does not slow down every worktree.
 */
final class WorktreeInclude {
  private static final Logger LOG = Logger.getInstance(WorktreeInclude.class);
  private static final String FILE = ".worktreeinclude";
  /** Keeps the git command line well under the Windows limit of 32767 characters. */
  private static final int EXCLUDES_LENGTH = 16_000;

  private WorktreeInclude() {
  }

  /** Never fails: problems are logged and the worktree stays usable without the files. */
  static void copy(Path worktree, Map<String, String> environment) {
    try {
      String commonDir = Worktrees.git(worktree, environment, "rev-parse", "--path-format=absolute",
        "--git-common-dir");
      Path main = Path.of(commonDir.trim()).getParent();
      Path include = main.resolve(FILE);
      if (!Files.isRegularFile(include)) {
        return;
      }
      int copied = 0;
      for (String path : listed(main, include, environment)) {
        if (copyFile(main, worktree, path)) {
          copied++;
        }
      }
      LOG.info("Copied " + copied + " files from " + FILE + " into " + worktree);
    } catch (IOException | InvalidPathException | Worktrees.GitException e) {
      LOG.warn("Could not copy the files " + FILE + " lists into " + worktree, e);
    }
  }

  /** Untracked files in {@code main} that match the include patterns and are gitignored, relative to {@code main}. */
  private static List<String> listed(Path main, Path include, Map<String, String> environment)
    throws IOException, Worktrees.GitException {
    Set<String> ignoredFiles = new HashSet<>();
    Set<String> ignoredFolders = new HashSet<>();
    String ignored = Worktrees.git(main, environment, "ls-files", "--others", "--ignored", "--exclude-standard",
      "--directory", "-z");
    for (String entry : ignored.split("\0")) {
      if (entry.endsWith("/")) {
        ignoredFolders.add(entry.substring(0, entry.length() - 1));
      } else if (!entry.isEmpty()) {
        ignoredFiles.add(entry);
      }
    }

    List<String> patterns = Files.readAllLines(include, StandardCharsets.UTF_8);
    List<String> arguments = new ArrayList<>(
      List.of("ls-files", "--others", "--ignored", "--exclude-from=" + include, "-z", "--", "."));
    List<String> folders = new ArrayList<>(ignoredFolders);
    folders.sort(Comparator.comparingInt(WorktreeInclude::depth));
    int length = 0;
    for (String folder : folders) {
      if (isNamed(folder, patterns)) {
        continue;
      }
      String exclude = ":(exclude,literal)" + folder + "/";
      length += exclude.length() + 1;
      if (length > EXCLUDES_LENGTH) {
        break;
      }
      arguments.add(exclude);
    }

    List<String> listed = new ArrayList<>();
    for (String path : Worktrees.git(main, environment, arguments.toArray(String[]::new)).split("\0")) {
      if (ignoredFiles.contains(path) || isInside(path, ignoredFolders)) {
        listed.add(path);
      }
    }
    return listed;
  }

  /**
   * Whether a pattern may match {@code folder} or a path in it. A pattern without a slash only counts when it matches
   * one of the folder's names. Errs towards true, which only costs a search through the folder.
   */
  private static boolean isNamed(String folder, List<String> patterns) {
    String[] names = folder.split("/");
    for (String line : patterns) {
      String pattern = line.stripTrailing();
      if (pattern.isEmpty() || pattern.startsWith("#") || pattern.startsWith("!")) {
        continue;
      }
      if (pattern.endsWith("/")) {
        pattern = pattern.substring(0, pattern.length() - 1);
      }
      if (pattern.indexOf('/') < 0) {
        for (String name : names) {
          if (glob(pattern).matcher(name).matches()) {
            return true;
          }
        }
      } else if (startsLike(pattern.startsWith("/") ? pattern.substring(1) : pattern, names)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the pattern's leading parts match the folder's leading names, as far as both go. */
  private static boolean startsLike(String pattern, String[] names) {
    String[] parts = pattern.split("/");
    for (int i = 0; i < parts.length && i < names.length; i++) {
      if (parts[i].equals("**")) {
        return true;
      }
      if (!glob(parts[i]).matcher(names[i]).matches()) {
        return false;
      }
    }
    return true;
  }

  /** One part of a pattern as a regex. Matches loosely: ignores case and lets a bracket class stand for any char. */
  private static Pattern glob(String part) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < part.length(); i++) {
      char c = part.charAt(i);
      if (c == '*') {
        regex.append(".*");
      } else if (c == '?') {
        regex.append('.');
      } else if (c == '[') {
        int end = part.indexOf(']', i + 2);
        if (end < 0) {
          regex.append(".*");
          break;
        }
        regex.append('.');
        i = end;
      } else if (c == '\\' && i + 1 < part.length()) {
        regex.append(Pattern.quote(String.valueOf(part.charAt(++i))));
      } else {
        regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  private static boolean isInside(String path, Set<String> folders) {
    for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
      if (folders.contains(path.substring(0, slash))) {
        return true;
      }
    }
    return false;
  }

  private static int depth(String path) {
    int depth = 0;
    for (int i = 0; i < path.length(); i++) {
      if (path.charAt(i) == '/') {
        depth++;
      }
    }
    return depth;
  }

  /** Copies one file to the same place in the worktree, unless it is a symlink or the worktree has the file. */
  private static boolean copyFile(Path main, Path worktree, String path) {
    try {
      Path source = main.resolve(path);
      Path target = worktree.resolve(path);
      if (Files.isSymbolicLink(source) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
      return true;
    } catch (IOException | InvalidPathException e) {
      LOG.warn("Could not copy " + path + " from " + FILE + " into " + worktree, e);
      return false;
    }
  }
}
