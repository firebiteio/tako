package io.firebite.tako;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.PropertyKey;

/**
 * The IDE side's texts from {@code messages/TakoBundle.properties}. A language pack or a
 * {@code TakoBundle_<locale>.properties} next to it translates them.
 */
public final class TakoBundle {
  @NonNls
  private static final String PATH = "messages.TakoBundle";
  private static final DynamicBundle INSTANCE = new DynamicBundle(TakoBundle.class, PATH);

  private TakoBundle() {
  }

  public static @Nls String message(@PropertyKey(resourceBundle = PATH) String key, Object... params) {
    return INSTANCE.getMessage(key, params);
  }
}
