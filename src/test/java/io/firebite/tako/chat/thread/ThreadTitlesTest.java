package io.firebite.tako.chat.thread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.agent.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ThreadTitlesTest {

  @Test
  public void promptHasTheFirstRequestAndTheLatestMessages() {
    List<ThreadTitles.Message> conversation = new ArrayList<>();
    conversation.add(new ThreadTitles.Message(true, "Fix the login bug"));
    for (int i = 1; i <= 8; i++) {
      conversation.add(new ThreadTitles.Message(i % 2 == 0, "message " + i));
    }

    String prompt = ThreadTitles.prompt(conversation);

    assertTrue(prompt.contains("First request:\nFix the login bug\n"));
    assertFalse(prompt.contains("message 2\n"));
    assertTrue(prompt.contains("Agent: message 3\nDeveloper: message 4\n"));
    assertTrue(prompt.endsWith("Developer: message 8\n"));
  }

  @Test
  public void promptRepeatsNoMessageOfAShortConversation() {
    String prompt = ThreadTitles.prompt(List.of(new ThreadTitles.Message(true, "Add dark mode"),
      new ThreadTitles.Message(false, "Done")));

    assertEquals(1, prompt.split("Add dark mode", -1).length - 1);
    assertTrue(prompt.endsWith("Latest messages:\nAgent: Done\n"));
  }

  @Test
  public void promptCutsLongMessages() {
    String prompt = ThreadTitles.prompt(List.of(new ThreadTitles.Message(true, "x".repeat(5000))));

    assertTrue(prompt.contains("x".repeat(799) + "…"));
    assertFalse(prompt.contains("x".repeat(800)));
  }

  @Test
  public void titleDropsLabelQuotesAndPeriod() {
    assertEquals("Fix login redirect", ThreadTitles.title("Title: \"Fix login redirect.\"\n"));
    assertEquals("Dark mode toggle", ThreadTitles.title("\n**Dark mode toggle**\nmore text"));
  }

  @Test
  public void titleIsCutToFitTheThreadList() {
    String title = ThreadTitles.title("A very long title that keeps going well past the limit");

    assertEquals(40, title.length());
    assertTrue(title.endsWith("…"));
  }

  @Test
  public void blankAnswerHasNoTitle() {
    assertNull(ThreadTitles.title(" \n\"\"\n"));
  }

  @Test
  public void eventsGivePromptsAndOwnAnswers() {
    List<ThreadTitles.Message> conversation = ThreadTitles.fromEvents(List.of(
      new AgentEvent.Prompt("Rename the class", false, List.of()),
      new AgentEvent.Text("m1", "From a subagent", "tool-1"),
      new AgentEvent.Text("m2", "Renamed it", null)));

    assertEquals(List.of(new ThreadTitles.Message(true, "Rename the class"),
      new ThreadTitles.Message(false, "Renamed it")), conversation);
  }
}
