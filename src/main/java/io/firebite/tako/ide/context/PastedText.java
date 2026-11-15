package io.firebite.tako.ide.context;

import io.firebite.tako.chat.store.TakoFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Keeps a large paste out of the prompt: it goes to a text file the agent reads with its tools. */
public final class PastedText {
  /** Pastes of at least this many UTF-8 bytes become a file. */
  public static final int FILE_THRESHOLD = 32 * 1024;
  /** Pasted files older than this are deleted when the chat opens. */
  public static final Duration MAX_AGE = Duration.ofDays(7);
  private static final Pattern NAME = Pattern.compile("pasted-text-(\\d{1,9})\\.txt");

  private PastedText() {
  }

  public static boolean isLarge(String text) {
    return text.length() >= FILE_THRESHOLD || text.getBytes(StandardCharsets.UTF_8).length >= FILE_THRESHOLD;
  }

  /** Where pasted files go: the {@link TakoFolder}, so per user and outside every project. */
  public static Path folder() {
    return TakoFolder.get().resolve("pasted");
  }

  /** Creates {@code directory}, open only to its owner where the file system has POSIX permissions. */
  public static Path createFolder(Path directory) throws IOException {
    Files.createDirectories(directory);
    if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    return directory;
  }

  /** Writes {@code text} to {@code pasted-text-<n>.txt} in {@code directory}, one past the highest number there. */
  public static Path write(Path directory, String text) throws IOException {
    createFolder(directory);
    for (int n = highestNumber(directory) + 1;; n++) {
      try {
        return Files.writeString(directory.resolve("pasted-text-" + n + ".txt"), text, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      } catch (FileAlreadyExistsException ignored) {
      }
    }
  }

  /** The pasted file in {@code directory} that a chip names, or null for any other chip. */
  public static @Nullable Path fileOf(ContextItem item, Path directory) {
    if (item.kind() != ContextItem.Kind.FILE || !NAME.matcher(item.label()).matches()) {
      return null;
    }
    Path file = directory.resolve(item.label());
    return item.text().endsWith(file.toString()) ? file : null;
  }

  /** Deletes the pasted files in {@code directory} last written before {@code cutoff}. */
  public static void deleteOlderThan(Path directory, Instant cutoff) throws IOException {
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
      for (Path file : files) {
        if (NAME.matcher(file.getFileName().toString()).matches()
          && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  private static int highestNumber(Path directory) throws IOException {
    int highest = 0;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
      for (Path file : files) {
        Matcher matcher = NAME.matcher(file.getFileName().toString());
        if (matcher.matches()) {
          highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
      }
    }
    return highest;
  }
}
