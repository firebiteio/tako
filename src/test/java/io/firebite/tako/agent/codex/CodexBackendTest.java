package io.firebite.tako.agent.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class CodexBackendTest {

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
}
