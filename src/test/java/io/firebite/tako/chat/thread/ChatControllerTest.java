package io.firebite.tako.chat.thread;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentDefaults;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.agent.acp.AcpBackend;
import io.firebite.tako.agent.acp.FakeAcpAgent;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.agent.claude.FakeClaude;
import io.firebite.tako.agent.codex.CodexBackend;
import io.firebite.tako.agent.codex.FakeCodex;
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
    this.record.projectPath = Files.createTempDirectory("tako-work").toString();
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
      Files.writeString(script, "@\"" + java + "\" -cp \"" + classes + "\" " + FakeClaude.class.getName()
        + " %*\r\n", StandardCharsets.UTF_8);
      return script;
    }
    Path script = directory.resolve("claude");
    Files.writeString(script, "#!/bin/sh\nexec '" + java + "' -cp '" + classes + "' " + FakeClaude.class.getName()
      + " \"$@\"\n", StandardCharsets.UTF_8);
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

  public void testClaudeRestartKeepsTheLastErrorAndResumesTheSessionOnTheNextPrompt() throws Exception {
    this.controller.open();
    this.awaitPost(ChatMessage.Init.class);
    this.controller.send("fail", List.of(), List.of(), 1);
    while (!(this.awaitPost(ChatMessage.Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    String session = this.record.sessionId;
    String error = this.controller.error();
    assertNotNull(session);
    assertNotNull(error);

    this.restart(this.controller);
    assertEquals("no new turn ran", error, this.controller.error());

    this.controller.send("write a.txt", List.of(), List.of(), 2);
    List<ChatMessage.ThreadMessage> resumed = this.postsUntil(ChatMessage.Permission.class);
    assertNotNull("the resumed session starts before the prompt", find(resumed, ChatMessage.Init.class));
    assertNull("the session was resumed", find(resumed, ChatMessage.Notice.class));
    assertEquals(session, this.record.sessionId);
    assertEquals(ChatController.State.WAITING, this.controller.state());
  }

  public void testCodexRestartResumesTheThreadWithItsModelAndEffort() throws Exception {
    Path codex = FakeExecutable.write("codex", FakeCodex.class);
    ChatController thread = new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new CodexBackend(codex.toString(), AgentDefaults.NONE, Map.of()));
    try {
      thread.open();
      assertTrue(this.awaitPost(ChatMessage.Init.class).info().capabilities().resume());
      assertEquals(ChatController.State.IDLE, thread.awaitSettled(TIMEOUT_MILLIS));
      String session = this.record.sessionId;
      assertNotNull(session);
      thread.setModel("gpt-x");
      thread.setEffort("high");

      this.restart(thread);

      thread.send("list files", List.of(), List.of(), 1);
      List<ChatMessage.ThreadMessage> resumed = this.postsUntil(ChatMessage.Permission.class);
      ChatMessage.Init init = find(resumed, ChatMessage.Init.class);
      assertNotNull("the resumed thread starts before the prompt", init);
      assertEquals("gpt-x", init.info().model());
      assertEquals("high", init.info().effort());
      assertNull("the chat keeps its timeline", find(resumed, ChatMessage.Replay.class));
      assertNull("the thread was resumed", find(resumed, ChatMessage.Notice.class));
      assertEquals(session, this.record.sessionId);
    } finally {
      thread.dispose();
    }
  }

  public void testAcpRestartLoadsTheSessionWithItsModelAndModeAndWaitsForAnIdleAgent() throws Exception {
    ChatController acp = this.acpController();
    try {
      acp.open();
      this.awaitPost(ChatMessage.Replay.class);
      assertTrue(this.awaitPost(ChatMessage.Init.class).info().capabilities().resume());
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));
      acp.setModel("smart");
      acp.setMode("code");

      this.restart(acp);

      acp.send("change a.txt", List.of(), List.of(), 1);
      List<ChatMessage.ThreadMessage> resumed = this.postsUntil(ChatMessage.Permission.class);
      ChatMessage.Init init = find(resumed, ChatMessage.Init.class);
      assertNotNull("the resumed session starts before the prompt", init);
      assertEquals("smart", init.info().model());
      assertEquals("code", init.info().mode());
      assertNull("the chat keeps its timeline", find(resumed, ChatMessage.Replay.class));
      assertNull("the session was loaded", find(resumed, ChatMessage.Notice.class));
      assertEquals("s0", this.record.sessionId);
      assertEquals(ChatController.State.WAITING, acp.state());

      acp.restartSession();
      ChatMessage.Permission permission = (ChatMessage.Permission) resumed.getLast();
      acp.answer(permission.requestId(), false, null, false, null, null);
      while (!(this.awaitPost(ChatMessage.Event.class).event() instanceof AgentEvent.TurnEnded)) {
      }
      assertEquals("a restart while the agent waits does nothing", ChatController.State.IDLE,
        acp.awaitSettled(TIMEOUT_MILLIS));
    } finally {
      acp.dispose();
    }
  }

  public void testRestartTellsWhenTheSessionCouldNotBeResumed() throws Exception {
    ChatController acp = this.acpController();
    try {
      acp.open();
      this.awaitPost(ChatMessage.Init.class);
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));
      this.restart(acp);
      this.record.sessionId = "gone";

      acp.send("change a.txt", List.of(), List.of(), 1);
      List<ChatMessage.ThreadMessage> started = this.postsUntil(ChatMessage.Permission.class);
      assertNotNull(find(started, ChatMessage.Notice.class));
      assertNull(find(started, ChatMessage.Replay.class));
      assertEquals("s1", this.record.sessionId);
    } finally {
      acp.dispose();
    }
  }

  /** A thread of the fake ACP agent that resumes its session s0. */
  private ChatController acpController() throws Exception {
    Path agent = FakeExecutable.write("fake-acp", FakeAcpAgent.class);
    this.record.sessionId = "s0";
    return new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new AcpBackend("Fake", List.of(agent.toString()), Map.of()));
  }

  public void testAPassingWorktreeSetupRunsBeforeThePromptReachesTheAgent() throws Exception {
    ChatController thread = this.setupController("echo ok> setup.txt");
    try {
      thread.open();
      thread.send("write a.txt", List.of(), List.of(), 1);

      List<ChatMessage.ThreadMessage> posts = this.postsUntil(ChatMessage.Permission.class);

      assertNotNull("the setup showed before the agent got the prompt", find(posts, ChatMessage.Setup.class));
      assertTrue(Files.exists(Path.of(this.record.workPath, "setup.txt")));
      assertNull(this.record.pendingSetup);
      assertEquals(ChatController.State.WAITING, thread.state());
    } finally {
      thread.dispose();
    }
  }

  public void testAFailedWorktreeSetupHoldsThePromptUntilTheAgentStartsWithoutIt() throws Exception {
    ChatController thread = this.setupController("exit 1");
    try {
      thread.open();
      thread.send("write a.txt", List.of(), List.of(), 1);
      this.awaitSetup(ChatMessage.SetupPhase.FAILED);
      this.awaitState(thread, ChatController.State.WAITING);
      assertTrue(thread.error(), thread.error().startsWith(TakoBundle.message("setup.failed", "")));
      assertNotNull("a failed setup keeps its record", this.record.pendingSetup);

      thread.interrupt();
      thread.retrySetup();
      this.awaitSetup(ChatMessage.SetupPhase.FAILED);
      this.awaitState(thread, ChatController.State.WAITING);

      thread.skipSetup();

      assertNotNull(this.awaitPost(ChatMessage.Permission.class));
      assertNull(this.record.pendingSetup);
    } finally {
      thread.dispose();
    }
  }

  public void testStopCancelsTheSetupAndClosingClearsWhatTheChatShows() throws Exception {
    ChatController thread = this.setupController(SystemInfo.isWindows ? "ping -n 61 127.0.0.1 > nul" : "sleep 60");
    thread.open();
    thread.send("write a.txt", List.of(), List.of(), 1);
    this.awaitState(thread, ChatController.State.WORKING);
    while (this.awaitPost(ChatMessage.Setup.class).step() != 1) {
    }

    thread.interrupt();

    ChatMessage.Setup failed = this.awaitSetup(ChatMessage.SetupPhase.FAILED);
    assertEquals(TakoBundle.message("setup.error.cancelled"), failed.error());
    this.awaitState(thread, ChatController.State.WAITING);

    thread.dispose();

    List<ChatMessage.ThreadMessage> posts = this.postsUntil(ChatMessage.Exit.class);
    ChatMessage.Setup cleared = find(posts, ChatMessage.Setup.class);
    assertNotNull("the card goes", cleared);
    assertEquals(ChatMessage.SetupPhase.DONE, cleared.phase());
    assertEquals(0, cleared.steps());
    assertNotNull("archiving keeps the setup for the next open", this.record.pendingSetup);
  }

  /** A thread in a new worktree whose main checkout's {@code .worktreesetup} holds {@code command}. */
  private ChatController setupController(String command) throws Exception {
    Path repository = Files.createTempDirectory("tako-main");
    Files.writeString(repository.resolve(".worktreesetup"), command + "\n", StandardCharsets.UTF_8);
    Path worktree = Path.of(this.record.projectPath);
    this.record.workPath = worktree.toString();
    this.record.pendingSetup = ThreadStore.PendingSetup.of(repository, worktree);
    return new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new ClaudeBackend(Map.of()));
  }

  private ChatMessage.Setup awaitSetup(ChatMessage.SetupPhase phase) throws InterruptedException {
    while (true) {
      ChatMessage.Setup setup = this.awaitPost(ChatMessage.Setup.class);
      if (setup.phase() == phase) {
        return setup;
      }
    }
  }

  private void awaitState(ChatController thread, ChatController.State state) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (thread.state() != state && System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      Thread.sleep(20);
    }
    assertEquals(state, thread.state());
  }

  /** Restarts the idle agent and waits until the chat learns it stopped. */
  private void restart(ChatController thread) throws InterruptedException {
    thread.restartSession();
    this.awaitPost(ChatMessage.Exit.class);
    assertEquals(ChatController.State.STOPPED, thread.state());
  }

  private static <T> T find(List<ChatMessage.ThreadMessage> messages, Class<T> type) {
    for (ChatMessage.ThreadMessage message : messages) {
      if (type.isInstance(message)) {
        return type.cast(message);
      }
    }
    return null;
  }
}
