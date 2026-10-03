package io.firebite.tako.ide.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * A project folder's icon as a data URL. It takes the IDE's own {@code .idea/icon.svg}, {@code .idea/icon_dark.svg}
 * or {@code .idea/icon.png}, else a favicon in one of the usual places.
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

  public static Icons find(Path folder) {
    String light = first(folder, IDE_ICONS);
    String dark = first(folder, IDE_DARK_ICONS);
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

  private static @Nullable String first(Path folder, List<String> names) {
    for (String name : names) {
      String url = dataUrl(folder.resolve(name));
      if (url != null) {
        return url;
      }
    }
    return null;
  }

  static @Nullable String dataUrl(Path file) {
    String mediaType = mediaType(file.getFileName().toString());
    try {
      if (mediaType == null || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
        return null;
      }
      return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    } catch (IOException e) {
      return null;
    }
  }

  private static @Nullable String mediaType(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".svg")) {
      return "image/svg+xml";
    }
    if (lower.endsWith(".png")) {
      return "image/png";
    }
    if (lower.endsWith(".ico")) {
      return "image/x-icon";
    }
    return null;
  }

  public record Icons(
    @Nullable String light,
    @Nullable String dark
  ) {
  }
}
