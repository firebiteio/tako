package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.AgentEvent;
import org.junit.Test;

public class PromptEchoesTest {
  @Test
  public void promptEchoedBeforeTheResultJoinsTheTurn() {
    PromptEchoes echoes = new PromptEchoes();
    assertFalse(echoes.sent("first", 1));
    assertTrue(echoes.sent("follow-up", 2));
    assertNull(echoes.echoed("first"));
    assertEquals(new AgentEvent.PromptTaken(2, false), echoes.echoed("follow-up"));
  }

  @Test
  public void promptEchoedAfterTheResultStartsTheNextTurn() {
    PromptEchoes echoes = new PromptEchoes();
    echoes.sent("first", 1);
    echoes.sent("second", 2);
    echoes.echoed("first");
    echoes.turnEnded();
    assertTrue(echoes.sent("third", 3));
    assertEquals(new AgentEvent.PromptTaken(2, true), echoes.echoed("second"));
    echoes.turnEnded();
    assertEquals(new AgentEvent.PromptTaken(3, true), echoes.echoed("third"));
    echoes.turnEnded();
    assertFalse(echoes.sent("fourth", 4));
  }

  @Test
  public void unechoedSlashCommandIsSkipped() {
    PromptEchoes echoes = new PromptEchoes();
    echoes.sent("/context", 1);
    echoes.turnEnded();
    assertFalse(echoes.sent("hello", 2));
    assertTrue(echoes.sent("again", 3));
    assertNull(echoes.echoed("hello"));
    assertEquals(new AgentEvent.PromptTaken(3, false), echoes.echoed("again"));
  }

  @Test
  public void imagesAloneEchoEmpty() {
    PromptEchoes echoes = new PromptEchoes();
    echoes.sent("x", 1);
    echoes.sent("  ", 2);
    echoes.turnEnded();
    assertEquals(new AgentEvent.PromptTaken(2, true), echoes.echoed(""));
  }
}
