package io.firebite.tako.ui.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializer;
import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.json.Json;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Turns {@link PageMessage}s into the page's JSON and the page's JSON into {@link PageOp}s. */
public final class PageCodec {
  private static final Logger LOG = Logger.getInstance(PageCodec.class);
  private static final String TYPE = "type";
  private static final Gson PLAIN = new Gson();
  /** Agent events carry their kind as {@code type}, wherever they are nested. */
  private static final Gson GSON = new GsonBuilder()
    .registerTypeHierarchyAdapter(AgentEvent.class, (JsonSerializer<AgentEvent>) (event, type, context) -> {
      JsonObject json = PLAIN.toJsonTree(event).getAsJsonObject();
      json.addProperty(TYPE, wireName(event.getClass()));
      return json;
    })
    .create();
  private static final String KIND = "kind";
  private static final String OP = "op";
  private static final String THREAD = "thread";
  private static final Map<String, Class<?>> OPS = new HashMap<>();

  static {
    index(PageOp.class);
  }

  private PageCodec() {
  }

  public static JsonObject encode(PageMessage message) {
    JsonObject json = GSON.toJsonTree(message).getAsJsonObject();
    json.addProperty(KIND, wireName(message.getClass()));
    return json;
  }

  /** A thread's message, tagged with the thread. */
  public static JsonObject encode(PageMessage.ThreadMessage message, String threadId) {
    JsonObject json = encode(message);
    json.addProperty(THREAD, threadId);
    return json;
  }

  /** The op the page sent. Logs and returns null when the op is unknown or lacks a field it needs. */
  public static @Nullable PageRequest decode(String payload) {
    JsonObject json = Json.parseObject(payload);
    String name = Json.string(json, OP);
    Class<?> type = name == null ? null : OPS.get(name);
    if (type == null) {
      LOG.warn("Unknown chat op: " + name);
      return null;
    }
    try {
      PageOp op = (PageOp) GSON.fromJson(json, type);
      String missing = missingField(op);
      if (missing != null) {
        LOG.warn("Chat op " + name + " lacks " + missing);
        return null;
      }
      return new PageRequest(Json.string(json, THREAD), op);
    } catch (JsonParseException | IllegalArgumentException e) {
      LOG.warn("Malformed chat op " + name, e);
      return null;
    }
  }

  /** {@code ThreadAdded} → {@code threadAdded}. */
  static String wireName(Class<?> type) {
    String name = type.getSimpleName();
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static void index(Class<?> type) {
    if (type.isRecord()) {
      Class<?> previous = OPS.put(wireName(type), type);
      if (previous != null) {
        throw new IllegalStateException("Two page ops named " + wireName(type));
      }
      return;
    }
    for (Class<?> subtype : type.getPermittedSubclasses()) {
      index(subtype);
    }
  }

  private static @Nullable String missingField(PageOp op) {
    for (RecordComponent component : op.getClass().getRecordComponents()) {
      if (component.getType().isPrimitive() || component.isAnnotationPresent(Omittable.class)) {
        continue;
      }
      try {
        if (component.getAccessor().invoke(op) == null) {
          return component.getName();
        }
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
    }
    return null;
  }
}
