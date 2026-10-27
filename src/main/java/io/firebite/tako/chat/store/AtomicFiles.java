package io.firebite.tako.chat.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a file whole, so a crash leaves either the old or the new content. */
final class AtomicFiles {
  private AtomicFiles() {
  }

  static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
