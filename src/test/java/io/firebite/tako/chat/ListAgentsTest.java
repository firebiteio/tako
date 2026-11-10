package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.AgentSessionInfo;
import java.util.List;
import org.junit.Test;

public class ListAgentsTest {
  private static final AgentInfo.Capabilities NONE = new AgentInfo.Capabilities(false, false, false, false, false,
    false, false);

  @Test
  public void namesTheModelsAndModesOfAnAgentThatRan() {
    AgentCatalog catalog = new AgentCatalog();
    catalog.remember(new AgentInfo("claude", "Claude Code", "", NONE,
      List.of(new AgentInfo.Model("sonnet", "Sonnet", "Fast", null, List.of("low", "high")),
        new AgentInfo.Model("haiku", "Haiku", null, null, List.of())),
      "sonnet", List.of(new AgentInfo.Mode("plan", "Plan", "Plans only"), new AgentInfo.Mode("default", "Ask", "")),
      "plan", null, List.of()));

    JsonObject claude = agents(list(catalog, "")).get(0).getAsJsonObject();

    assertEquals("claude", claude.get("id").getAsString());
    assertEquals("Claude Code", claude.get("name").getAsString());
    assertTrue(claude.get("canStart").getAsBoolean());
    assertTrue(claude.get("isYours").getAsBoolean());
    assertFalse(claude.has("note"));
    JsonArray models = claude.getAsJsonArray("models");
    assertEquals(2, models.size());
    JsonObject sonnet = models.get(0).getAsJsonObject();
    assertEquals("sonnet", sonnet.get("value").getAsString());
    assertEquals("Sonnet", sonnet.get("name").getAsString());
    assertEquals("Fast", sonnet.get("description").getAsString());
    assertEquals("[\"low\",\"high\"]", sonnet.getAsJsonArray("efforts").toString());
    assertFalse(models.get(1).getAsJsonObject().has("description"));
    assertEquals(0, models.get(1).getAsJsonObject().getAsJsonArray("efforts").size());
    JsonArray modes = claude.getAsJsonArray("modes");
    assertEquals("plan", modes.get(0).getAsJsonObject().get("value").getAsString());
    assertEquals("Plan", modes.get(0).getAsJsonObject().get("label").getAsString());
    assertEquals("Plans only", modes.get(0).getAsJsonObject().get("description").getAsString());
    assertFalse(modes.get(1).getAsJsonObject().has("description"));
  }

  @Test
  public void tellsTheDefaultModelGoesForAnAgentThatDidNotRun() {
    JsonObject codex = agents(list(new AgentCatalog(), "")).get(1).getAsJsonObject();

    assertEquals("codex", codex.get("id").getAsString());
    assertFalse(codex.get("canStart").getAsBoolean());
    assertFalse(codex.has("isYours"));
    assertFalse(codex.has("models"));
    assertFalse(codex.has("modes"));
    assertTrue(codex.get("note").getAsString().contains("default"));
  }

  @Test
  public void carriesThePersonsNotes() {
    JsonObject result = list(new AgentCatalog(), "  sonnet: mechanical edits.\ncodex: debugging\n");

    assertEquals("sonnet: mechanical edits.\ncodex: debugging", result.get("notes").getAsString());
  }

  @Test
  public void leavesBlankNotesOut() {
    assertFalse(list(new AgentCatalog(), " \n ").has("notes"));
  }

  private static JsonObject list(AgentCatalog catalog, String notes) {
    return ThreadTools.agents(List.of(new Backend("claude", "Claude Code", true), new Backend("codex", "Codex", false)),
      "claude", catalog, notes);
  }

  private static JsonArray agents(JsonObject result) {
    return result.getAsJsonArray("agents");
  }

  private record Backend(
    String id,
    String displayName,
    boolean canStart
  ) implements AgentBackend {
    @Override
    public AgentSession start(AgentLaunch launch, AgentListener listener) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AgentHistory history(String projectPath, String sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<AgentSessionInfo> sessions(String projectPath, int limit) {
      return List.of();
    }
  }
}
