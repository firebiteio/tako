package io.firebite.tako.chat.thread;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentDefaults;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.agent.acp.AcpBackend;
import io.firebite.tako.agent.acp.FakeAcpAgent;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.agent.claude.FakeClaude;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.agent.codex.CodexBackend;
import io.firebite.tako.agent.codex.FakeCodex;
import io.firebite.tako.chat.store.PromptStash;
import io.firebite.tako.chat.store.ThreadPast;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.project.GitDiffs;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.settings.AuthMode;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.bridge.message.ChatError;
import io.firebite.tako.ui.bridge.message.ThreadMessage;
import io.firebite.tako.ui.bridge.message.thread.Event;
import io.firebite.tako.ui.bridge.message.thread.Exit;
import io.firebite.tako.ui.bridge.message.thread.Init;
import io.firebite.tako.ui.bridge.message.thread.Notice;
import io.firebite.tako.ui.bridge.message.thread.Permission;
import io.firebite.tako.ui.bridge.message.thread.Queue;
import io.firebite.tako.ui.bridge.message.thread.QueueItem;
import io.firebite.tako.ui.bridge.message.thread.QueueStashed;
import io.firebite.tako.ui.bridge.message.thread.Replay;
import io.firebite.tako.ui.bridge.message.thread.Rewound;
import io.firebite.tako.ui.bridge.message.thread.Setup;
import io.firebite.tako.ui.bridge.message.thread.SetupPhase;
import io.firebite.tako.ui.bridge.message.thread.Submit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

public class ChatControllerTest extends BasePlatformTestCase {
  private static final long TIMEOUT_MILLIS = 20000;

  private final BlockingQueue<ThreadMessage> posted = new LinkedBlockingQueue<>();
  private final ThreadStore.Record record = new ThreadStore.Record();
  private ChatSettings.Data savedSettings;
  private ChatHost host;
  private ChatController controller;
  private String title;
  /** What each look the controller asked for was about: true for a question, false for a turn's end. */
  private final List<Boolean> looks = new ArrayList<>();
  /** What the controller told its host about prompts and limits, in order. */
  private final List<String> hostCalls = new ArrayList<>();
  private Path pastFile;

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
    this.pastFile = Files.createTempDirectory("tako-past").resolve("thread.json");
    this.host = new ChatHost() {
      @Override
      public void setTitle(String title) {
        ChatControllerTest.this.title = title;
      }

      @Override
      public void requestAttention(String reason) {
      }

      @Override
      public void needsLook(boolean asks) {
        ChatControllerTest.this.looks.add(asks);
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
      public void prompted(String typed) {
        ChatControllerTest.this.hostCalls.add("prompted " + typed);
      }

      @Override
      public void limitReached(@Nullable String label, @Nullable Long resetsAt, boolean namesReset) {
        ChatControllerTest.this.hostCalls.add("limit " + label + " " + resetsAt);
      }

      @Override
      public List<String> additionalDirectories() {
        return List.of();
      }

      @Override
      public String toolsUrl() {
        return null;
      }

      @Override
      public void started(AgentInfo info) {
      }

      @Override
      public String busyThreadIn(String topLevel) {
        return null;
      }

      @Override
      public Path pastFile() {
        return ChatControllerTest.this.pastFile;
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

  /** The title the thread got, which the controller sets on the EDT after the prompt went out. */
  private String awaitTitle() throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (this.title == null && System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      Thread.sleep(10);
    }
    return this.title;
  }

  private <T extends ThreadMessage> T awaitPost(Class<T> type) throws InterruptedException {
    return type.cast(this.postsUntil(type).getLast());
  }

  /** What the chat was sent up to and including the first message of {@code type}. */
  private List<ThreadMessage> postsUntil(Class<?> type) throws InterruptedException {
    List<ThreadMessage> messages = new ArrayList<>();
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      ThreadMessage message = this.posted.poll(50, TimeUnit.MILLISECONDS);
      if (message == null) {
        continue;
      }
      messages.add(message);
      if (type.isInstance(message)) {
        return messages;
      }
      if (message instanceof ChatError error) {
        throw new AssertionError("Chat error: " + error.text());
      }
    }
    throw new AssertionError("Nothing of type " + type.getSimpleName() + " was posted");
  }

  public void testGitDiffsOutsideGitTellWhy() throws Exception {
    assertThrows(Worktrees.GitException.class, () -> this.controller.gitDiffSources());

    this.controller.showGitDiff(new GitDiffs.Uncommitted());

    assertTrue(this.awaitPost(ChatError.class).text().startsWith("Could not read the changes: "));
  }

  public void testGitDiffsWithNothingToShowSaySoWithoutAnAgent() throws Exception {
    Path folder = Path.of(this.record.projectPath);
    git(folder, "init", "-q", "-b", "main");
    git(folder, "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-q", "--allow-empty", "-m",
      "empty");

    GitDiffs.Sources sources = this.controller.gitDiffSources();
    assertEquals(0, sources.uncommitted());
    assertEquals(1, sources.commits().size());

    this.controller.showGitDiff(new GitDiffs.Uncommitted());
    assertEquals("No uncommitted changes.", this.awaitPost(ChatError.class).text());
    this.controller.showGitDiff(sources.commits().get(0));
    assertEquals("The commit changed no files.", this.awaitPost(ChatError.class).text());
    git(folder, "branch", "-m", "feature");
    git(folder, "branch", "main");
    git(folder, "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-q", "--allow-empty", "-m",
      "also empty");
    GitDiffs.Branch branch = this.controller.gitDiffSources().branch();
    this.controller.showGitDiff(branch);
    assertEquals("The branch changed no files.", this.awaitPost(ChatError.class).text());
    this.controller.showGitDiff(new GitDiffs.Commit("0".repeat(40), "0000000", "gone", 0));
    assertTrue(this.awaitPost(ChatError.class).text().startsWith("Could not read the changes: "));
  }

  public void testPromptPermissionAndResultFlowThroughTheView() throws Exception {
    this.controller.open();
    Init init = this.awaitPost(Init.class);
    assertEquals("Using the Claude CLI login", init.info().authDescription());

    this.controller.setEffort("high");

    this.controller.send("write a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    Permission permission = this.awaitPost(Permission.class);
    assertEquals("Write", permission.prompt().toolName());
    assertEquals(ToolKind.EDIT, permission.prompt().category());
    assertEquals("write a.txt", this.awaitTitle());

    this.controller.answer(permission.requestId(), false, "no", false, null, null);
    while (true) {
      Event message = this.awaitPost(Event.class);
      if (message.event() instanceof AgentEvent.Text text) {
        assertEquals("denied", text.text());
      }
      if (message.event() instanceof AgentEvent.TurnEnded ended) {
        assertEquals(null, ended.error());
        break;
      }
    }
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    assertEquals("the question asks for a look, then the turn's end", List.of(true, false), this.looks);
  }

  public void testThreadInItsOwnFolderKeepsItsPullRequestWhenTheBranchIsGone() throws Exception {
    GitInfo.PullRequest merged = new GitInfo.PullRequest("#7", "Fix", "https://github.com/o/r/pull/7", "merged");
    this.record.workPath = Files.createTempDirectory("tako-worktree").toString();
    this.record.branch = "fix";
    this.record.pullRequest = merged;

    this.controller.refreshGit();

    assertNull(this.record.branch);
    assertEquals(merged, this.record.pullRequest);
  }

  public void testThreadInItsOwnFolderDropsThePullRequestOnAnotherBranch() throws Exception {
    Path worktree = Files.createTempDirectory("tako-worktree");
    Process init = new ProcessBuilder("git", "init", "-q", "-b", "feat-b").directory(worktree.toFile()).start();
    assertEquals(0, init.waitFor());
    this.record.workPath = worktree.toString();
    this.record.branch = "feat-a";
    this.record.pullRequest = new GitInfo.PullRequest("#5", "A", "https://github.com/o/r/pull/5", "merged");

    this.controller.refreshGit();

    assertEquals("feat-b", this.record.branch);
    assertNull(this.record.pullRequest);
  }

  public void testThreadInTheSharedCheckoutDropsThePullRequestOfAGoneBranch() {
    this.record.branch = "fix";
    this.record.pullRequest = new GitInfo.PullRequest("#7", "Fix", "https://github.com/o/r/pull/7", "open");

    this.controller.refreshGit();

    assertNull(this.record.pullRequest);
  }

  public void testFirstPromptIsSubmittedOnceTheAgentIsReadyAndStateFollowsTheTurn() throws Exception {
    this.record.projectPath = Files.createTempDirectory("tako-work").toString();
    this.controller.open("write a.txt");
    this.awaitPost(Init.class);
    Submit submit = this.awaitPost(Submit.class);
    assertEquals("write a.txt", submit.text());
    assertEquals("a waiter never sees it idle before the prompt", ChatController.State.WORKING,
      this.controller.state());

    this.controller.send(submit.text(), List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    Permission permission = this.awaitPost(Permission.class);
    assertEquals(ChatController.State.WAITING, this.controller.state());
    assertNull("a given title stays", this.title);

    this.controller.answer(permission.requestId(), false, "no", false, null, null);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, this.controller.state());
    assertEquals("denied", this.controller.lastAnswer());
  }

  public void testClaudeRestartKeepsTheLastErrorAndResumesTheSessionOnTheNextPrompt() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    this.controller.send("fail", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    String session = this.record.sessionId;
    String error = this.controller.error();
    assertNotNull(session);
    assertNotNull(error);

    this.restart(this.controller);
    assertEquals("no new turn ran", error, this.controller.error());

    this.controller.send("write a.txt", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);
    List<ThreadMessage> resumed = this.postsUntil(Permission.class);
    assertNotNull("the resumed session starts before the prompt", find(resumed, Init.class));
    assertNull("the session was resumed", find(resumed, Notice.class));
    assertEquals(session, this.record.sessionId);
    assertEquals(ChatController.State.WAITING, this.controller.state());
  }

  public void testCodexRestartResumesTheThreadWithItsModelAndEffort() throws Exception {
    Path codex = FakeExecutable.write("codex", FakeCodex.class);
    ChatController thread = new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new CodexBackend(codex.toString(), AgentDefaults.NONE, Map.of()));
    try {
      thread.open();
      assertTrue(this.awaitPost(Init.class).info().capabilities().resume());
      assertEquals(ChatController.State.IDLE, thread.awaitSettled(TIMEOUT_MILLIS));
      String session = this.record.sessionId;
      assertNotNull(session);
      thread.setModel("gpt-x");
      thread.setEffort("high");

      this.restart(thread);

      thread.send("list files", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
      List<ThreadMessage> resumed = this.postsUntil(Permission.class);
      Init init = find(resumed, Init.class);
      assertNotNull("the resumed thread starts before the prompt", init);
      assertEquals("gpt-x", init.info().model());
      assertEquals("high", init.info().effort());
      assertNull("the chat keeps its timeline", find(resumed, Replay.class));
      assertNull("the thread was resumed", find(resumed, Notice.class));
      assertEquals(session, this.record.sessionId);
    } finally {
      thread.dispose();
    }
  }

  public void testATurnStoppedByALimitTellsTheHostAfterThePromptAndAFinishedTurnDoesNot() throws Exception {
    Path codex = FakeExecutable.write("codex", FakeCodex.class);
    ChatController thread = new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new CodexBackend(codex.toString(), AgentDefaults.NONE, Map.of()));
    try {
      thread.open();
      this.awaitPost(Init.class);
      assertEquals(ChatController.State.IDLE, thread.awaitSettled(TIMEOUT_MILLIS));

      thread.send(FakeCodex.LIMIT_PROMPT, List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
      while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
      }
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      assertEquals(List.of("prompted " + FakeCodex.LIMIT_PROMPT, "limit 5h " + FakeCodex.LIMIT_RESETS_AT * 1000),
        this.hostCalls);

      this.hostCalls.clear();
      thread.send("list files", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);
      Permission permission = this.awaitPost(Permission.class);
      thread.answer(permission.requestId(), true, null, false, null, null);
      while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
      }
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      assertEquals(List.of("prompted list files"), this.hostCalls);
    } finally {
      thread.dispose();
    }
  }

  public void testAcpRestartLoadsTheSessionWithItsModelAndModeAndWaitsForAnIdleAgent() throws Exception {
    ChatController acp = this.acpController();
    try {
      acp.open();
      this.awaitPost(Replay.class);
      assertTrue(this.awaitPost(Init.class).info().capabilities().resume());
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));
      acp.setModel("smart");
      acp.setMode("code");

      this.restart(acp);

      acp.send("change a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
      List<ThreadMessage> resumed = this.postsUntil(Permission.class);
      Init init = find(resumed, Init.class);
      assertNotNull("the resumed session starts before the prompt", init);
      assertEquals("smart", init.info().model());
      assertEquals("code", init.info().mode());
      assertNull("the chat keeps its timeline", find(resumed, Replay.class));
      assertNull("the session was loaded", find(resumed, Notice.class));
      assertEquals("s0", this.record.sessionId);
      assertEquals(ChatController.State.WAITING, acp.state());

      acp.restartSession();
      Permission permission = (Permission) resumed.getLast();
      acp.answer(permission.requestId(), false, null, false, null, null);
      while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
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
      this.awaitPost(Init.class);
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));
      this.restart(acp);
      this.record.sessionId = "gone";

      acp.send("change a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
      List<ThreadMessage> started = this.postsUntil(Permission.class);
      assertNotNull(find(started, Notice.class));
      assertNull(find(started, Replay.class));
      assertEquals("s1", this.record.sessionId);
    } finally {
      acp.dispose();
    }
  }

  public void testRewindToTheFirstPromptStartsANewSession() throws Exception {
    this.finishFailingTurn();
    assertNotNull(this.record.sessionId);

    this.controller.rewind(List.of("fail"), false);
    List<ThreadMessage> messages = this.postsUntil(Rewound.class);

    Replay replay = find(messages, Replay.class);
    assertNotNull("the timeline is rebuilt", replay);
    assertTrue(replay.events().isEmpty());
    assertEquals(new Rewound(true, null), messages.getLast());
    assertNull(this.record.sessionId);
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
  }

  public void testRewindKeepsTheEarlierPromptsInACopyOfTheSession() throws Exception {
    Path config = Files.createTempDirectory("tako-claude-config");
    String session = "11111111-2222-3333-4444-555555555555";
    Path sessions = config.resolve("projects").resolve(SessionStore.encode(this.record.projectPath));
    Files.createDirectories(sessions);
    Files.write(sessions.resolve(session + ".jsonl"), List.of(transcriptUser(session, "First question"),
      transcriptUser(session, "Second question")), StandardCharsets.UTF_8);
    this.record.sessionId = session;
    ChatController thread = new ChatController(this.getProject(), this.posted::add, this.host, this.record,
      new ClaudeBackend(Map.of("CLAUDE_CONFIG_DIR", config.toString())));
    try {
      thread.open();
      this.awaitPost(Init.class);
      assertEquals(ChatController.State.IDLE, thread.awaitSettled(TIMEOUT_MILLIS));

      thread.rewind(List.of("First question", "Second  question"), false);
      List<ThreadMessage> messages = this.postsUntil(Rewound.class);

      assertEquals(new Rewound(true, null), messages.getLast());
      List<String> prompts = new ArrayList<>();
      for (AgentEvent event : find(messages, Replay.class).events()) {
        if (event instanceof AgentEvent.Prompt prompt) {
          prompts.add(prompt.text());
        }
      }
      assertEquals(List.of("First question"), prompts);
      assertNotNull(this.record.sessionId);
      assertFalse(session.equals(this.record.sessionId));
      assertTrue("the original stays", Files.exists(sessions.resolve(session + ".jsonl")));

      thread.rewind(List.of("First question", "Other question"), false);
      Rewound mismatch = this.awaitPost(Rewound.class);
      assertFalse(mismatch.rewound());
      assertNotNull(mismatch.error());
    } finally {
      thread.dispose();
    }
  }

  public void testRewindWhileTheAgentWaitsIsRefused() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    this.controller.send("write a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    Permission permission = this.awaitPost(Permission.class);
    String session = this.record.sessionId;

    this.controller.rewind(List.of("write a.txt"), false);

    assertEquals(new Rewound(false, TakoBundle.message("error.rewind.busy")),
      this.awaitPost(Rewound.class));
    assertEquals(session, this.record.sessionId);
    assertEquals(ChatController.State.WAITING, this.controller.state());
    this.controller.answer(permission.requestId(), false, "no", false, null, null);
  }

  public void testRewindIsRefusedWhileOneRuns() throws Exception {
    this.finishFailingTurn();

    this.controller.rewind(List.of("fail"), false);
    this.controller.rewind(List.of("fail"), false);

    assertEquals(new Rewound(false, TakoBundle.message("error.rewind.busy")),
      this.awaitPost(Rewound.class));
    assertEquals(new Rewound(true, null), this.awaitPost(Rewound.class));
  }

  public void testFilesWithoutASnapshotChangeNothing() throws Exception {
    this.finishFailingTurn();
    String session = this.record.sessionId;

    this.controller.rewind(List.of("fail"), true);

    Rewound rewound = this.awaitPost(Rewound.class);
    assertFalse(rewound.rewound());
    assertEquals(TakoBundle.message("error.rewind.no.checkpoint"), rewound.error());
    assertEquals(session, this.record.sessionId);
    assertEquals(ChatController.State.IDLE, this.controller.state());
  }

  public void testAnIdlePromptGetsASnapshotThatTheRewindPutsBack() throws Exception {
    Path repository = Files.createTempDirectory("tako-rewind-repo").toRealPath();
    git(repository, "init", "-q");
    Files.writeString(repository.resolve("a.txt"), "before", StandardCharsets.UTF_8);
    this.record.id = "3f2a6c1e-0000-4000-8000-000000000002";
    this.record.projectPath = repository.toString();
    this.finishFailingTurn();
    assertTrue(this.controller.hasCheckpoint(0, "fail"));
    assertFalse(this.controller.hasCheckpoint(0, "other"));
    Files.writeString(repository.resolve("a.txt"), "after", StandardCharsets.UTF_8);
    Files.writeString(repository.resolve("new.txt"), "new", StandardCharsets.UTF_8);

    this.controller.rewind(List.of("fail"), true);

    assertEquals(new Rewound(true, null), this.awaitPost(Rewound.class));
    assertEquals("before", Files.readString(repository.resolve("a.txt"), StandardCharsets.UTF_8));
    assertFalse(Files.exists(repository.resolve("new.txt")));
  }

  public void testSubmitDuringARewindWaitsForIt() throws Exception {
    this.finishFailingTurn();

    this.controller.rewind(List.of("fail"), false);
    this.controller.submit("next");

    List<ThreadMessage> messages = this.postsUntil(Submit.class);
    assertNotNull("the prompt goes in after the rewind", find(messages, Rewound.class));
    assertEquals("next", ((Submit) messages.getLast()).text());
    assertEquals(ChatController.State.WORKING, this.controller.state());
  }

  /** Opens the thread and runs a turn that fails right away, so the agent is idle with a session. */
  private void finishFailingTurn() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    this.controller.send("fail", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
  }

  private static String transcriptUser(String session, String text) {
    return "{\"type\":\"user\",\"uuid\":\"u-" + text.hashCode() + "\",\"sessionId\":\"" + session
      + "\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}";
  }

  private static void git(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
    assertEquals(0, process.waitFor());
  }

  /** What the chat was sent up to and including the next event of {@code type}. */
  private List<ThreadMessage> postsUntilEvent(Class<? extends AgentEvent> type)
    throws InterruptedException {
    List<ThreadMessage> messages = new ArrayList<>();
    while (true) {
      List<ThreadMessage> batch = this.postsUntil(Event.class);
      messages.addAll(batch);
      if (type.isInstance(((Event) batch.getLast()).event())) {
        return messages;
      }
    }
  }

  private static boolean hasEvent(List<ThreadMessage> messages, AgentEvent event) {
    return indexOfEvent(messages, event) >= 0;
  }

  private static int indexOfEvent(List<ThreadMessage> messages, AgentEvent event) {
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof Event posted && posted.event().equals(event)) {
        return i;
      }
    }
    return -1;
  }

  private static Queue lastQueue(List<ThreadMessage> messages) {
    Queue last = null;
    for (ThreadMessage message : messages) {
      if (message instanceof Queue queue) {
        last = queue;
      }
    }
    return last;
  }

  /** Opens the thread and starts a turn that waits for its permission prompt. */
  private Permission startWaitingTurn() throws InterruptedException {
    this.controller.open();
    this.awaitPost(Init.class);
    assertEquals(QueuedPrompts.Action.SEND, this.controller.send("write a.txt", List.of(), List.of(), 1, 0,
      QueuedPrompts.Delivery.QUEUE));
    return this.awaitPost(Permission.class);
  }

  public void testPromptDuringATurnWaitsAndGoesAfterIt() throws Exception {
    Permission permission = this.startWaitingTurn();
    assertEquals(QueuedPrompts.Action.HOLD, this.controller.send("write b.txt", List.of(), List.of(), 2, 1,
      QueuedPrompts.Delivery.QUEUE));
    assertEquals(ChatController.State.WAITING, this.controller.state());

    this.controller.answer(permission.requestId(), true, null, false, null, null);
    List<ThreadMessage> posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertFalse("the queued prompt waits for the turn's end", hasEvent(posts, new AgentEvent.PromptTaken(2, true)));
    assertNotSame("never idle between the turns", ChatController.State.IDLE, this.controller.state());
    posts = this.postsUntil(Permission.class);
    assertTrue(hasEvent(posts, new AgentEvent.PromptTaken(2, true)));
    this.controller.answer(((Permission) posts.getLast()).requestId(), true, null, false, null, null);
    posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertTrue(lastQueue(posts).items().isEmpty());
  }

  public void testUnqueuedPromptIsNeverSent() throws Exception {
    Permission permission = this.startWaitingTurn();
    this.controller.send("fail", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);
    assertEquals("fail", this.controller.unqueue(2).text());
    assertNull("twice gives nothing", this.controller.unqueue(2));

    this.controller.answer(permission.requestId(), true, null, false, null, null);
    this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertFalse(hasEvent(this.postsFor(1000), new AgentEvent.PromptTaken(2, true)));
  }

  /** What the chat is sent within the next {@code millis}. */
  private List<ThreadMessage> postsFor(long millis) throws InterruptedException {
    List<ThreadMessage> messages = new ArrayList<>();
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      ThreadMessage message = this.posted.poll(50, TimeUnit.MILLISECONDS);
      if (message != null) {
        messages.add(message);
      }
    }
    return messages;
  }

  public void testSendNowStopsTheTurnAndSendsThePromptNext() throws Exception {
    this.startWaitingTurn();
    this.controller.send("fail second", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);
    this.controller.send("fail third", List.of(), List.of(), 3, 2, QueuedPrompts.Delivery.QUEUE);

    assertEquals(QueuedPrompts.SendNow.INTERRUPT, this.controller.sendQueuedNow(3));
    assertEquals("twice does nothing", QueuedPrompts.SendNow.NONE, this.controller.sendQueuedNow(3));
    List<ThreadMessage> posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertTrue(hasEvent(posts, new AgentEvent.Interrupted()));
    posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertTrue("the hurried prompt goes first", hasEvent(posts, new AgentEvent.PromptTaken(3, true)));
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertFalse("its error holds the rest", hasEvent(this.postsFor(1000), new AgentEvent.PromptTaken(2, true)));
  }

  public void testStopHoldsTheQueueAndANewPromptResumesIt() throws Exception {
    this.startWaitingTurn();
    this.controller.send("fail queued", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);

    this.controller.interrupt();
    List<ThreadMessage> posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertTrue(lastQueue(posts).held());
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));

    assertEquals(QueuedPrompts.Action.SEND, this.controller.send("write b.txt", List.of(), List.of(), 3, 2,
      QueuedPrompts.Delivery.QUEUE));
    posts = this.postsUntil(Permission.class);
    assertTrue(hasEvent(posts, new AgentEvent.PromptTaken(3, true)));
    assertFalse(hasEvent(posts, new AgentEvent.PromptTaken(2, true)));
    this.controller.answer(((Permission) posts.getLast()).requestId(), true, null, false, null, null);
    this.postsUntilEvent(AgentEvent.TurnEnded.class);
    posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertTrue("the held prompt goes after the new one", hasEvent(posts, new AgentEvent.PromptTaken(2, true)));
  }

  public void testAgentExitKeepsQueuedPromptsHeldAndSendRestartsIt() throws Exception {
    this.startWaitingTurn();
    this.controller.send("fail later", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);
    assertEquals(QueuedPrompts.Action.STEER, this.controller.send("exit now", List.of(), List.of(), 3, 2,
      QueuedPrompts.Delivery.STEER));

    List<ThreadMessage> posts = this.postsUntil(Exit.class);
    assertEquals(ChatController.State.STOPPED, this.controller.state());
    Queue queue = lastQueue(posts);
    assertTrue(queue.held());
    assertEquals(List.of(new QueueItem(3, QueuedPrompts.Status.HELD),
      new QueueItem(2, QueuedPrompts.Status.HELD)), queue.items());

    assertEquals("exit now", this.controller.unqueue(3).text());
    assertEquals(QueuedPrompts.SendNow.SEND, this.controller.sendQueuedNow(2));
    posts = this.postsUntilEvent(AgentEvent.TurnEnded.class);
    assertNotNull("the agent started again", find(posts, Init.class));
    assertTrue(hasEvent(posts, new AgentEvent.PromptTaken(2, true)));
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
  }

  public void testClosingTheThreadStashesItsQueuedPrompts() throws Exception {
    PromptStash stash = PromptStash.getInstance(this.getProject());
    for (PromptStash.Entry entry : stash.entries()) {
      stash.remove(entry.id);
    }
    this.startWaitingTurn();
    this.controller.send("first ä 😀", List.of(), List.of(), 2, 1, QueuedPrompts.Delivery.QUEUE);
    this.controller.send("second", List.of(), List.of(), 3, 2, QueuedPrompts.Delivery.QUEUE);

    this.controller.dispose();
    assertEquals(2, this.awaitPost(QueueStashed.class).count());
    List<PromptStash.Entry> entries = stash.entries();
    assertEquals("second", entries.get(0).prompt.text());
    assertEquals("first ä 😀", entries.get(1).prompt.text());
    assertEquals("a closed queue sends nothing", QueuedPrompts.Action.SEND, this.controller.send("late", List.of(),
      List.of(), 4, 3, QueuedPrompts.Delivery.QUEUE));
    for (PromptStash.Entry entry : stash.entries()) {
      stash.remove(entry.id);
    }
  }

  public void testSubmitWhileWaitingKeepsWaitingAndQueues() throws Exception {
    this.startWaitingTurn();
    this.controller.submit("from another agent");
    assertEquals(ChatController.State.WAITING, this.controller.state());
    assertEquals("from another agent", this.awaitPost(Submit.class).text());
  }

  public void testSettledStaysPendingWhileStartingAndCompletesOnClose() {
    CompletableFuture<ChatController.State> settled = this.controller.settled();
    assertFalse(settled.isDone());

    this.controller.dispose();

    assertEquals(ChatController.State.STARTING, settled.getNow(null));
  }

  public void testSettledCompletesAgainAfterEachTurn() throws Exception {
    CompletableFuture<ChatController.State> first = this.controller.settled();
    Permission permission = this.startWaitingTurn();
    assertFalse(first.getNow(ChatController.State.WORKING).isBusy());
    assertEquals("waiting for the person is settled", ChatController.State.WAITING,
      this.controller.settled().getNow(null));

    this.controller.answer(permission.requestId(), true, null, false, null, null);
    CompletableFuture<ChatController.State> next = this.controller.settled();

    assertEquals(ChatController.State.IDLE, next.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
  }

  public void testSwitchingAgentsHandsTheConversationToTheNextPromptOnly() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    this.deniedTurn(this.controller, "write a.txt", 1);
    String claudeSession = this.record.sessionId;
    Path prompts = Files.createTempDirectory("acp-prompts").resolve("prompts.txt");
    AcpBackend acp = this.fakeAcp(prompts);

    assertTrue(this.controller.switchAgent(acp));
    assertFalse("a second switch waits for the first", this.controller.switchAgent(new CodexBackend("codex",
      AgentDefaults.NONE, Map.of())));
    List<ThreadMessage> switched = this.postsUntil(Init.class);
    Event divider = find(switched, Event.class);
    assertEquals(new AgentEvent.AgentSwitched("Claude Code", "Fake"), divider.event());
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertEquals(acp.id(), this.record.agent());
    assertEquals("Claude Code", this.record.handoffFrom);
    assertEquals(claudeSession, this.record.earlierSessions().getFirst().sessionId);
    assertEquals("s1", this.record.sessionId);
    assertEquals(1, this.record.agentSwitches);
    ThreadPast past = ThreadPast.read(this.pastFile);
    assertEquals(List.of(new ThreadPast.Turn("write a.txt", "denied")), past.segments().getFirst().turns());

    this.controller.send("go on", List.of(), List.of(), 2, 0, QueuedPrompts.Delivery.QUEUE);
    Permission first = this.awaitPost(Permission.class);
    this.controller.send("and more", List.of(), List.of(), 3, 1, QueuedPrompts.Delivery.QUEUE);
    this.controller.answer(first.requestId(), false, null, false, null, null);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    Permission second = this.awaitPost(Permission.class);
    assertNull("delivered once its turn ended without an error", this.record.handoffFrom);
    this.controller.answer(second.requestId(), false, null, false, null, null);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }

    List<String> sent = Files.readAllLines(prompts, StandardCharsets.UTF_8);
    assertEquals(2, sent.size());
    assertTrue(sent.get(0).startsWith("go on\\n\\n<ide_context>\\n<handoff>\\nThis thread ran with Claude Code"));
    assertTrue(sent.get(0).contains("Person: write a.txt\\nAgent: denied"));
    assertEquals("and more", sent.get(1));
    assertTrue(
      this.controller.conversation(10_000).startsWith("Person: write a.txt\n\nAgent: denied\n\nPerson: go on"));
  }

  public void testSwitchWaitsUntilTheAgentIsIdle() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    AcpBackend acp = this.fakeAcp(null);
    this.controller.send("write a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    assertFalse("a prompt on its way to the agent", this.controller.switchAgent(acp));
    Permission permission = this.awaitPost(Permission.class);

    assertFalse(this.controller.switchAgent(acp));

    this.controller.answer(permission.requestId(), false, null, false, null, null);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertNull(this.record.agent);
    assertFalse("the same agent is no switch", this.controller.switchAgent(new ClaudeBackend(Map.of())));
  }

  public void testSwitchToAnAgentThatIsNotInstalledChangesNothing() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    this.deniedTurn(this.controller, "write a.txt", 1);
    String session = this.record.sessionId;

    assertTrue(this.controller.switchAgent(new AcpBackend("Missing", List.of("tako-no-such-agent-xyz"), Map.of())));
    List<ThreadMessage> posts = this.postsUntil(Notice.class);

    assertNull(find(posts, Event.class));
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertNull(this.record.agent);
    assertEquals(session, this.record.sessionId);
    assertNull(this.record.handoffFrom);
    assertFalse(Files.exists(this.pastFile));
    this.deniedTurn(this.controller, "write a.txt", 2);
  }

  public void testAFailedFirstTurnKeepsTheHandoffForTheNextPrompt() throws Exception {
    ChatController acp = this.acpController();
    try {
      acp.open();
      this.awaitPost(Init.class);
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));
      assertTrue(acp.hasConversation());

      assertTrue(acp.switchAgent(new ClaudeBackend(Map.of())));
      this.awaitPost(Init.class);
      assertEquals(ChatController.State.IDLE, acp.awaitSettled(TIMEOUT_MILLIS));
      assertEquals("Fake", this.record.handoffFrom);
      assertEquals("s0", this.record.earlierSessions().getFirst().sessionId);

      acp.send("fail", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
      while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
      }
      assertEquals("Fake", this.record.handoffFrom);

      this.deniedTurn(acp, "write a.txt", 2);
      assertNull(this.record.handoffFrom);
    } finally {
      acp.dispose();
    }
  }

  public void testSwitchingBackBeforeAnyPromptKeepsWhoDidTheWork() throws Exception {
    this.controller.open();
    this.awaitPost(Init.class);
    this.deniedTurn(this.controller, "write a.txt", 1);

    assertTrue(this.controller.switchAgent(this.fakeAcp(null)));
    this.awaitPost(Init.class);
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));
    assertTrue(this.controller.switchAgent(new ClaudeBackend(Map.of())));
    this.awaitPost(Init.class);
    assertEquals(ChatController.State.IDLE, this.controller.awaitSettled(TIMEOUT_MILLIS));

    assertEquals("claude", this.record.agent());
    assertEquals("Claude Code", this.record.handoffFrom);
    assertEquals(2, this.record.agentSwitches);
    ThreadPast past = ThreadPast.read(this.pastFile);
    assertEquals(2, past.segments().size());
    assertTrue(past.segments().get(1).turns().isEmpty());
    assertEquals(2, this.record.earlierSessions().size());
    this.deniedTurn(this.controller, "write a.txt", 2);
    assertNull(this.record.handoffFrom);
  }

  public void testReopeningShowsTheEarlierAgentsBeforeTheSession() throws Exception {
    new ThreadPast(List.of(new ThreadPast.Segment("Codex", "Claude Code",
      List.of(new ThreadPast.Turn("earlier", "done")))), List.of(), null, null).write(this.pastFile);
    this.record.handoffFrom = "Codex";
    this.record.agentSwitches = 1;

    this.controller.open();
    List<ThreadMessage> opened = this.postsUntil(Init.class);

    Replay replay = find(opened, Replay.class);
    assertEquals(List.of(new AgentEvent.Prompt("earlier", false, List.of()), new AgentEvent.Text(null, "done", null),
      new AgentEvent.AgentSwitched("Codex", "Claude Code")), replay.events());
    assertTrue(this.controller.hasConversation());
    assertEquals("Person: earlier\n\nAgent: done", this.controller.conversation(1000));
  }

  public void testReopeningDropsASwitchTheRecordNeverGot() throws Exception {
    new ThreadPast(List.of(new ThreadPast.Segment("Fake", "Claude Code", List.of())), List.of(), null, null)
      .write(this.pastFile);
    ChatController acp = this.acpController();
    try {
      acp.open();
      List<ThreadMessage> opened = this.postsUntil(Init.class);

      Replay replay = find(opened, Replay.class);
      for (AgentEvent event : replay.events()) {
        assertFalse(event instanceof AgentEvent.AgentSwitched);
      }
      assertTrue(ThreadPast.read(this.pastFile).isEmpty());
    } finally {
      acp.dispose();
    }
  }

  /** Sends a prompt to the fake Claude Code or ACP agent and declines its edit, until the turn ended. */
  private void deniedTurn(ChatController thread, String prompt, int turn) throws InterruptedException {
    thread.send(prompt, List.of(), List.of(), turn, turn - 1, QueuedPrompts.Delivery.QUEUE);
    Permission permission = this.awaitPost(Permission.class);
    thread.answer(permission.requestId(), false, "no", false, null, null);
    while (!(this.awaitPost(Event.class).event() instanceof AgentEvent.TurnEnded)) {
    }
    assertEquals(ChatController.State.IDLE, thread.awaitSettled(TIMEOUT_MILLIS));
  }

  /** The fake ACP agent, writing the prompts it gets to {@code prompts} if given. */
  private AcpBackend fakeAcp(Path prompts) throws Exception {
    Path agent = FakeExecutable.write("fake-acp", FakeAcpAgent.class);
    List<String> command = prompts == null ? List.of(agent.toString()) : List.of(agent.toString(), prompts.toString());
    return new AcpBackend("Fake", command, Map.of());
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
      thread.send("write a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);

      List<ThreadMessage> posts = this.postsUntil(Permission.class);

      assertNotNull("the setup showed before the agent got the prompt", find(posts, Setup.class));
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
      thread.send("write a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
      List<ThreadMessage> posts = new ArrayList<>(this.postsUntil(Setup.class));
      while (!(posts.getLast() instanceof Setup setup && setup.phase() == SetupPhase.FAILED)) {
        posts.addAll(this.postsUntil(Setup.class));
      }
      this.awaitState(thread, ChatController.State.WAITING);
      Thread.sleep(500);
      ThreadMessage late;
      while ((late = this.posted.poll()) != null) {
        posts.add(late);
      }
      assertNull("the agent waits for the setup too: " + posts, find(posts, Init.class));
      assertTrue(thread.error(), thread.error().startsWith(TakoBundle.message("setup.failed", "")));
      assertNotNull("a failed setup keeps its record", this.record.pendingSetup);

      thread.interrupt();
      thread.retrySetup();
      this.awaitSetup(SetupPhase.FAILED);
      this.awaitState(thread, ChatController.State.WAITING);

      thread.skipSetup();

      assertNotNull(this.awaitPost(Permission.class));
      assertNull(this.record.pendingSetup);
    } finally {
      thread.dispose();
    }
  }

  public void testStopCancelsTheSetupAndClosingClearsWhatTheChatShows() throws Exception {
    ChatController thread = this.setupController(SystemInfo.isWindows ? "ping -n 61 127.0.0.1 > nul" : "sleep 60");
    thread.open();
    thread.send("write a.txt", List.of(), List.of(), 1, 0, QueuedPrompts.Delivery.QUEUE);
    this.awaitState(thread, ChatController.State.WORKING);
    while (this.awaitPost(Setup.class).step() != 1) {
    }

    thread.interrupt();

    Setup failed = this.awaitSetup(SetupPhase.FAILED);
    assertEquals(TakoBundle.message("setup.error.cancelled"), failed.error());
    this.awaitState(thread, ChatController.State.WAITING);

    thread.dispose();

    List<ThreadMessage> posts = this.postsUntil(Exit.class);
    Setup cleared = find(posts, Setup.class);
    assertNotNull("the card goes", cleared);
    assertEquals(SetupPhase.DONE, cleared.phase());
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

  private Setup awaitSetup(SetupPhase phase) throws InterruptedException {
    while (true) {
      Setup setup = this.awaitPost(Setup.class);
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
    this.awaitPost(Exit.class);
    assertEquals(ChatController.State.STOPPED, thread.state());
  }

  private static <T> T find(List<ThreadMessage> messages, Class<T> type) {
    for (ThreadMessage message : messages) {
      if (type.isInstance(message)) {
        return type.cast(message);
      }
    }
    return null;
  }
}
