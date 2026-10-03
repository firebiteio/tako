package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.firebite.tako.agent.EditPreview;
import org.junit.Test;

public class ClaudeEditsTest {

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  @Test
  public void editReplacesTheFirstOccurrence() {
    EditPreview preview = ClaudeEdits.preview("Edit",
      json("{\"file_path\":\"a.txt\",\"old_string\":\"b\",\"new_string\":\"X\"}"), "a b c b");

    assertEquals("a b c b", preview.before());
    assertEquals("a X c b", preview.after());
  }

  @Test
  public void editReplaceAll() {
    EditPreview preview = ClaudeEdits.preview("Edit",
      json("{\"file_path\":\"a.txt\",\"old_string\":\"b\",\"new_string\":\"X\",\"replace_all\":true}"), "a b c b");

    assertEquals("a X c X", preview.after());
  }

  @Test
  public void editMatchesLfTextInCrlfFile() {
    EditPreview preview = ClaudeEdits.preview("Edit",
      json("{\"file_path\":\"a.txt\",\"old_string\":\"one\\ntwo\",\"new_string\":\"1\\n2\"}"), "one\r\ntwo\r\nthree");

    assertEquals("1\r\n2\r\nthree", preview.after());
  }

  @Test
  public void editWithMissingTextHasNoPreview() {
    assertNull(ClaudeEdits.preview("Edit",
      json("{\"file_path\":\"a.txt\",\"old_string\":\"zzz\",\"new_string\":\"X\"}"), "abc"));
  }

  @Test
  public void writeOfNewFileStartsEmpty() {
    EditPreview preview = ClaudeEdits.preview("Write", json("{\"file_path\":\"new.txt\",\"content\":\"hi\"}"), null);

    assertEquals("", preview.before());
    assertEquals("hi", preview.after());
  }

  @Test
  public void multiEditAppliesInOrder() {
    EditPreview preview = ClaudeEdits.preview("MultiEdit", json("{\"file_path\":\"a.txt\",\"edits\":["
      + "{\"old_string\":\"a\",\"new_string\":\"b\"},{\"old_string\":\"b\",\"new_string\":\"c\",\"replace_all\":true}]}"),
      "a b");

    assertEquals("c c", preview.after());
  }

  @Test
  public void otherToolsHaveNoPreview() {
    assertNull(ClaudeEdits.preview("Bash", json("{\"file_path\":\"a.txt\",\"command\":\"ls\"}"), "x"));
  }
}
