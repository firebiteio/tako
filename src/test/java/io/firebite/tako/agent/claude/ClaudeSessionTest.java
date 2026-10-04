package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

public class ClaudeSessionTest {
  @Test
  public void scratchpadLivesUnderTheCliTempFolder() {
    assertEquals(Path.of("/t", "claude", "D--work-app", "s1", "scratchpad"),
      ClaudeSession.scratchpadDirectory(Map.of("TEMP", "/t"), "D:\\work\\app", "s1"));
    assertEquals(Path.of("/own", "claude", "-work-app", "s1", "scratchpad"),
      ClaudeSession.scratchpadDirectory(Map.of("CLAUDE_CODE_TMPDIR", "/own", "TMPDIR", "/t"), "/work/app", "s1"));
  }
}
