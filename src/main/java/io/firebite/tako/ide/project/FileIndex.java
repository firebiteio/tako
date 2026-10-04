package io.firebite.tako.ide.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** The files and folders of a project folder for {@code @} mentions, as git sees them when it is a repository. */
public final class FileIndex {
  static final int MAX_ENTRIES = 50_000;
  private static final long FRESH_MILLIS = 30_000;
  private static final Set<String> SKIPPED = Set.of(".git", ".idea", ".gradle", "node_modules", "build", "out",
    "target", "dist", ".venv", "venv", "__pycache__", ".intellijPlatform", ".next", ".cache");

  private static final Map<Path, Snapshot> CACHE = new ConcurrentHashMap<>();

  private FileIndex() {
  }

  /** Paths relative to {@code root} with forward slashes. Folders end with a slash. */
  public static List<String> entries(Path root, Map<String, String> environment) {
    Snapshot snapshot = CACHE.get(root);
    if (snapshot != null && System.currentTimeMillis() - snapshot.takenAt() < FRESH_MILLIS) {
      return snapshot.entries();
    }
    List<String> files = gitFiles(root, environment);
    if (files == null) {
      files = walk(root);
    }
    List<String> entries = withFolders(files);
    CACHE.put(root, new Snapshot(System.currentTimeMillis(), entries));
    return entries;
  }

  public static List<Match> search(List<String> entries, String query, int limit) {
    String needle = query.toLowerCase(Locale.ROOT).replace('\\', '/');
    List<ScoredEntry> scored = new ArrayList<>();
    for (String entry : entries) {
      int score = score(entry, needle);
      if (score > 0) {
        scored.add(new ScoredEntry(entry, score));
      }
    }
    scored.sort(Comparator.comparingInt(ScoredEntry::score).reversed()
      .thenComparingInt(item -> item.entry().length())
      .thenComparing(ScoredEntry::entry));
    List<Match> matches = new ArrayList<>();
    for (ScoredEntry item : scored.subList(0, Math.min(limit, scored.size()))) {
      boolean isDirectory = item.entry().endsWith("/");
      matches.add(new Match(isDirectory ? item.entry().substring(0, item.entry().length() - 1) : item.entry(),
        isDirectory));
    }
    return matches;
  }

  static int score(String entry, String needle) {
    String path = entry.toLowerCase(Locale.ROOT);
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
    if (needle.isEmpty()) {
      return trimmed.indexOf('/') < 0 ? 10 : 1;
    }
    if (name.equals(needle)) {
      return 100;
    }
    if (name.startsWith(needle)) {
      return 80;
    }
    if (name.contains(needle)) {
      return 60;
    }
    if (trimmed.startsWith(needle)) {
      return 50;
    }
    if (trimmed.contains(needle)) {
      return 40;
    }
    return isSubsequence(needle, name) ? 10 : 0;
  }

  private static boolean isSubsequence(String needle, String haystack) {
    int position = 0;
    for (int i = 0; i < haystack.length() && position < needle.length(); i++) {
      if (haystack.charAt(i) == needle.charAt(position)) {
        position++;
      }
    }
    return position == needle.length();
  }

  static List<String> withFolders(List<String> files) {
    Set<String> entries = new LinkedHashSet<>();
    for (String file : files) {
      int slash = file.indexOf('/');
      while (slash > 0) {
        entries.add(file.substring(0, slash + 1));
        slash = file.indexOf('/', slash + 1);
      }
    }
    entries.addAll(files);
    return List.copyOf(entries);
  }

  private static List<String> gitFiles(Path root, Map<String, String> environment) {
    ProcessBuilder builder = new ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z");
    builder.directory(root.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Path output = null;
    try {
      output = Files.createTempFile("tako-files", ".out");
      builder.redirectOutput(output.toFile());
      Process process = builder.start();
      process.getOutputStream().close();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      if (process.exitValue() != 0) {
        return null;
      }
      List<String> files = new ArrayList<>();
      for (String file : Files.readString(output, StandardCharsets.UTF_8).split("\0")) {
        if (!file.isEmpty() && files.size() < MAX_ENTRIES) {
          files.add(file);
        }
      }
      return files;
    } catch (IOException e) {
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

  static List<String> walk(Path root) {
    List<String> files = new ArrayList<>();
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
          if (!directory.equals(root) && SKIPPED.contains(directory.getFileName().toString())) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          return files.size() >= MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          files.add(root.relativize(file).toString().replace('\\', '/'));
          return files.size() >= MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
      return files;
    }
    return files;
  }

  public record Match(
    String path,
    boolean isDirectory
  ) {
  }

  private record Snapshot(
    long takenAt,
    List<String> entries
  ) {
  }

  private record ScoredEntry(
    String entry,
    int score
  ) {
  }
}
