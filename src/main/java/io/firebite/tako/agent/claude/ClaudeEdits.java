package io.firebite.tako.agent.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.agent.claude.protocol.ClaudeTool;
import io.firebite.tako.json.Json;
import org.jetbrains.annotations.Nullable;

/** What a pending Write, Edit or MultiEdit tool call would make of its file. */
final class ClaudeEdits {
  private ClaudeEdits() {
  }

  /**
   * @param currentContent the file as it is on disk, null when it does not exist
   * @return null when the tool is not a text edit or its old text is not in the file
   */
  static @Nullable EditPreview preview(String toolName, JsonObject input, @Nullable String currentContent) {
    ClaudeTool tool = ClaudeTool.named(toolName);
    String filePath = Json.string(input, "file_path");
    if (tool == null || !tool.isTextEdit() || filePath == null) {
      return null;
    }
    String before = currentContent == null ? "" : currentContent;
    String after = switch (tool) {
      case WRITE -> Json.string(input, "content");
      case EDIT -> applyEdit(before, input);
      default -> applyEdits(before, Json.array(input, "edits"));
    };
    return after == null ? null : new EditPreview(filePath, before, after);
  }

  private static @Nullable String applyEdits(String content, @Nullable JsonArray edits) {
    if (edits == null) {
      return null;
    }
    String after = content;
    for (JsonElement edit : edits) {
      after = edit.isJsonObject() ? applyEdit(after, edit.getAsJsonObject()) : null;
      if (after == null) {
        return null;
      }
    }
    return after;
  }

  private static @Nullable String applyEdit(String content, JsonObject edit) {
    String oldText = Json.string(edit, "old_string");
    String newText = Json.string(edit, "new_string");
    if (oldText == null || newText == null) {
      return null;
    }
    boolean replaceAll = Json.bool(edit, "replace_all");
    String replaced = replace(content, oldText, newText, replaceAll);
    if (replaced != null || !content.contains("\r\n")) {
      return replaced;
    }
    String unix = replace(content.replace("\r\n", "\n"), oldText.replace("\r\n", "\n"), newText.replace("\r\n", "\n"),
      replaceAll);
    return unix == null ? null : unix.replace("\n", "\r\n");
  }

  private static @Nullable String replace(String content, String oldText, String newText, boolean replaceAll) {
    if (oldText.isEmpty()) {
      return content.isEmpty() ? newText : null;
    }
    int index = content.indexOf(oldText);
    if (index < 0) {
      return null;
    }
    if (replaceAll) {
      return content.replace(oldText, newText);
    }
    return content.substring(0, index) + newText + content.substring(index + oldText.length());
  }
}
