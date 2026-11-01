package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ClaudeBackendTest {
  private static final String SESSION = "11111111-2222-3333-4444-555555555555";
  private static final String PROJECT = "D:/Work/project";

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private ClaudeBackend backend;
  private Path sessions;

  @Before
  public void writeTranscript() throws Exception {
    Path config = this.folder.getRoot().toPath();
    this.backend = new ClaudeBackend(Map.of("CLAUDE_CONFIG_DIR", config.toString()));
    this.sessions = config.resolve("projects").resolve(SessionStore.encode(PROJECT));
    Files.createDirectories(this.sessions);
    Files.write(this.sessions.resolve(SESSION + ".jsonl"), List.of(
      user("u1", "First question"),
      assistant("a1", "u1", "First answer"),
      "{\"type\":\"cost-state\",\"sessionId\":\"" + SESSION + "\"}",
      "{\"type\":\"last-prompt\",\"leafUuid\":\"a2\",\"sessionId\":\"" + SESSION + "\"}",
      user("u2", "Second question"),
      assistant("a2", "u2", "Second answer")), StandardCharsets.UTF_8);
  }

  @Test
  public void forksUpToThePromptAfterTheKeptOnes() throws Exception {
    String fork = this.backend.fork(PROJECT, SESSION, 1);

    assertNotEquals(SESSION, fork);
    List<String> lines = Files.readAllLines(this.sessions.resolve(fork + ".jsonl"), StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    for (String line : lines) {
      assertTrue(line.contains("\"sessionId\":\"" + fork + "\""));
    }
    assertEquals(List.of("First question"), prompts(this.backend.history(PROJECT, fork)));
    assertEquals(List.of("First question", "Second question"), prompts(this.backend.history(PROJECT, SESSION)));
  }

  @Test
  public void findsTextInPromptsAndAnswers() throws Exception {
    assertTrue(this.backend.sessionMentions(PROJECT, SESSION, "second ANSWER"));
    assertTrue(this.backend.sessionMentions(PROJECT, SESSION, "first question"));
    assertFalse(this.backend.sessionMentions(PROJECT, SESSION, "third"));

    String fork = this.backend.fork(PROJECT, SESSION, 1);
    assertFalse(this.backend.sessionMentions(PROJECT, fork, "second"));
  }

  @Test
  public void rewindCopiesUpToTheForgottenPromptAndKeepsTheOriginal() throws Exception {
    String rewound = this.backend.rewind(PROJECT, SESSION, List.of("First  question", "Second question\n"));

    assertNotEquals(SESSION, rewound);
    assertEquals(List.of("First question"), prompts(this.backend.history(PROJECT, rewound)));
    assertEquals(List.of("First question", "Second question"), prompts(this.backend.history(PROJECT, SESSION)));
  }

  @Test
  public void rewindChecksEveryPromptBeforeWritingAnything() throws Exception {
    List<List<String>> wrong = List.of(
      List.of("First question", "Other question"),
      List.of("Other question", "Second question"),
      List.of("Second question"),
      List.of("First question", "Second question", "Third question"));
    for (List<String> prompts : wrong) {
      AgentException error = assertThrows(AgentException.class, () -> this.backend.rewind(PROJECT, SESSION, prompts));
      assertEquals(TakoBundle.message("error.rewind.mismatch"), error.getMessage());
    }
    assertEquals("no copy was written", 1, this.sessions.toFile().list().length);
  }

  @Test
  public void rewindMatchesUnicodeAndSlashCommandsWithArguments() throws Exception {
    Files.write(this.sessions.resolve(SESSION + ".jsonl"), List.of(
      user("u1", "Grüße 🐙 日本"),
      assistant("a1", "u1", "Hallo"),
      user("u2", "<command-name>/review</command-name><command-args>src</command-args>"),
      assistant("a2", "u2", "Reviewed"),
      user("u3", "Third")), StandardCharsets.UTF_8);

    String rewound = this.backend.rewind(PROJECT, SESSION, List.of("Grüße 🐙 日本", "/review src", "Third"));

    assertEquals(List.of("Grüße 🐙 日本", "/review"), prompts(this.backend.history(PROJECT, rewound)));
  }

  @Test
  public void rewindOfAMissingSessionFails() {
    assertThrows(AgentException.class,
      () -> this.backend.rewind(PROJECT, "99999999-2222-3333-4444-555555555555", List.of("First question")));
  }

  private static List<String> prompts(AgentHistory history) {
    List<String> prompts = new ArrayList<>();
    for (AgentEvent event : ((AgentHistory.Loaded) history).events()) {
      if (event instanceof AgentEvent.Prompt prompt) {
        prompts.add(prompt.text());
      }
    }
    return prompts;
  }

  private static String user(String uuid, String text) {
    return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"sessionId\":\"" + SESSION
      + "\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}";
  }

  private static String assistant(String uuid, String parent, String text) {
    return "{\"type\":\"assistant\",\"uuid\":\"" + uuid + "\",\"parentUuid\":\"" + parent + "\",\"sessionId\":\""
      + SESSION + "\",\"message\":{\"id\":\"m-" + uuid + "\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\","
      + "\"text\":\"" + text + "\"}]}}";
  }
}
