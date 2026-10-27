package io.firebite.tako.ide.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Keeps a large paste out of the prompt: it goes to a text file the agent reads with its tools. */
public final class PastedText {
  /** Pastes of at least this many characters become a file. */
  public static final int FILE_THRESHOLD = 32 * 1024;

  private PastedText() {
  }

  public static boolean isLarge(String text) {
    return text.length() >= FILE_THRESHOLD;
  }

  /** Writes {@code text} to the first free {@code pasted-text-<n>.txt} in {@code directory}, creating the folder. */
  public static Path write(Path directory, String text) throws IOException {
    Files.createDirectories(directory);
    for (int n = 1;; n++) {
      try {
        return Files.writeString(directory.resolve("pasted-text-" + n + ".txt"), text, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      } catch (FileAlreadyExistsException ignored) {
      }
    }
  }
}
