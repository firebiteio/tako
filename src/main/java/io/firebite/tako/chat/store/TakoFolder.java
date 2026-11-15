package io.firebite.tako.chat.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.ide.context.PastedText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code tako} folder in the IDE's system folder, where Tako keeps its files. Each IDE version has its own system
 * folder and an update does not carry it over, so the first use in a new version imports the newest earlier one.
 */
public final class TakoFolder {
  private static final Logger LOG = Logger.getInstance(TakoFolder.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  /** Written once the import ran, so it runs once per IDE version. */
  static final String MARKER = "imported";
  /** A system folder name like {@code IntelliJIdea2026.3}: the product, then the version. */
  private static final Pattern VERSIONED = Pattern.compile("(\\D+)(\\d+)\\.(\\d+)");
  private static final String NAME = "tako";

  private static volatile boolean ready;

  private TakoFolder() {
  }

  /** The folder, after importing the earlier version's files on first use. */
  public static Path get() {
    Path folder = Path.of(PathManager.getSystemPath(), NAME);
    if (!ready) {
      synchronized (TakoFolder.class) {
        if (!ready) {
          prepare(folder);
          ready = true;
        }
      }
    }
    return folder;
  }

  /** Imports into {@code folder} once, from the newest earlier version of the same product beside its system folder. */
  static void prepare(Path folder) {
    Path marker = folder.resolve(MARKER);
    if (Files.exists(marker)) {
      return;
    }
    Path previous = previousFolder(folder.getParent());
    try {
      if (previous != null) {
        importFrom(previous, folder);
        LOG.info("Imported Tako files from " + previous);
      }
      Files.createDirectories(folder);
      Files.writeString(marker, previous != null ? previous.toString() : "", StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOG.warn("Could not import Tako files from " + previous, e);
    }
  }

  /** The {@code tako} folder of the newest version before {@code systemFolder}'s, or null when there is none. */
  static @Nullable Path previousFolder(Path systemFolder) {
    Matcher current = VERSIONED.matcher(systemFolder.getFileName().toString());
    Path parent = systemFolder.getParent();
    if (!current.matches() || parent == null || !Files.isDirectory(parent)) {
      return null;
    }
    int currentVersion = version(current);
    Path best = null;
    int bestVersion = -1;
    try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parent)) {
      for (Path sibling : siblings) {
        Matcher matcher = VERSIONED.matcher(sibling.getFileName().toString());
        if (!matcher.matches() || !matcher.group(1).equals(current.group(1))) {
          continue;
        }
        int version = version(matcher);
        Path candidate = sibling.resolve(NAME);
        if (version < currentVersion && version > bestVersion && Files.isDirectory(candidate)) {
          best = candidate;
          bestVersion = version;
        }
      }
    } catch (IOException e) {
      LOG.warn("Could not look for earlier Tako files in " + parent, e);
      return null;
    }
    return best;
  }

  private static int version(Matcher matcher) {
    return Integer.parseInt(matcher.group(2)) * 1000 + Integer.parseInt(matcher.group(3));
  }

  /**
   * Copies what {@code target} lacks. When both have a thread or stash file, it merges them. {@code target}'s entries
   * win and the imported ones it lacks go after them.
   */
  static void importFrom(Path source, Path target) throws IOException {
    Files.createDirectories(target);
    try (DirectoryStream<Path> files = Files.newDirectoryStream(source, "*.json")) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        Path existing = target.resolve(name);
        if (!Files.exists(existing)) {
          Files.copy(file, existing);
        } else if (name.startsWith("threads-")) {
          merge(file, existing, "threads", "id");
          merge(file, existing, "projects", "path");
          merge(file, existing, "benches", "id");
        } else if (name.startsWith("stash-")) {
          merge(file, existing, "entries", "id");
        }
      }
    }
    copyMissing(source.resolve("past"), target.resolve("past"));
    if (Files.isDirectory(source.resolve("pasted"))) {
      copyMissing(source.resolve("pasted"), PastedText.createFolder(target.resolve("pasted")));
    }
  }

  /** Appends the objects of {@code source}'s {@code array} whose {@code key} {@code target}'s lacks. */
  private static void merge(Path source, Path target, String array, String key) throws IOException {
    JsonObject from = read(source);
    JsonObject into = read(target);
    if (from == null || into == null || !from.has(array) || !from.get(array).isJsonArray()) {
      return;
    }
    JsonArray merged = into.has(array) && into.get(array).isJsonArray() ? into.getAsJsonArray(array) : new JsonArray();
    Set<String> present = new HashSet<>();
    for (JsonElement element : merged) {
      present.add(keyOf(element, key));
    }
    for (JsonElement element : from.getAsJsonArray(array)) {
      if (present.add(keyOf(element, key))) {
        merged.add(element);
      }
    }
    into.add(array, merged);
    if (!into.has("activeId") && from.has("activeId")) {
      into.add("activeId", from.get("activeId"));
    }
    AtomicFiles.write(target, GSON.toJson(into));
  }

  private static String keyOf(JsonElement element, String key) {
    if (!element.isJsonObject() || !element.getAsJsonObject().has(key)) {
      return element.toString();
    }
    return element.getAsJsonObject().get(key).getAsString().replace('\\', '/').toLowerCase();
  }

  private static @Nullable JsonObject read(Path file) {
    try {
      JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
      return element.isJsonObject() ? element.getAsJsonObject() : null;
    } catch (IOException | JsonParseException e) {
      LOG.warn("Could not read " + file + " to import it", e);
      return null;
    }
  }

  private static void copyMissing(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      return;
    }
    Files.createDirectories(target);
    try (DirectoryStream<Path> files = Files.newDirectoryStream(source)) {
      for (Path file : files) {
        Path copy = target.resolve(file.getFileName().toString());
        if (Files.isRegularFile(file) && !Files.exists(copy)) {
          Files.copy(file, copy);
        }
      }
    }
  }
}
