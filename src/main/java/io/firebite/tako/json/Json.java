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
    return asObject(parse(text));
  }

  /** One JSON array, or null for blank, malformed or non-array text. */
  public static @Nullable JsonArray parseArray(@Nullable String text) {
    JsonElement element = parse(text);
    return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
  }

  private static @Nullable JsonElement parse(@Nullable String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return JsonParser.parseString(text);
    } catch (JsonParseException e) {
      return null;
    }
  }

  /** The element as an object, else null. */
  public static @Nullable JsonObject asObject(@Nullable JsonElement element) {
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  /** The element as a string when it is a primitive, else null. */
  public static @Nullable String asString(@Nullable JsonElement element) {
    return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
  }

  public static @Nullable String string(@Nullable JsonObject object, String key) {
    return asString(object == null ? null : object.get(key));
  }

  public static @Nullable JsonObject object(@Nullable JsonObject object, String key) {
    return asObject(object == null ? null : object.get(key));
  }

  public static @Nullable JsonArray array(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
  }

  /** The array's object elements, empty when the array is missing. */
  public static List<JsonObject> objects(@Nullable JsonObject object, String key) {
    return objects(array(object, key));
  }

  /** The array's object elements, empty when the array is null. */
  public static List<JsonObject> objects(@Nullable JsonArray array) {
    List<JsonObject> objects = new ArrayList<>();
    if (array != null) {
      for (JsonElement element : array) {
        if (element.isJsonObject()) {
          objects.add(element.getAsJsonObject());
        }
      }
    }
    return objects;
  }

  /** The array's primitive elements as strings, empty when the array is missing. */
  public static List<String> strings(@Nullable JsonObject object, String key) {
    List<String> strings = new ArrayList<>();
    JsonArray array = array(object, key);
    if (array != null) {
      for (JsonElement element : array) {
        if (element.isJsonPrimitive()) {
          strings.add(element.getAsString());
        }
      }
    }
    return strings;
  }

  public static boolean bool(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsBoolean();
  }

  /** The boolean, else null when absent or not a primitive. */
  public static @Nullable Boolean optionalBool(@Nullable JsonObject object, String key) {
    JsonElement element = object == null ? null : object.get(key);
    return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : null;
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

  /** The number narrowed to an int, else null when absent or not a number. */
  public static @Nullable Integer optionalInt(@Nullable JsonObject object, String key) {
    return isNumber(object, key) ? (int) number(object, key) : null;
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
