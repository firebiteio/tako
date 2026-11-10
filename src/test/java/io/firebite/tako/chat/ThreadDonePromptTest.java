package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.chat.thread.ChatController.State;
import org.junit.Test;

public class ThreadDonePromptTest {

  @Test
  public void namesTheThreadItsAgentStateAndAnswer() {
    String text = ThreadDonePrompt.compose("t1", "Port the parser", "codex", State.IDLE,
      "Ported it. Tests pass.\n", null);

    assertEquals("""
      Tako thread "Port the parser" (threadId t1, agent codex) is done with the prompt you handed it. State: idle.

      Its answer:
      Ported it. Tests pass.""", text);
  }

  @Test
  public void aStoppedThreadCarriesItsError() {
    String text = ThreadDonePrompt.compose("t1", "Port", "claude", State.STOPPED, "", "exit code 3: out of memory");

    assertEquals("""
      Tako thread "Port" (threadId t1, agent claude) is done with the prompt you handed it. State: stopped.
      Error: exit code 3: out of memory
      It wrote no answer.""", text);
  }

  @Test
  public void aLongAnswerIsCutWithANote() {
    String answer = "a".repeat(ThreadDonePrompt.ANSWER_LENGTH + 500);

    String text = ThreadDonePrompt.compose("t1", "Port", "claude", State.IDLE, answer, null);

    assertTrue(text.endsWith("…\n\nThe answer is cut to 4000 of its 4500 characters. get_thread gives all of it."));
    assertFalse(text.contains("a".repeat(ThreadDonePrompt.ANSWER_LENGTH)));
    assertFalse(text.contains("—"));
  }
}
