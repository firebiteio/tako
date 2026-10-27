package io.firebite.tako.chat.thread;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.ToolKind;
import java.util.List;
import org.junit.Test;

public class ConversationTest {

  @Test
  public void keepsPromptsAndAnswersWithoutToolsOrSubagents() {
    Conversation conversation = Conversation.of(List.of(
      prompt("fix the build"),
      new AgentEvent.ToolStarted("t1", "Bash", ToolKind.COMMAND, new JsonObject(), null, null),
      new AgentEvent.ToolFinished("t1", "ok", false, null),
      new AgentEvent.Text("m1", "Looking.", null),
      new AgentEvent.Text("m2", "subagent text", "task1"),
      new AgentEvent.Text("m3", "Fixed it.", null)));
    conversation.prompt("thanks ");
    conversation.add(new AgentEvent.Text("m4", "You're welcome.", null));

    assertEquals("Person: fix the build\n\nAgent: Looking.\n\nFixed it.\n\nPerson: thanks\n\nAgent: You're welcome.",
      conversation.text(1000));
  }

  @Test
  public void resetStartsOver() {
    Conversation conversation = new Conversation();
    conversation.prompt("old");
    conversation.reset(List.of(prompt("replayed")));

    assertEquals("Person: replayed", conversation.text(1000));
  }

  @Test
  public void leavesOutTheOldestTurnsWhenTooLong() {
    Conversation conversation = Conversation.of(List.of(prompt("first"), prompt("second"), prompt("third")));

    assertEquals("[1 earlier turn left out]\n\nPerson: second\n\nPerson: third", conversation.text(30));
  }

  @Test
  public void cutsATurnLongerThanTheLimit() {
    assertEquals("Person: ab [cut]", Conversation.of(List.of(prompt("abcdef"))).text(10));
  }

  private static AgentEvent prompt(String text) {
    return new AgentEvent.Prompt(text, false, List.of());
  }
}
