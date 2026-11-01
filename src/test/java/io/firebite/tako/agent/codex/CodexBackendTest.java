package io.firebite.tako.agent.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentDefaults;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.FakeExecutable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CodexBackendTest {
  private static final String THREAD = "019a0000-0000-7000-8000-000000000001";

  @Test
  public void askUsesASmallModelWithoutUserConfigOrTools() {
    List<String> command = CodexBackend.askCommand("codex");
    String line = String.join(" ", command);

    assertTrue(line.startsWith("codex exec --ephemeral --skip-git-repo-check --ignore-user-config --ignore-rules "
      + "--sandbox read-only"));
    assertTrue(line.contains("-m gpt-5.4-mini"));
    assertTrue(line.contains("-c web_search=disabled"));
    assertTrue(line.contains("-c features.shell_tool=false -c features.unified_exec=false"));
    assertTrue(line.contains("-c features.hooks=false"));
    assertEquals("-", command.getLast());
  }

  @Test
  public void rewindRevertsToTheTurnOfTheFirstForgottenPrompt() throws Exception {
    Path log = Files.createTempFile("fake-codex", ".log");

    assertEquals(THREAD, backend(log, false).rewind(folder(), THREAD, List.of("first", "second")));
    assertEquals("the turn without a user message is not counted", "t3", Files.readString(log,
      StandardCharsets.UTF_8));

    assertEquals(THREAD, backend(log, false).rewind(folder(), THREAD, List.of("first", "second", "steer", "third")));
    assertEquals("a follow-up in a turn counts as a prompt", "t4", Files.readString(log, StandardCharsets.UTF_8));

    backend(log, false).rewind(folder(), THREAD, List.of("first"));
    assertEquals("t1", Files.readString(log, StandardCharsets.UTF_8));
  }

  @Test
  public void rewindSendsNoRevertWhenThePromptsDoNotMatch() throws Exception {
    Path log = Files.createTempFile("fake-codex", ".log");
    List<List<String>> wrong = List.of(
      List.of("first", "second", "steer"),
      List.of("first", "other"),
      List.of("other", "second"),
      List.of("first", "second", "steer", "third", "fourth"));
    for (List<String> prompts : wrong) {
      AgentException error = assertThrows(AgentException.class,
        () -> backend(log, false).rewind(folder(), THREAD, prompts));
      assertEquals(TakoBundle.message("error.rewind.mismatch"), error.getMessage());
    }
    assertTrue(Files.readString(log, StandardCharsets.UTF_8).isEmpty());
  }

  @Test
  public void aFailedRevertSaysWhy() throws Exception {
    Path log = Files.createTempFile("fake-codex", ".log");

    AgentException error = assertThrows(AgentException.class,
      () -> backend(log, true).rewind(folder(), THREAD, List.of("first", "second")));

    assertTrue(error.getMessage(), error.getMessage().contains("legacy history"));
    assertFalse(error.getMessage().equals(TakoBundle.message("error.rewind.mismatch")));
  }

  @Test
  public void aMissingCodexCannotRewind() {
    CodexBackend backend = new CodexBackend("Z:/no/such/codex", AgentDefaults.NONE, Map.of("PATH", ""));

    assertThrows(AgentException.class, () -> backend.rewind(folder(), THREAD, List.of("first")));
  }

  private static CodexBackend backend(Path log, boolean failsRevert) throws Exception {
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("FAKE_CODEX_LOG", log.toString());
    if (failsRevert) {
      environment.put("FAKE_CODEX_REVERT", "fail");
    }
    Path codex = FakeExecutable.write("codex", FakeCodex.class);
    return new CodexBackend(codex.toString(), AgentDefaults.NONE, environment);
  }

  private static String folder() {
    return System.getProperty("java.io.tmpdir");
  }
}
