package io.firebite.tako.chat.thread;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.project.SetupScript;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class WorktreeSetupTest extends BasePlatformTestCase {
  private static final long TIMEOUT_MILLIS = 30_000;
  private static final String FINISHED = "finished";

  private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
  private final List<String> ran = Collections.synchronizedList(new ArrayList<>());
  private final List<WorktreeSetup> setups = new ArrayList<>();
  private Path worktree;
  private Path repository;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.worktree = Files.createTempDirectory("tako-work tree ü");
    this.repository = Files.createTempDirectory("tako-main");
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      for (WorktreeSetup setup : this.setups) {
        setup.dispose();
      }
    } finally {
      super.tearDown();
    }
  }

  private WorktreeSetup setup() {
    return this.setup(true, WorktreeSetup.TIMEOUT_MILLIS);
  }

  private WorktreeSetup setup(boolean trusted, long timeoutMillis) {
    WorktreeSetup setup = new WorktreeSetup(ThreadStore.PendingSetup.of(this.repository, this.worktree),
      new Recorder(), () -> trusted, timeoutMillis);
    this.setups.add(setup);
    return setup;
  }

  private void file(String... lines) throws Exception {
    Files.writeString(this.repository.resolve(SetupScript.FILE), String.join("\n", lines) + "\n",
      StandardCharsets.UTF_8);
  }

  private static String sleep(int seconds) {
    return SystemInfo.isWindows ? "ping -n " + (seconds + 1) + " 127.0.0.1 > nul" : "sleep " + seconds;
  }

  private static String variable(String name) {
    return SystemInfo.isWindows ? "%" + name + "%" : "\"$" + name + "\"";
  }

  private Runnable prompt(String name) {
    return () -> this.ran.add(name);
  }

  /** The first event that matches, after skipping the ones before it. */
  @SuppressWarnings("unchecked")
  private <T> T await(Class<T> type, Predicate<T> matches) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      Object event = this.events.poll(50, TimeUnit.MILLISECONDS);
      if (type.isInstance(event) && matches.test((T) event)) {
        return (T) event;
      }
    }
    throw new AssertionError("No matching " + type.getSimpleName());
  }

  private ChatMessage.Setup awaitPhase(ChatMessage.SetupPhase phase) throws InterruptedException {
    return this.await(ChatMessage.Setup.class, setup -> setup.phase() == phase);
  }

  private void awaitFinished() throws InterruptedException {
    this.await(String.class, FINISHED::equals);
  }

  private void awaitRan(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (this.ran.size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(count, this.ran.size());
  }

  /** What arrives within a moment, for checking that nothing more does. */
  private List<Object> drainFor(long millis) throws InterruptedException {
    List<Object> rest = new ArrayList<>();
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      Object event = this.events.poll(20, TimeUnit.MILLISECONDS);
      if (event != null) {
        rest.add(event);
      }
    }
    return rest;
  }

  public void testRunsTheCommandsInOrderInTheWorktreeThenLetsThePromptGo() throws Exception {
    this.file("# set up", "echo " + variable(SetupScript.WORKTREE_VARIABLE) + "> first.txt",
      "echo " + variable(SetupScript.REPOSITORY_VARIABLE) + "> second.txt");
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));

    setup.start();

    ChatMessage.Setup first = this.await(ChatMessage.Setup.class, s -> s.step() == 1);
    assertEquals(2, first.steps());
    assertTrue(first.command().contains("first.txt"));
    ChatMessage.Setup done = this.awaitPhase(ChatMessage.SetupPhase.DONE);
    assertEquals(2, done.step());
    assertTrue(done.endedAt() >= done.startedAt());
    this.awaitFinished();
    this.awaitRan(1);
    String written = new String(Files.readAllBytes(this.worktree.resolve("first.txt")), StandardCharsets.ISO_8859_1);
    assertTrue("cmd writes the console code page, so only the ASCII part compares: " + written,
      written.contains(this.worktree.getParent().toString() + this.worktree.getFileSystem().getSeparator()
        + "tako-work tree "));
    assertEquals(this.repository.toString(), Files.readString(this.worktree.resolve("second.txt")).strip());
    assertFalse(setup.holds());
    for (Object late : this.drainFor(600)) {
      assertFalse("nothing shows after the setup is done: " + late, late instanceof ChatMessage.Setup);
    }

    setup.whenReady(this.prompt("b"));
    assertEquals("a prompt after the setup goes at once", List.of("a", "b"), this.ran);
  }

  public void testQuotesInACommandReachTheShellAsWritten() throws Exception {
    this.file("echo \"a  b\" > \"quoted file.txt\"", SystemInfo.isWindows ? "mkdir made\\" : "mkdir made/");
    WorktreeSetup setup = this.setup();

    setup.start();

    this.awaitPhase(ChatMessage.SetupPhase.DONE);
    assertTrue("a trailing backslash stays the command's", Files.isDirectory(this.worktree.resolve("made")));
    String written = Files.readString(this.worktree.resolve("quoted file.txt")).strip();
    assertEquals(SystemInfo.isWindows ? "\"a  b\"" : "a  b", written);
  }

  public void testAFailedCommandStopsTheSetupAndHoldsPromptsUntilARetrySucceeds() throws Exception {
    this.file("echo before", "exit 3", "echo never> never.txt");
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));

    setup.start();

    ChatMessage.Setup failed = this.awaitPhase(ChatMessage.SetupPhase.FAILED);
    assertEquals(TakoBundle.message("setup.error.exit", "exit 3", "3"), failed.error());
    assertEquals(2, failed.step());
    assertEquals(failed.error(), this.await(Failed.class, f -> true).reason());
    assertFalse(Files.exists(this.worktree.resolve("never.txt")));
    assertTrue(setup.holdsFailed());
    setup.whenReady(this.prompt("b"));
    assertEquals(failed.error(), this.await(Held.class, held -> held.failure() != null).failure());
    assertTrue(this.ran.isEmpty());

    this.file("echo fixed> fixed.txt");
    setup.retry();

    assertNull(this.await(Held.class, held -> true).failure());
    this.awaitPhase(ChatMessage.SetupPhase.DONE);
    this.awaitRan(2);
    assertEquals("held prompts go in order", List.of("a", "b"), this.ran);
    assertTrue(Files.exists(this.worktree.resolve("fixed.txt")));
  }

  public void testNoFileLetsPromptsGoWithoutShowingAnything() throws Exception {
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));

    setup.start();

    this.awaitFinished();
    this.awaitRan(1);
    for (Object event : this.drainFor(300)) {
      assertFalse(event instanceof ChatMessage.Setup);
    }
    assertFalse(setup.showsCard());
  }

  public void testACommentOnlyFileIsLikeNoFile() throws Exception {
    this.file("# nothing yet", "", "   ");
    WorktreeSetup setup = this.setup();

    setup.start();

    this.awaitFinished();
    assertFalse(setup.showsCard());
  }

  public void testAPromptSentWhileHeldPromptsGoStillGoesLast() throws Exception {
    this.file("echo ok");
    WorktreeSetup setup = this.setup();
    setup.whenReady(() -> {
      this.ran.add("a");
      setup.whenReady(this.prompt("c"));
    });
    setup.whenReady(this.prompt("b"));

    setup.start();

    this.awaitRan(3);
    assertEquals(List.of("a", "b", "c"), this.ran);
  }

  public void testCancelStopsALongCommandAndARetryRightAfterRunsAfresh() throws Exception {
    this.file(sleep(60));
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));
    setup.start();
    this.await(ChatMessage.Setup.class, s -> s.step() == 1);
    Thread.sleep(300);
    long cancelledAt = System.currentTimeMillis();

    assertTrue(setup.cancelHeld());
    assertFalse("a second cancel does nothing", setup.cancel());

    ChatMessage.Setup failed = this.awaitPhase(ChatMessage.SetupPhase.FAILED);
    assertEquals(TakoBundle.message("setup.error.cancelled"), failed.error());
    assertTrue("cancel kills at once", System.currentTimeMillis() - cancelledAt < 15_000);
    for (Object event : this.drainFor(300)) {
      assertFalse("a cancel does not notify", event instanceof Failed);
    }

    this.file("echo again> again.txt");
    setup.retry();
    setup.retry();
    this.awaitPhase(ChatMessage.SetupPhase.DONE);
    this.awaitRan(1);
    assertTrue(Files.exists(this.worktree.resolve("again.txt")));
  }

  public void testCancelBeforeAnyCommandStartedStillCancels() throws Exception {
    this.file(sleep(60));
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));

    setup.start();
    setup.cancel();

    ChatMessage.Setup end = this.await(ChatMessage.Setup.class, s -> s.phase() != ChatMessage.SetupPhase.RUNNING);
    assertEquals(ChatMessage.SetupPhase.FAILED, end.phase());
    assertEquals(TakoBundle.message("setup.error.cancelled"), end.error());
  }

  public void testCancelHeldNeedsAWaitingPrompt() throws Exception {
    this.file(sleep(60));
    WorktreeSetup setup = this.setup();
    setup.start();
    this.await(ChatMessage.Setup.class, s -> s.step() == 1);

    assertFalse(setup.cancelHeld());
    assertTrue(setup.cancel());
    this.awaitPhase(ChatMessage.SetupPhase.FAILED);
    assertFalse("nothing waits", setup.holdsFailed());
  }

  public void testSkipLetsThePromptsGoWhileTheSetupRuns() throws Exception {
    this.file(sleep(60));
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));
    setup.start();
    this.await(ChatMessage.Setup.class, s -> s.step() == 1);

    setup.skip();
    setup.skip();

    this.awaitPhase(ChatMessage.SetupPhase.SKIPPED);
    this.awaitFinished();
    this.awaitRan(1);
    assertFalse(setup.showsCard());
    for (Object event : this.drainFor(500)) {
      assertFalse("the killed run reports nothing: " + event, event instanceof ChatMessage.Setup);
    }
  }

  public void testActionsInTheWrongStateDoNothing() throws Exception {
    this.file("echo ok");
    WorktreeSetup setup = this.setup();
    setup.retry();
    assertFalse(setup.cancel());

    setup.start();
    setup.start();
    this.awaitPhase(ChatMessage.SetupPhase.DONE);
    this.awaitFinished();

    setup.retry();
    setup.skip();
    setup.start();
    assertFalse(setup.cancel());
    for (Object event : this.drainFor(500)) {
      assertFalse("nothing runs again: " + event, event instanceof ChatMessage.Setup || FINISHED.equals(event));
    }
  }

  public void testATimeoutKillsTheCommandAndFails() throws Exception {
    this.file(sleep(60));
    WorktreeSetup setup = this.setup(true, 1000);

    setup.start();

    ChatMessage.Setup failed = this.awaitPhase(ChatMessage.SetupPhase.FAILED);
    assertEquals(TakoBundle.message("setup.error.timeout", "1"), failed.error());
    this.await(Failed.class, f -> true);
  }

  public void testAnUntrustedProjectRunsNothing() throws Exception {
    this.file("echo x> ran.txt");
    WorktreeSetup setup = this.setup(false, WorktreeSetup.TIMEOUT_MILLIS);

    setup.start();

    assertEquals(TakoBundle.message("setup.error.untrusted"), this.awaitPhase(ChatMessage.SetupPhase.FAILED).error());
    assertFalse(Files.exists(this.worktree.resolve("ran.txt")));
  }

  public void testAWorktreeDeletedOutsideTheIdeHasNothingToSetUp() throws Exception {
    this.file("echo x");
    Files.delete(this.worktree);
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));

    setup.start();

    this.awaitFinished();
    this.awaitRan(1);
    assertFalse(setup.showsCard());
  }

  public void testStartingTheAgentWaitsButIsNoPrompt() throws Exception {
    this.file(sleep(60));
    WorktreeSetup setup = this.setup();
    setup.startWhenReady(this.prompt("start"));
    setup.start();
    this.await(ChatMessage.Setup.class, s -> s.step() == 1);

    assertTrue(setup.holds());
    assertFalse(setup.holdsPrompts());
    setup.whenReady(this.prompt("a"));
    assertTrue(setup.holdsPrompts());

    setup.skip();
    this.awaitRan(2);
    assertEquals(List.of("start", "a"), this.ran);
  }

  public void testRetryAfterTheFileWasDeletedTakesTheCardAway() throws Exception {
    this.file("exit 1");
    WorktreeSetup setup = this.setup();
    setup.start();
    this.awaitPhase(ChatMessage.SetupPhase.FAILED);
    assertTrue(setup.showsCard());

    Files.delete(this.repository.resolve(SetupScript.FILE));
    setup.retry();

    ChatMessage.Setup done = this.awaitPhase(ChatMessage.SetupPhase.DONE);
    assertEquals(0, done.steps());
    this.awaitFinished();
    assertFalse(setup.showsCard());
  }

  public void testDisposeWhileRunningKillsAndStaysQuiet() throws Exception {
    this.file(sleep(60) + " && echo late> late.txt");
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));
    setup.start();
    this.await(ChatMessage.Setup.class, s -> s.step() == 1);
    assertTrue(setup.showsCard());

    setup.dispose();
    setup.dispose();

    assertFalse(setup.holds());
    assertTrue(this.drainFor(1500).isEmpty());
    setup.whenReady(this.prompt("b"));
    setup.start();
    setup.retry();
    assertTrue(this.drainFor(500).isEmpty());
    assertTrue(this.ran.isEmpty());
  }

  public void testDroppedPromptsNeverRun() throws Exception {
    this.file("exit 1");
    WorktreeSetup setup = this.setup();
    setup.whenReady(this.prompt("a"));
    setup.start();
    this.awaitPhase(ChatMessage.SetupPhase.FAILED);

    setup.dropHeld();
    setup.skip();

    this.awaitFinished();
    Thread.sleep(300);
    assertTrue(this.ran.isEmpty());
  }

  public void testSettleTellsAboutHeldPromptsOrRunsIdle() throws Exception {
    this.file("exit 1");
    WorktreeSetup setup = this.setup();
    List<String> idle = new ArrayList<>();
    setup.settle(() -> idle.add("idle"));
    assertEquals(List.of("idle"), idle);

    setup.whenReady(this.prompt("a"));
    setup.start();
    this.awaitPhase(ChatMessage.SetupPhase.FAILED);
    this.events.clear();
    setup.settle(() -> idle.add("idle"));

    assertEquals(List.of("idle"), idle);
    assertNotNull(this.await(Held.class, held -> true).failure());
  }

  public void testHugeOutputKeepsItsEndAndUtf8Survives() throws Exception {
    Files.writeString(this.worktree.resolve("text.txt"), "Grüße ✓ 日本\n", StandardCharsets.UTF_8);
    String count = SystemInfo.isWindows ? "for /L %i in (1,1,6000) do @echo line %i" : "seq -f 'line %g' 1 6000";
    this.file(count, (SystemInfo.isWindows ? "type" : "cat") + " text.txt");
    WorktreeSetup setup = this.setup();

    setup.start();

    ChatMessage.Setup done = this.awaitPhase(ChatMessage.SetupPhase.DONE);
    assertTrue(done.output(), done.output().contains("line 6000"));
    assertTrue(done.output(), done.output().contains("Grüße ✓ 日本"));
    assertTrue(done.output().startsWith("…"));
    assertTrue(done.output().length() <= 20_002);
  }

  private record Held(
    String failure
  ) {
  }

  private record Failed(
    String reason
  ) {
  }

  private final class Recorder implements WorktreeSetup.Listener {
    @Override
    public void changed(ChatMessage.Setup setup) {
      WorktreeSetupTest.this.events.add(setup);
    }

    @Override
    public void held(String failure) {
      WorktreeSetupTest.this.events.add(new Held(failure));
    }

    @Override
    public void finished() {
      WorktreeSetupTest.this.events.add(FINISHED);
    }

    @Override
    public void failed(String reason) {
      WorktreeSetupTest.this.events.add(new Failed(reason));
    }
  }
}
