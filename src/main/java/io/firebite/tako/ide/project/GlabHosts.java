package io.firebite.tako.ide.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The GitLab hosts {@code glab} knows, read from its config without running it, so a self-hosted GitLab whose name
 * lacks "gitlab" counts as GitLab too.
 */
public final class GlabHosts {
  private static final String CONFIG = "config.yml";
  private static final String FOLDER = "glab-cli";

  private GlabHosts() {
  }

  /** The hosts of every glab config found and of {@code GITLAB_HOST}, lowercase. */
  public static Set<String> read(Map<String, String> environment) {
    Set<String> hosts = new LinkedHashSet<>();
    for (String variable : List.of("GITLAB_HOST", "GL_HOST")) {
      String host = environment.get(variable);
      if (host != null && !host.isBlank()) {
        hosts.add(hostOf(host));
      }
    }
    for (Path folder : configFolders(environment)) {
      Path config = folder.resolve(CONFIG);
      if (!Files.isRegularFile(config)) {
        continue;
      }
      try {
        hosts.addAll(parse(Files.readString(config, StandardCharsets.UTF_8)));
      } catch (IOException ignored) {
      }
    }
    return hosts;
  }

  /** The keys under the top level {@code hosts:} of a glab {@code config.yml}, lowercase. */
  static Set<String> parse(String config) {
    Set<String> hosts = new LinkedHashSet<>();
    boolean isInHosts = false;
    int hostIndent = -1;
    for (String line : config.split("\\R")) {
      String content = line.strip();
      if (content.isEmpty() || content.startsWith("#")) {
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      if (indent == 0) {
        isInHosts = content.equals("hosts:");
        continue;
      }
      if (!isInHosts) {
        continue;
      }
      if (hostIndent < 0) {
        hostIndent = indent;
      }
      if (indent == hostIndent && content.endsWith(":")) {
        String key = content.substring(0, content.length() - 1).strip();
        char quote = key.isEmpty() ? ' ' : key.charAt(0);
        if (key.length() > 1 && (quote == '"' || quote == '\'') && key.charAt(key.length() - 1) == quote) {
          key = key.substring(1, key.length() - 1);
        }
        if (!key.isEmpty()) {
          hosts.add(key.toLowerCase(Locale.ROOT));
        }
      }
    }
    return hosts;
  }

  /** Where glab keeps its config on each system, the override first. */
  private static List<Path> configFolders(Map<String, String> environment) {
    List<Path> folders = new ArrayList<>();
    add(folders, environment.get("GLAB_CONFIG_DIR"), null);
    add(folders, environment.get("XDG_CONFIG_HOME"), FOLDER);
    add(folders, environment.get("LOCALAPPDATA"), FOLDER);
    add(folders, environment.get("APPDATA"), FOLDER);
    String home = System.getProperty("user.home");
    add(folders, home, ".config/" + FOLDER);
    add(folders, home, "Library/Application Support/" + FOLDER);
    return folders;
  }

  private static void add(List<Path> folders, @Nullable String base, @Nullable String child) {
    if (base == null || base.isBlank()) {
      return;
    }
    Path folder = child == null ? Path.of(base) : Path.of(base, child);
    if (!folders.contains(folder)) {
      folders.add(folder);
    }
  }

  /** {@code GITLAB_HOST} may be a URL like {@code https://gitlab.example.com}. */
  private static String hostOf(String value) {
    String host = value.strip();
    int scheme = host.indexOf("://");
    if (scheme >= 0) {
      host = host.substring(scheme + 3);
    }
    int slash = host.indexOf('/');
    return (slash < 0 ? host : host.substring(0, slash)).toLowerCase(Locale.ROOT);
  }
}
