package io.firebite.tako.agent.claude.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.Test;

public class TranscriptTailTest {

  @Test
  public void returnsOnlyNewCompleteLines() throws Exception {
    Path file = Files.createTempDirectory("tail").resolve("agent-x.jsonl");
    TranscriptTail tail = new TranscriptTail(file);
    assertTrue(tail.poll().isEmpty());

    Files.writeString(file, "{\"n\":1}\n{\"n\":2}\n{\"n\":", StandardCharsets.UTF_8);
    List<JsonObject> first = tail.poll();
    assertEquals(2, first.size());
    assertEquals(2, first.get(1).get("n").getAsInt());

    Files.writeString(file, "3}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    List<JsonObject> second = tail.poll();
    assertEquals(1, second.size());
    assertEquals(3, second.get(0).get("n").getAsInt());
    assertTrue(tail.poll().isEmpty());
  }
}
