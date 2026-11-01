package io.firebite.tako.chat.thread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.chat.store.ThreadPast;
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
    conversation.send(1, "thanks ", 0);
    conversation.take(1);
    conversation.add(new AgentEvent.Text("m4", "You're welcome.", null));

    assertEquals("Person: fix the build\n\nAgent: Looking.\n\nFixed it.\n\nPerson: thanks\n\nAgent: You're welcome.",
      conversation.text(1000));
  }

  @Test
  public void aQueuedPromptCountsOnceTaken() {
    Conversation conversation = new Conversation();
    conversation.send(1, "first", 0);
    conversation.take(1);
    conversation.add(new AgentEvent.Text("m1", "Working on it.", null));
    conversation.send(2, "second", 0);
    conversation.add(new AgentEvent.Text("m2", "First is done.", null));
    conversation.take(2);
    conversation.add(new AgentEvent.Text("m3", "Second is done.", null));

    assertEquals("Person: first\n\nAgent: Working on it.\n\nFirst is done.\n\nPerson: second\n\nAgent: Second is done.",
      conversation.text(1000));
  }

  @Test
  public void leavesOutSlashCommandsAndNamesImageOnlyPrompts() {
    Conversation conversation = Conversation.of(List.of(prompt("/compact"), prompt("")));
    conversation.send(1, "/clear", 1);
    conversation.take(1);

    assertEquals("Person: [images]", conversation.text(1000));
  }

  @Test
  public void countsTheImagesOfAPrompt() {
    Conversation conversation = Conversation.of(List.of(new AgentEvent.Prompt("look", false,
      List.of(new ImageAttachment("image/png", "AA==", "a.png")))));
    conversation.send(1, "and these", 2);
    conversation.take(1);

    assertEquals("Person: look [1 image]\n\nPerson: and these [2 images]", conversation.text(1000));
  }

  @Test
  public void resetStartsOver() {
    Conversation conversation = new Conversation();
    conversation.send(1, "old", 0);
    conversation.take(1);
    conversation.reset(List.of(prompt("replayed")));

    assertEquals("Person: replayed", conversation.text(1000));
  }

  @Test
  public void leavesOutTheOldestTurnsWhenTooLong() {
    Conversation conversation = Conversation.of(List.of(prompt("first"), prompt("second"), prompt("third")));

    assertEquals("[1 earlier turn left out]\n\nPerson: second\n\nPerson: third", conversation.text(30));
  }

  @Test
  public void keepsTheEndOfATurnLongerThanTheLimit() {
    assertEquals("[cut] n: abcdef", Conversation.of(List.of(prompt("abcdef"))).text(9));
  }

  @Test
  public void keepsChangedFilesNewestLastAndTheMainAgentsLatestTodosAndPlan() {
    Conversation conversation = Conversation.of(List.of(
      prompt("go"),
      todos("t1", null, "write tests", "pending"),
      todos("t2", "task1", "subagent todo", "pending"),
      changed("a.txt"),
      changed("b.txt"),
      changed("a.txt"),
      todos("t3", null, "write tests", "completed"),
      plan("Step one, then step two.")));

    Handoff.Input input = conversation.handoffInput();

    assertEquals(List.of("b.txt", "a.txt"), input.files());
    assertEquals("- [completed] write tests", input.todos());
    assertEquals("Step one, then step two.", input.plan());
    assertEquals(List.of(new ThreadPast.Turn("go", "")), input.turns());
  }

  @Test
  public void pastHoldsTheTurnsSinceTheLastSwitchAndRestoreKeepsThemAsBaseline() {
    Conversation conversation = Conversation.of(List.of(prompt("first"), text("one"), changed("a.txt")));
    ThreadPast first = conversation.past("Claude Code", "Codex");
    conversation.restore(first);
    conversation.send(1, "second", 0);
    conversation.take(1);
    conversation.add(text("two"));

    ThreadPast second = conversation.past("Codex", "Gemini");

    assertEquals(2, second.segments().size());
    assertEquals(List.of(new ThreadPast.Turn("first", "one")), second.segments().get(0).turns());
    assertEquals(List.of(new ThreadPast.Turn("second", "two")), second.segments().get(1).turns());
    assertEquals("Codex", second.segments().get(1).agentName());
    assertEquals("Gemini", second.segments().get(1).nextAgentName());
    assertEquals(List.of("a.txt"), second.files());

    conversation.reset(List.of(prompt("replayed")));
    assertEquals("Person: first\n\nAgent: one\n\nPerson: replayed", conversation.text(1000));
    assertEquals(List.of("a.txt"), conversation.handoffInput().files());
  }

  @Test
  public void textWithoutAPromptAfterASwitchBelongsToTheNewAgent() {
    Conversation conversation = Conversation.of(List.of(prompt("first"), text("one")));
    conversation.restore(conversation.past("Claude Code", "Codex"));
    conversation.send(1, "/status", 0);
    conversation.take(1);
    conversation.add(text("status output"));

    ThreadPast past = conversation.past("Codex", "Claude Code");

    assertEquals(List.of(new ThreadPast.Turn("first", "one")), past.segments().get(0).turns());
    assertEquals(List.of(new ThreadPast.Turn(null, "status output")), past.segments().get(1).turns());
  }

  @Test
  public void anEmptyConversationGivesAnEmptySegment() {
    Conversation conversation = new Conversation();
    assertTrue(conversation.isEmpty());

    ThreadPast past = conversation.past("Claude Code", "Codex");

    assertEquals(1, past.segments().size());
    assertTrue(past.segments().getFirst().turns().isEmpty());
    conversation.restore(past);
    assertTrue(conversation.isEmpty());
    conversation.add(prompt("hi"));
    assertFalse(conversation.isEmpty());
  }

  private static AgentEvent prompt(String text) {
    return new AgentEvent.Prompt(text, false, List.of());
  }

  private static AgentEvent text(String text) {
    return new AgentEvent.Text(null, text, null);
  }

  private static AgentEvent changed(String path) {
    return new AgentEvent.ToolFinished("edit-" + path, "", false, new AgentEvent.Change(path, 1, 0, false));
  }

  private static AgentEvent todos(String id, String parentId, String content, String status) {
    JsonArray todos = new JsonArray();
    todos.add(AgentEvent.todo(content, status));
    JsonObject input = new JsonObject();
    input.add("todos", todos);
    return new AgentEvent.ToolStarted(id, "TodoWrite", ToolKind.TODO, input, null, parentId);
  }

  private static AgentEvent plan(String plan) {
    JsonObject input = new JsonObject();
    input.addProperty("plan", plan);
    return new AgentEvent.ToolStarted("p1", "ExitPlanMode", ToolKind.PLAN, input, null, null);
  }
}
