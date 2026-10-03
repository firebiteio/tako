package io.firebite.tako.agent.claude.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;
import org.junit.Test;

public class FileChangeTest {

  @Test
  public void editCountsPatchLines() {
    FileChange change = FileChange
      .fromToolResult(JsonParser.parseString("{\"filePath\":\"a.txt\",\"originalFile\":\"x\\ny\\n\","
        + "\"structuredPatch\":[{\"lines\":[\" x\",\"-y\",\"+z\",\"+w\"]}]}").getAsJsonObject());

    assertEquals("x\ny\n", change.originalContent());
    assertEquals(2, change.addedLines());
    assertEquals(1, change.removedLines());
    assertFalse(change.isNew());
  }

  @Test
  public void createdFileStartsEmptyAndCountsContent() {
    FileChange change = FileChange.fromToolResult(JsonParser.parseString("{\"type\":\"create\",\"filePath\":\"b.txt\","
      + "\"content\":\"1\\n2\\n3\\n\",\"structuredPatch\":[],\"originalFile\":null}").getAsJsonObject());

    assertTrue(change.isNew());
    assertEquals("", change.originalContent());
    assertEquals(3, change.addedLines());
  }

  @Test
  public void readResultIsNoChange() {
    assertNull(FileChange.fromToolResult(JsonParser.parseString("{\"type\":\"text\",\"file\":{}}").getAsJsonObject()));
  }
}
