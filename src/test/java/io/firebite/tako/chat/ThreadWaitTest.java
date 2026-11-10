package io.firebite.tako.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.claude.FakeClaude;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.QueuedPrompts;
import io.firebite.tako.mcp.McpToolException;
import io.firebite.tako.settings.AuthMode;
import io.firebite.tako.settings.ChatSettings;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The wait and wake tools on threads running the fake Claude. Without a chat panel, a prompt the tools send leaves its
 * thread working for good, as nothing hands it to the agent; the tests hand it over themselves where it should run.
 */
public class ThreadWaitTest extends BasePlatformTestCase {
  private static final long TIMEOUT_MILLIS = 20_000;

  private ChatService service;
  private ThreadStore store;
  private Set<String> before;
  private String claudePath;
  private AuthMode authMode;
  private ThreadStore.Record parent;
  private ThreadTools tools;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.service = ChatService.getInstance(this.getProject());
    this.store = ThreadStore.getInstance(this.getProject());
    this.before = this.ids();
    ChatSettings.Data settings = ChatSettings.state();
    this.claudePath = settings.claudePath;
    this.authMode = settings.authMode;
    settings.claudePath = FakeExecutable.write("claude", FakeClaude.class).toString();
    settings.authMode = AuthMode.CLI_LOGIN;
    this.parent = this.store.create(null, "Orchestrator", null, "claude");
    this.tools = new ThreadTools(this.service, this.store, this.parent.id);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      for (String id : this.ids()) {
        if (!this.before.contains(id)) {
          this.service.close(id);
        }
      }
      ChatSettings.Data settings = ChatSettings.state();
      settings.claudePath = this.claudePath;
      settings.authMode = this.authMode;
    } finally {
      super.tearDown();
    }
  }

  private Set<String> ids() {
    Set<String> ids = new HashSet<>();
    for (ThreadStore.Record record : this.store.all()) {
      ids.add(record.id);
    }
    return ids;
  }

  /** An open thread whose agent started and is idle. */
  private ThreadStore.Record idleThread(String title) throws Exception {
    ThreadStore.Record record = this.store.create(null, title, null, "claude");
    record.workPath = Files.createTempDirectory("tako-wait").toString();
    this.service.openThread(record);
    assertEquals(ChatController.State.IDLE, this.controller(record).awaitSettled(TIMEOUT_MILLIS));
    return record;
  }

  /** A thread that got a prompt from the tools and works on it for good. */
  private ThreadStore.Record workingThread() throws Exception {
    ThreadStore.Record record = this.idleThread("Working");
    this.call(ThreadTools.SEND, this.prompt(record, "keep going", false));
    assertEquals(ChatController.State.WORKING, this.controller(record).state());
    return record;
  }

  private ChatController controller(ThreadStore.Record record) {
    ChatController controller = this.service.controller(record.id);
    assertNotNull(controller);
    return controller;
  }

  private JsonObject prompt(ThreadStore.Record record, String prompt, boolean wake) {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("threadId", record.id);
    arguments.addProperty("prompt", prompt);
    arguments.addProperty("wake", wake);
    return arguments;
  }

  /** Runs the prompt the tools sent, as the chat panel would. */
  private void run(ThreadStore.Record record, String prompt) {
    this.controller(record).send(prompt, List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
  }

  private JsonObject call(String tool, JsonObject arguments) throws McpToolException {
    return JsonParser.parseString(this.tools.call(tool, arguments)).getAsJsonObject();
  }

  private JsonObject waitFor(String until, long timeoutSeconds, ThreadStore.Record... records)
    throws McpToolException {
    JsonObject arguments = new JsonObject();
    JsonArray ids = new JsonArray();
    for (ThreadStore.Record record : records) {
      ids.add(record.id);
    }
    arguments.add("threadIds", ids);
    arguments.addProperty("until", until);
    arguments.addProperty("timeoutSeconds", timeoutSeconds);
    return this.call(ThreadTools.WAIT_ALL, arguments);
  }

  private static List<String> states(JsonObject result) {
    List<String> states = new ArrayList<>();
    for (JsonElement thread : result.getAsJsonArray("threads")) {
      states.add(thread.getAsJsonObject().get("state").getAsString());
    }
    return states;
  }

  public void testWaitForAnyReturnsOnceOneIsDone() throws Exception {
    ThreadStore.Record working = this.workingThread();
    ThreadStore.Record idle = this.idleThread("Idle");

    JsonObject result = this.waitFor("any", 60, working, idle);

    assertEquals(List.of("working", "idle"), states(result));
    assertFalse(result.has("timedOut"));
  }

  public void testWaitForAllTimesOutWhileOneWorks() throws Exception {
    ThreadStore.Record working = this.workingThread();
    ThreadStore.Record idle = this.idleThread("Idle");

    JsonObject result = this.waitFor("all", 1, working, idle);

    assertEquals(List.of("working", "idle"), states(result));
    assertTrue(result.get("timedOut").getAsBoolean());
  }

  public void testWaitForAllReturnsOnceEachTurnEnded() throws Exception {
    ThreadStore.Record first = this.idleThread("First");
    ThreadStore.Record second = this.idleThread("Second");
    this.run(first, "fail first");
    this.run(second, "fail second");

    JsonObject result = this.waitFor("all", 60, first, second);

    assertEquals(List.of("idle", "idle"), states(result));
    assertFalse(result.has("timedOut"));
    assertEquals(first.id, result.getAsJsonArray("threads").get(0).getAsJsonObject().get("threadId").getAsString());
  }

  public void testWaitForTheCallersOwnThreadOrNoneIsRefused() throws Exception {
    ThreadStore.Record idle = this.idleThread("Idle");

    try {
      this.waitFor("all", 1, idle, this.parent);
      fail("waited for itself");
    } catch (McpToolException e) {
      assertEquals("That is your own thread.", e.getMessage());
    }
    try {
      this.waitFor("all", 1);
      fail("waited for nothing");
    } catch (McpToolException e) {
      assertEquals("Missing threadIds", e.getMessage());
    }
  }

  public void testAHandedPromptWithWakeWakesTheCallerOnceItIsDone() throws Exception {
    ThreadStore.Record child = this.idleThread("Child");

    this.call(ThreadTools.SEND, this.prompt(child, "fail on purpose", true));
    this.run(child, "fail on purpose");

    assertEquals(ChatController.State.IDLE, this.controller(child).awaitSettled(TIMEOUT_MILLIS));
    assertEquals(List.of(this.parent.id), this.service.wakes().changed(child.id, ChatController.State.IDLE));
    assertEquals(List.of(), this.service.wakes().changed(child.id, ChatController.State.IDLE));
  }

  public void testAHandedPromptWithoutWakeWakesNobody() throws Exception {
    ThreadStore.Record child = this.idleThread("Child");

    this.call(ThreadTools.SEND, this.prompt(child, "fail on purpose", false));
    this.run(child, "fail on purpose");

    assertEquals(ChatController.State.IDLE, this.controller(child).awaitSettled(TIMEOUT_MILLIS));
    assertEquals(List.of(), this.service.wakes().changed(child.id, ChatController.State.IDLE));
  }

  public void testAWaitThatReturnedTheAnswerTakesTheWake() throws Exception {
    ThreadStore.Record child = this.idleThread("Child");
    this.call(ThreadTools.SEND, this.prompt(child, "fail on purpose", true));
    this.run(child, "fail on purpose");

    JsonObject waited = this.call(ThreadTools.WAIT, this.prompt(child, "", false));

    assertEquals("idle", waited.get("state").getAsString());
    assertTrue("the failed turn's error comes along", waited.has("error"));
    assertEquals(List.of(), this.service.wakes().changed(child.id, ChatController.State.IDLE));
  }
}
