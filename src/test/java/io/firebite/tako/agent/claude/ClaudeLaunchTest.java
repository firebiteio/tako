package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.settings.AuthMode;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ClaudeLaunchTest {

  @Test
  public void apiKeyModeSetsTheKeyAndStreamJsonFlags() {
    ClaudeLaunch launch = ClaudeLaunch.build("claude", AuthMode.API_KEY, "sk-test",
      new ClaudeLaunch.Options("sonnet", "plan", "max", null, List.of(), null), Map.of("PATH", "/bin"));

    assertEquals("sk-test", launch.environment().get("ANTHROPIC_API_KEY"));
    assertEquals("/bin", launch.environment().get("PATH"));
    String command = String.join(" ", launch.command());
    assertTrue(command.startsWith("claude -p --input-format stream-json --output-format stream-json"));
    assertTrue(command.contains("--permission-prompt-tool stdio"));
    assertTrue(command.contains("--model sonnet"));
    assertTrue(command.contains("--permission-mode plan"));
    assertTrue(command.contains("--effort max"));
    assertFalse(command.contains("--resume"));
  }

  @Test
  public void cliLoginModeDropsAnInheritedKey() {
    ClaudeLaunch launch = ClaudeLaunch.build("claude", AuthMode.CLI_LOGIN, null,
      new ClaudeLaunch.Options("", "", "", "abc", List.of(), null), Map.of("ANTHROPIC_API_KEY", "sk-inherited"));

    assertFalse(launch.environment().containsKey("ANTHROPIC_API_KEY"));
    String command = String.join(" ", launch.command());
    assertTrue(command.endsWith("--resume abc"));
    assertFalse(command.contains("--model"));
    assertFalse(command.contains("--permission-mode"));
    assertFalse(command.contains("--effort"));
  }

  @Test
  public void additionalDirectoriesBecomeAddDirFlags() {
    ClaudeLaunch launch = ClaudeLaunch.build("claude", AuthMode.CLI_LOGIN, null,
      new ClaudeLaunch.Options(null, null, null, null, List.of("D:/shared-lib", "D:/api"), null), Map.of());

    String command = String.join(" ", launch.command());
    assertTrue(command.contains("--add-dir D:/shared-lib --add-dir D:/api"));
  }

  @Test
  public void mcpConfigBecomesAFlag() {
    ClaudeLaunch launch = ClaudeLaunch.build("claude", AuthMode.CLI_LOGIN, null,
      new ClaudeLaunch.Options(null, null, null, null, List.of(), "C:/tmp/tako-mcp.json"), Map.of());

    assertTrue(String.join(" ", launch.command()).contains("--mcp-config C:/tmp/tako-mcp.json"));
  }

  @Test
  public void askUsesASmallModelWithoutToolsOrSavedSession() {
    ClaudeLaunch launch = ClaudeLaunch.ask("claude", AuthMode.API_KEY, "sk-test", Map.of());

    assertEquals(List.of("claude", "-p", "--model", "haiku", "--tools", "", "--strict-mcp-config",
      "--no-session-persistence", "--output-format", "text"), launch.command());
    assertEquals("sk-test", launch.environment().get("ANTHROPIC_API_KEY"));
  }

  @Test(expected = IllegalStateException.class)
  public void apiKeyModeWithoutKeyFails() {
    ClaudeLaunch.build("claude", AuthMode.API_KEY, " ", ClaudeLaunch.Options.defaults(), Map.of());
  }
}
