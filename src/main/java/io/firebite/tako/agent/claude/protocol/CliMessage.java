package io.firebite.tako.agent.claude.protocol;

import com.google.gson.JsonObject;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** One stream-json line the CLI wrote, with typed reads of the parts the IDE acts on. */
public record CliMessage(
  JsonObject json
) {
  public static final String SUBTYPE_INIT = "init";
  private static final String TASK_SUBTYPE_PREFIX = "task_";

  public static CliMessage of(Type type, JsonObject body) {
    JsonObject json = Json.of("type", type.wireName());
    json.add("message", body);
    return new CliMessage(json);
  }

  public Type type() {
    return Type.of(Json.string(this.json, "type"));
  }

  public @Nullable String subtype() {
    return Json.string(this.json, "subtype");
  }

  public @Nullable String sessionId() {
    return Json.string(this.json, "session_id");
  }

  /** The working directory a {@code system/init} reports. */
  public @Nullable String workingDirectory() {
    return Json.string(this.json, "cwd");
  }

  public boolean isSystemInit() {
    return this.type() == Type.SYSTEM && SUBTYPE_INIT.equals(this.subtype());
  }

  public boolean isTaskEvent() {
    String subtype = this.subtype();
    return this.type() == Type.SYSTEM && subtype != null && subtype.startsWith(TASK_SUBTYPE_PREFIX);
  }

  /** The {@code message} of a user or assistant line. */
  public @Nullable JsonObject body() {
    return Json.object(this.json, "message");
  }

  public List<ToolResult> toolResults() {
    List<ToolResult> results = new ArrayList<>();
    for (JsonObject block : Json.objects(this.body(), "content")) {
      String toolUseId = Json.string(block, "tool_use_id");
      if ("tool_result".equals(Json.string(block, "type")) && toolUseId != null) {
        results.add(new ToolResult(toolUseId, Json.bool(block, "is_error")));
      }
    }
    return results;
  }

  /** What a Write or Edit did, which the CLI attaches to the user line carrying its result. Reading it removes it. */
  public @Nullable JsonObject takeToolUseResult() {
    JsonObject result = Json.object(this.json, "tool_use_result");
    this.json.remove("tool_use_result");
    return result;
  }

  /** The session's cumulative list-price cost a {@code result} reports, or null. */
  public @Nullable Double totalCostUsd() {
    return Json.isNumber(this.json, "total_cost_usd") ? Json.decimal(this.json, "total_cost_usd") : null;
  }

  public @Nullable JsonObject modelUsage() {
    return Json.object(this.json, "modelUsage");
  }

  public enum Type {
    USER,
    ASSISTANT,
    SYSTEM,
    RESULT,
    STREAM_EVENT,
    CONTROL_REQUEST,
    CONTROL_RESPONSE,
    OTHER;

    public String wireName() {
      return this.name().toLowerCase(Locale.ROOT);
    }

    private static final Map<String, Type> BY_WIRE_NAME = byWireName();

    static Type of(@Nullable String wireName) {
      return wireName == null ? OTHER : BY_WIRE_NAME.getOrDefault(wireName, OTHER);
    }

    private static Map<String, Type> byWireName() {
      Map<String, Type> types = new HashMap<>();
      for (Type type : values()) {
        if (type != OTHER) {
          types.put(type.wireName(), type);
        }
      }
      return types;
    }
  }

  public record ToolResult(
    String toolUseId,
    boolean isError
  ) {
  }
}
