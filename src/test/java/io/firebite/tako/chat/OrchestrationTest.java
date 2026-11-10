package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.PromptComposer;
import java.util.List;
import org.junit.Test;

public class OrchestrationTest {

  @Test
  public void instructionsNameTheToolsTheRoleUses() {
    String text = Orchestration.context("Orchestrator").text();

    for (String tool : List.of("list_agents", "create_thread", "wait_for_threads", "send_to_thread", "worktree: true",
      "wake: true", "until any")) {
      assertTrue(tool, text.contains(tool));
    }
    assertFalse(text.contains("—"));
  }

  @Test
  public void theChipCarriesTheLabel() {
    ContextItem item = Orchestration.context("Orchestrator");

    assertEquals("Orchestrator", item.label());
    assertEquals(ContextItem.Kind.SELECTION, item.kind());
  }

  @Test
  public void theTaskLeadsAndTranscriptsShowItAlone() {
    String prompt = PromptComposer.compose("port the parser", List.of(Orchestration.context("Orchestrator")));

    assertTrue(prompt.startsWith("port the parser\n\n<ide_context>\nYou are the orchestrator for the task above."));
    assertEquals("port the parser", PromptComposer.typedPart(prompt));
  }
}
