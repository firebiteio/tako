package io.firebite.tako.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Null-tolerant reads of loosely shaped JSON. A missing or mistyped member reads as null, false or 0. */
public final class Json {
  private Json() {
  }

  /** One JSON object, or null for blank, malformed or non-object text. */
  public static @Nullable JsonObject parseObject(@Nullable String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      JsonElement element = JsonParser.parseString(text);
      return element.isJsonObject() ? element.getAsJsonObject() : null;
    } catch (JsonParseException e) {
      return null;
    }
  }

  public static @Nullable String string(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
  }

  public static @Nullable JsonObject object(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  public static @Nullable JsonArray array(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
  }

  /** The array's object elements, empty when the array is missing. */
  public static List<JsonObject> objects(@Nullable JsonObject object, String key) {
    List<JsonObject> objects = new ArrayList<>();
    JsonArray array = array(object, key);
    if (array != null) {
      for (JsonElement element : array) {
        if (element.isJsonObject()) {
          objects.add(element.getAsJsonObject());
        }
      }
    }
    return objects;
  }

  public static boolean bool(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsBoolean();
  }

  public static long number(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
      ? element.getAsLong()
      : 0;
  }

  public static double decimal(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
      ? element.getAsDouble()
      : 0;
  }

  /** The number, else null when absent or not a number. */
  public static @Nullable Long optionalNumber(@Nullable JsonObject object, String key) {
    return isNumber(object, key) ? number(object, key) : null;
  }

  public static boolean isNumber(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
  }

  public static JsonObject of(String key, String value) {
    JsonObject object = new JsonObject();
    object.addProperty(key, value);
    return object;
  }
}
