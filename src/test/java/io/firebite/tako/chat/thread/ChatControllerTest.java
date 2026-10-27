package io.firebite.tako.chat.thread;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.agent.acp.AcpBackend;
import io.firebite.tako.agent.acp.FakeAcpAgent;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.agent.claude.FakeClaude;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.settings.AuthMode;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ChatControllerTest extends BasePlatformTestCase {
  private static final long TIMEOUT_MILLIS = 20000;

  private final BlockingQueue<ChatMessage.ThreadMessage> posted = new LinkedBlockingQueue<>();
  private final ThreadStore.Record record = new ThreadStore.Record();
  private ChatSettings.Data savedSettings;
  private ChatHost host;
  private ChatController controller;
  private String title;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    ChatSettings.Data settings = ChatSettings.state();
    this.savedSettings = new ChatSettings.Data();
    this.savedSettings.claudePath = settings.claudePath;
    this.savedSettings.authMode = settings.authMode;
    settings.claudePath = writeFakeExecutable().toString();
    settings.authMode = AuthMode.CLI_LOGIN;
    this.host = new ChatHost() {
      @Override
      public void setTitle(String title) {
        ChatControllerTest.this.title = title;
      }

      @Override
      public void requestAttention(String reason) {
      }

      @Override
      public void recordChanged() {
      }

      @Override
      public void activity() {
      }

      @Override
      public void stateChanged() {
      }

      @Override
      public List<String> additionalDirectories() {
        return List.of();
      }

      @Override
      public String toolsUrl() {
        return null;
      }
    };
    this.controller = new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new ClaudeBackend(Map.of()));
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      this.controller.dispose();
      ChatSettings.Data settings = ChatSettings.state();
      settings.claudePath = this.savedSettings.claudePath;
      settings.authMode = this.savedSettings.authMode;
    } finally {
      super.tearDown();
    }
  }

  private static Path writeFakeExecutable() throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", SystemInfo.isWindows ? "java.exe" : "java");
    Path classes = FakeExecutable.classes(FakeClaude.class);
    Path directory = Files.createTempDirectory("fake-claude");
    if (SystemInfo.isWindows) {
      Path script = directory.resolve("claude.cmd");
      Files.writeString(script, "@\"" + java + "\" -cp \"" + classes + "\" " + FakeClaude.class.getName() + "\r\n",
        StandardCharsets.UTF_8);
      return script;
    }
    Path script = directory.resolve("claude");
    Files.writeString(script, "#!/bin/sh\nexec '" + java + "' -cp '" + classes + "' " + FakeClaude.class.getName()
      + "\n", StandardCharsets.UTF_8);
    script.toFile().setExecutable(true);
    return script;
  }

  private <T extends ChatMessage.ThreadMessage> T awaitPost(Class<T> type) throws InterruptedException {
    return type.cast(this.postsUntil(type).getLast());
  }

  /** What the chat was sent up to and including the first message of {@code type}. */
  private List<ChatMessage.ThreadMessage> postsUntil(Class<?> type) throws InterruptedException {
    List<ChatMessage.ThreadMessage> messages = new ArrayList<>();
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      ChatMessage.ThreadMessage message = this.posted.poll(50, TimeUnit.MILLISECONDS);
      if (message == null) {
        continue;
      }
      messages.add(message);
      if (type.isInstance(message)) {
        return messages;
      }
      if (message instanceof ChatMessage.Error error) {
        throw new AssertionError("Chat error: " + error.text());
      }
    }
    throw new AssertionError("Nothing of type " + type.getSimpleName() + " was posted");
  }

  public void testPromptPermissionAndResultFlowThroughTheView() throws Exception {
    this.controller.open();
    ChatMessage.Init init = this.awaitPost(ChatMessage.Init.class);
    assertEquals("Using the Claude CLI login", init.info().authDescription());

    this.controller.setEffort("high");

    this.controller.send("write a.txt", List.of(), List.of(), 1);
    ChatMessage.Permission permission = this.awaitPost(ChatMessage.Permission.class);
    assertEquals("Write", permission.prompt().toolName());
    assertEquals(ToolKind.EDIT, permission.prompt().category());
    assertEquals("write a.txt", this.title);

    this.controller.answer(permission.requestId(), false, "no", false, null, null);
    while (true) {
      ChatMessage.Event message = this.awaitPost(ChatMessage.Event.class);
      if (message.event() instanceof AgentEvent.Text text) {
        assertEquals("denied", text.text());
      }
      if (message.event() instanceof AgentEvent.TurnEnded ended) {
        assertEquals(null, ended.error());
        break;
      }
    }
  }

  public void testFirstPromptIsSubmittedOnceTheAgentIsReadyAndStateFollowsTheTurn() throws Exception {
    this.record.projectPath = Files.createTempDirectory("tako-work").toString();
    this.controller.open("write a.txt");
    this.awaitPost(ChatMessage.Init.class);
    ChatMessage.Submit submit = this.awaitPost(ChatMessage.Submit.class);
    assertEquals("write a.txt", submit.text());
    assertEquals("a waiter never sees it idle before the prompt", ChatController.State.WORKING,
      this.controller.state());

    this.controller.send(submit.text(), List.of(), List.of(), 1);
    ChatMessage.Permission permission = this.awaitPost(ChatMessage.Permission.class);
    assertEquals(ChatController.State.WAITING, this.controller.state());
    assertNull("a given title stays", this.title);

    this.controller.answer(permission.requestId(), false, "no", false, null, null);
    while (!(this.awaitPost(ChatMessage.Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, this.controller.state());
    assertEquals("denied", this.controller.lastAnswer());
  }

  public void testRestartedSessionResumesOnTheNextPromptWithoutReplayingItsPast() throws Exception {
    Path agent = FakeExecutable.write("fake-acp", FakeAcpAgent.class);
    this.record.projectPath = Files.createTempDirectory("tako-work").toString();
    this.record.sessionId = "s0";
    ChatController acp = new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new AcpBackend("Fake", List.of(agent.toString()), Map.of()));
    try {
      acp.open();
      this.awaitPost(ChatMessage.Replay.class);
      assertTrue(this.awaitPost(ChatMessage.Init.class).info().capabilities().resume());
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));

      acp.restartSession();
      assertEquals(ChatController.State.STOPPED, acp.state());

      acp.send("change a.txt", List.of(), List.of(), 1);
      List<ChatMessage.ThreadMessage> resumed = this.postsUntil(ChatMessage.Permission.class);
      assertTrue("the resumed session starts before the prompt",
        resumed.stream().anyMatch(ChatMessage.Init.class::isInstance));
      assertFalse("the chat keeps its timeline", resumed.stream().anyMatch(ChatMessage.Replay.class::isInstance));
      assertEquals("s0", this.record.sessionId);
      assertEquals(ChatController.State.WAITING, acp.state());

      acp.restartSession();
      assertEquals("no restart while the agent waits", ChatController.State.WAITING, acp.state());
    } finally {
      acp.dispose();
    }
  }
}
