package io.firebite.tako.ide.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * A project folder's icon. It takes the IDE's own {@code .idea/icon.svg}, {@code .idea/icon_dark.svg} or
 * {@code .idea/icon.png}, else a favicon in one of the usual places.
 */
public final class ProjectIcons {
  static final long MAX_BYTES = 256 * 1024;
  private static final List<String> IDE_ICONS = List.of(".idea/icon.svg", ".idea/icon.png");
  private static final List<String> IDE_DARK_ICONS = List.of(".idea/icon_dark.svg", ".idea/icon_dark.png");
  private static final List<String> FAVICON_FOLDERS = List.of("", "public", "static", "src", "app", "assets",
    "web", "src/main/resources/static", "src/main/resources/public");
  private static final List<String> FAVICON_NAMES = List.of("favicon.svg", "favicon.png", "favicon.ico",
    "icon.svg", "icon.png");

  private ProjectIcons() {
  }

  /** Reads the icon files, so call off the EDT. */
  public static Icons find(Path folder) {
    Icon light = first(folder, IDE_ICONS);
    Icon dark = first(folder, IDE_DARK_ICONS);
    if (light == null) {
      for (String directory : FAVICON_FOLDERS) {
        light = first(directory.isEmpty() ? folder : folder.resolve(directory), FAVICON_NAMES);
        if (light != null) {
          break;
        }
      }
    }
    return new Icons(light, dark);
  }

  private static @Nullable Icon first(Path folder, List<String> names) {
    for (String name : names) {
      Icon icon = read(folder.resolve(name));
      if (icon != null) {
        return icon;
      }
    }
    return null;
  }

  static @Nullable Icon read(Path file) {
    try {
      if (!isImage(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
        return null;
      }
      return new Icon(file, Files.readAllBytes(file));
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean isImage(Path file) {
    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return lower.endsWith(".svg") || lower.endsWith(".png") || lower.endsWith(".ico");
  }

  public record Icons(
    @Nullable Icon light,
    @Nullable Icon dark
  ) {
  }

  /** @param bytes the file's content */
  public record Icon(
    Path file,
    byte[] bytes
  ) {
    public boolean isSvg() {
      return this.file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg");
    }
  }
}
