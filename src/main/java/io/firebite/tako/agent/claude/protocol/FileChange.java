package io.firebite.tako.agent.claude.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.json.Json;
import org.jetbrains.annotations.Nullable;

/**
 * What a Write or Edit did to a file, read from the {@code tool_use_result} the CLI attaches to its result.
 *
 * @param originalContent the file before the call, empty when the call created it
 */
public record FileChange(
  String filePath,
  String originalContent,
  int addedLines,
  int removedLines,
  boolean isNew
) {

  public static @Nullable FileChange fromToolResult(@Nullable JsonObject result) {
    String filePath = Json.string(result, "filePath");
    JsonArray patch = Json.array(result, "structuredPatch");
    if (filePath == null || patch == null || !result.has("originalFile")) {
      return null;
    }
    String original = Json.string(result, "originalFile");
    boolean isNew = "create".equals(Json.string(result, "type")) || original == null;
    int added = 0;
    int removed = 0;
    for (JsonElement hunk : patch) {
      JsonArray lines = hunk.isJsonObject() ? Json.array(hunk.getAsJsonObject(), "lines") : null;
      if (lines == null) {
        continue;
      }
      for (JsonElement line : lines) {
        String text = line.isJsonPrimitive() ? line.getAsString() : "";
        if (text.startsWith("+")) {
          added++;
        } else if (text.startsWith("-")) {
          removed++;
        }
      }
    }
    if (isNew && added == 0) {
      added = countLines(Json.string(result, "content"));
    }
    return new FileChange(filePath, original == null ? "" : original, added, removed, isNew);
  }

  private static int countLines(@Nullable String content) {
    if (content == null || content.isEmpty()) {
      return 0;
    }
    int lines = 1;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n' && i < content.length() - 1) {
        lines++;
      }
    }
    return lines;
  }
}
