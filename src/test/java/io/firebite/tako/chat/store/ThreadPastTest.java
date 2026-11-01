package io.firebite.tako.chat.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.AgentEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class ThreadPastTest {

  @Test
  public void survivesAWriteAndRead() throws Exception {
    Path file = Files.createTempDirectory("past").resolve("nested").resolve("t.json");
    ThreadPast past = new ThreadPast(List.of(new ThreadPast.Segment("Claude Code", "Codex",
      List.of(new ThreadPast.Turn("ünïcödé 😀 \"quoted\"", "answer\nwith lines"), new ThreadPast.Turn(null, "late")))),
      List.of("a.txt"), "- [pending] x", "the plan");

    past.write(file);

    assertEquals(past, ThreadPast.read(file));
  }

  @Test
  public void aMissingOrBrokenFileReadsAsEmpty() throws Exception {
    Path folder = Files.createTempDirectory("past");
    assertSame(ThreadPast.EMPTY, ThreadPast.read(folder.resolve("missing.json")));
    Path broken = folder.resolve("broken.json");
    Files.writeString(broken, "{\"segments\": [", StandardCharsets.UTF_8);
    assertSame(ThreadPast.EMPTY, ThreadPast.read(broken));
  }

  @Test
  public void missingFieldsReadAsEmpty() throws Exception {
    Path file = Files.createTempDirectory("past").resolve("t.json");
    Files.writeString(file, "{\"segments\": [{\"turns\": [{\"prompt\": \"hi\"}, null]}, null]}",
      StandardCharsets.UTF_8);

    ThreadPast past = ThreadPast.read(file);

    assertEquals(1, past.segments().size());
    assertEquals("", past.segments().getFirst().agentName());
    assertEquals(List.of(new ThreadPast.Turn("hi", "")), past.segments().getFirst().turns());
    assertTrue(past.files().isEmpty());
  }

  @Test
  public void replaysEachSegmentFollowedByItsSwitchWithoutBlankAnswers() {
    ThreadPast past = new ThreadPast(List.of(
      new ThreadPast.Segment("Claude Code", "Codex", List.of(new ThreadPast.Turn("first", "one"))),
      new ThreadPast.Segment("Codex", "Gemini", List.of(new ThreadPast.Turn("second", " ")))),
      List.of(), null, null);

    assertEquals(List.of(
      new AgentEvent.Prompt("first", false, List.of()),
      new AgentEvent.Text(null, "one", null),
      new AgentEvent.AgentSwitched("Claude Code", "Codex"),
      new AgentEvent.Prompt("second", false, List.of()),
      new AgentEvent.AgentSwitched("Codex", "Gemini")), past.events());
    assertEquals(1, past.firstSegments(1).segments().size());
    assertEquals(0, past.firstSegments(0).segments().size());
    assertSame(past, past.firstSegments(5));
    assertEquals(2, past.turns().size());
  }
}
