package io.firebite.tako.ui;

import com.google.gson.JsonObject;
import com.intellij.DynamicBundle;
import com.intellij.openapi.util.text.StringUtil;
import java.util.Enumeration;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NonNls;

/**
 * The chat page's texts from {@code messages/TakoPageBundle.properties}. The page formats them itself, so they go out
 * unformatted, and {@code {{key}}} in {@code index.html} is replaced before the page loads.
 */
final class PageBundle {
  @NonNls
  private static final String PATH = "messages.TakoPageBundle";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([\\w.]+)}}");

  private PageBundle() {
  }

  /** Every text by key, for {@code window.takoStrings}. */
  static String json() {
    ResourceBundle bundle = bundle();
    JsonObject strings = new JsonObject();
    Enumeration<String> keys = bundle.getKeys();
    while (keys.hasMoreElements()) {
      String key = keys.nextElement();
      strings.addProperty(key, bundle.getString(key));
    }
    return strings.toString();
  }

  /** The page with each {@code {{key}}} replaced by its text, escaped for HTML. */
  static String fill(String html) {
    ResourceBundle bundle = bundle();
    Matcher matcher = PLACEHOLDER.matcher(html);
    StringBuilder filled = new StringBuilder();
    while (matcher.find()) {
      String text = StringUtil.escapeXmlEntities(bundle.getString(matcher.group(1)));
      matcher.appendReplacement(filled, Matcher.quoteReplacement(text));
    }
    matcher.appendTail(filled);
    return filled.toString();
  }

  /**
   * The texts in the IDE's language. Without a fallback, an English IDE gets the English texts even when the system
   * language has a translation.
   */
  private static ResourceBundle bundle() {
    return ResourceBundle.getBundle(PATH, DynamicBundle.getLocale(), PageBundle.class.getClassLoader(),
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
  }

  /** The language the texts are in, for {@code <html lang>}. */
  static String languageTag() {
    return DynamicBundle.getLocale().toLanguageTag();
  }
}
