package io.firebite.tako;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/** Every key the code uses is in its bundle, every bundle key is used, and translations match their bundle. */
public class BundleKeysTest {
  private static final Pattern JAVA_KEY = Pattern.compile("TakoBundle\\.message\\(\\s*\"([^\"]+)\"");
  private static final Pattern XML_KEY = Pattern.compile("\\bkey=\"([^\"]+)\"");
  private static final Pattern XML_ACTION = Pattern.compile("<action id=\"([^\"]+)\"");
  private static final Pattern XML_GROUP = Pattern.compile("<group id=\"([^\"]+)\"");
  private static final Pattern XML_TOOL_WINDOW = Pattern.compile("<toolWindow id=\"([^\"]+)\"");
  private static final Pattern KOTLIN_KEY = Pattern.compile("\\bt\\(\\s*\"([^\"]+)\"");
  private static final Pattern KOTLIN_CALL = Pattern.compile("\\bt\\(\\s*");
  private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)");
  private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

  @Test
  public void ideTextsMatchTheBundle() throws IOException {
    Set<String> used = new TreeSet<>();
    for (Path file : files(Path.of("src/main/java"), ".java")) {
      collect(JAVA_KEY, Files.readString(file), used);
    }
    String pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"));
    collect(XML_KEY, pluginXml, used);
    Set<String> bundle = keys("messages/TakoBundle.properties");
    for (String id : matches(XML_ACTION, pluginXml)) {
      used.add("action." + id + ".text");
      if (bundle.contains("action." + id + ".description")) {
        used.add("action." + id + ".description");
      }
    }
    for (String id : matches(XML_GROUP, pluginXml)) {
      used.add("group." + id + ".text");
    }
    for (String id : matches(XML_TOOL_WINDOW, pluginXml)) {
      used.add("toolwindow.stripe." + id);
    }
    this.assertSame(used, bundle);
  }

  @Test
  public void pageTextsMatchTheBundle() throws IOException {
    Set<String> used = new TreeSet<>();
    List<String> dynamic = new ArrayList<>();
    for (Path file : files(Path.of("src/main/kotlin"), ".kt")) {
      if (file.endsWith("ui/Texts.kt")) {
        continue;
      }
      String source = Files.readString(file);
      collect(KOTLIN_KEY, source, used);
      Matcher calls = KOTLIN_CALL.matcher(source);
      while (calls.find()) {
        if (!source.startsWith("\"", calls.end())) {
          dynamic.add(file.getFileName() + ": " + source.substring(calls.start(), Math.min(source.length(),
            calls.end() + 30)));
        }
      }
    }
    assertEquals("t() needs a literal key", List.of(), dynamic);
    this.assertSame(used, keys("messages/TakoPageBundle.properties"));
  }

  @Test
  public void translationsMatchTheirBundle() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Path file : files(Path.of("src/main/resources/messages"), ".properties")) {
      String name = file.getFileName().toString();
      Properties texts = load("messages/" + name);
      int locale = name.indexOf('_');
      Properties english = locale < 0 ? texts : load("messages/" + name.substring(0, locale) + ".properties");
      Set<String> keys = new TreeSet<>(english.stringPropertyNames());
      keys.addAll(texts.stringPropertyNames());
      for (String key : keys) {
        String source = english.getProperty(key);
        String text = texts.getProperty(key);
        if (source == null || text == null) {
          problems.add(name + ": " + key + (source == null ? " is not in the English bundle" : " is missing"));
        } else if (!arguments(source).equals(arguments(text))) {
          problems.add(name + ": " + key + " uses other arguments than the English text");
        } else if (!arguments(text).isEmpty() && LONE_APOSTROPHE.matcher(text).find()) {
          problems.add(name + ": " + key + " has an apostrophe that MessageFormat drops, write it as ''");
        }
      }
    }
    assertEquals(List.of(), problems);
  }

  private void assertSame(Set<String> used, Set<String> bundle) {
    Set<String> missing = new TreeSet<>(used);
    missing.removeAll(bundle);
    Set<String> unused = new TreeSet<>(bundle);
    unused.removeAll(used);
    assertEquals("missing from the bundle", Set.of(), missing);
    assertEquals("not used anywhere", Set.of(), unused);
  }

  private static Set<String> keys(String resource) throws IOException {
    return new TreeSet<>(load(resource).stringPropertyNames());
  }

  private static Set<String> arguments(String text) {
    return new TreeSet<>(matches(ARGUMENT, text));
  }

  private static Properties load(String resource) throws IOException {
    Properties properties = new Properties();
    try (InputStream stream = BundleKeysTest.class.getClassLoader().getResourceAsStream(resource)) {
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties;
  }

  private static List<Path> files(Path root, String extension) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(path -> path.toString().endsWith(extension)).toList();
    }
  }

  private static List<String> matches(Pattern pattern, String text) {
    List<String> found = new ArrayList<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  private static void collect(Pattern pattern, String text, Set<String> into) {
    into.addAll(matches(pattern, text));
  }
}
