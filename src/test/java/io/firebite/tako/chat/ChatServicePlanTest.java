package io.firebite.tako.chat;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.claude.FakeClaude;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.settings.AuthMode;
import io.firebite.tako.settings.ChatSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

public class ChatServicePlanTest extends BasePlatformTestCase {
  private static final String PLAN = "# Plan: Add dark mode\n\n1. Add a theme setting\n2. Use it\n";

  private ChatService service;
  private ThreadStore store;
  private Set<String> before;
  private @Nullable String activeBefore;
  private String claudePath;
  private AuthMode authMode;
  private String claudeMode;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.service = ChatService.getInstance(this.getProject());
    this.store = ThreadStore.getInstance(this.getProject());
    this.before = this.ids();
    this.activeBefore = this.store.activeId();
    ChatSettings.Data settings = ChatSettings.state();
    this.claudePath = settings.claudePath;
    this.authMode = settings.authMode;
    this.claudeMode = settings.claudeMode;
    settings.claudePath = FakeExecutable.write("claude", FakeClaude.class).toString();
    settings.authMode = AuthMode.CLI_LOGIN;
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      ThreadStore.Record parking = this.store.create(null, null, null, "claude");
      this.store.setActiveId(parking.id);
      for (String id : this.ids()) {
        if (!this.before.contains(id) && !id.equals(parking.id)) {
          this.service.close(id);
        }
      }
      this.store.remove(parking.id);
      if (this.activeBefore != null) {
        this.store.setActiveId(this.activeBefore);
      }
      ChatSettings.Data settings = ChatSettings.state();
      settings.claudePath = this.claudePath;
      settings.authMode = this.authMode;
      settings.claudeMode = this.claudeMode;
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

  private ThreadStore.Record source(@Nullable String workPath) {
    ThreadStore.Record source = this.store.create("session-1", "Planning", null, "claude");
    source.model = "opus";
    source.mode = "plan";
    source.effort = "high";
    source.workPath = workPath;
    return source;
  }

  private static @Nullable String await(CompletableFuture<@Nullable String> future) {
    PlatformTestUtil.waitWithEventsDispatching("The plan thread did not start", future::isDone, 30);
    return future.getNow(null);
  }

  public void testPlanThreadCopiesTheSourceButNotItsPlanMode() {
    ThreadStore.Record source = this.source("/work/here");

    ThreadStore.Record record = this.service.planThread(source, PLAN, "/work/tree");

    assertEquals("Implement Add dark mode", record.title);
    assertEquals("claude", record.agent());
    assertEquals("opus", record.model);
    assertEquals("default", record.mode);
    assertEquals("high", record.effort);
    assertEquals("/work/tree", record.workPath);
    assertNull(record.projectPath);
    assertNull(record.parentId);
    assertNull(record.sessionId);
    assertEquals("plan", source.mode);
    assertSame(record, this.store.find(record.id));
  }

  public void testPlanThreadKeepsAModeOtherThanPlan() {
    ThreadStore.Record source = this.source(null);
    source.mode = "acceptEdits";

    assertEquals("acceptEdits", this.service.planThread(source, PLAN, null).mode);
  }

  public void testPlanThreadLeavesPlanModeTheSettingsWouldStartIn() {
    ChatSettings.state().claudeMode = "plan";
    ThreadStore.Record source = this.source(null);
    source.mode = "";

    assertEquals("default", this.service.planThread(source, PLAN, null).mode);
  }

  public void testPlanThreadOfALegacyThreadRunsClaude() {
    ThreadStore.Record source = this.source(null);
    source.agent = null;

    assertEquals("claude", this.service.planThread(source, PLAN, null).agent);
  }

  public void testPlanThreadWithoutAHeadingOrWithAHugeOneHasAShortTitle() {
    ThreadStore.Record source = this.source(null);

    assertEquals("Implement plan", this.service.planThread(source, "1. do it", null).title);
    String title = this.service.planThread(source, "# " + "🎨".repeat(500), null).title;
    assertTrue(title, title.length() <= 40);
    assertTrue(title, title.startsWith("Implement 🎨"));
  }

  public void testPlanThreadAddsTheProjectFolderAgainWhenItWasRemoved() throws Exception {
    String folder = Files.createTempDirectory("tako-project").toString();
    ThreadStore.Record source = this.source(null);
    source.projectPath = folder;

    ThreadStore.Record record = this.service.planThread(source, PLAN, null);

    assertEquals(folder, record.projectPath);
    assertNotNull(this.store.findProject(folder));
    this.store.removeProject(folder);
  }

  public void testImplementingAPlanStartsAndSelectsANewThread() throws Exception {
    String folder = Files.createTempDirectory("tako-plan").toString();
    ThreadStore.Record source = this.source(folder);

    String id = await(this.service.implementPlan(source.id, PLAN, false));

    assertNotNull(id);
    ThreadStore.Record record = this.store.find(id);
    assertNotNull(record);
    assertEquals(folder, record.workPath);
    assertEquals("default", record.mode);
    assertEquals("Implement Add dark mode", record.title);
    assertEquals(id, this.store.activeId());
    assertNotNull(this.service.controller(id));
  }

  public void testImplementingTwiceStartsTwoThreads() throws Exception {
    ThreadStore.Record source = this.source(Files.createTempDirectory("tako-plan").toString());

    String first = await(this.service.implementPlan(source.id, PLAN, false));
    String second = await(this.service.implementPlan(source.id, PLAN, false));

    assertNotNull(first);
    assertNotNull(second);
    assertFalse(first.equals(second));
  }

  public void testImplementingThePlanOfAnUnknownThreadStartsNothing() {
    CompletableFuture<@Nullable String> future = this.service.implementPlan("gone", PLAN, false);

    assertTrue(future.isDone());
    assertNull(future.getNow("started"));
    assertEquals(this.before, this.ids());
  }

  public void testImplementingInAFolderDeletedOutsideTheIdeStartsNothing() throws Exception {
    Path folder = Files.createTempDirectory("tako-plan");
    Files.delete(folder);
    ThreadStore.Record source = this.source(folder.toString());
    Set<String> ids = this.ids();

    assertNull(await(this.service.implementPlan(source.id, PLAN, true)));
    assertNull(await(this.service.implementPlan(source.id, PLAN, false)));
    assertEquals(ids, this.ids());
  }

  public void testImplementingInAWorktreeOutsideGitStartsNothing() throws Exception {
    ThreadStore.Record source = this.source(Files.createTempDirectory("tako-no-git").toString());
    Set<String> ids = this.ids();

    assertNull(await(this.service.implementPlan(source.id, PLAN, true)));
    assertEquals(ids, this.ids());
  }

  public void testImplementingInAWorktreeWorksOnANewBranchNamedAfterThePlan() throws Exception {
    Path repository = Files.createTempDirectory("tako-plan-repo").resolve("game");
    Files.createDirectories(repository);
    git(repository, "init", "-q");
    git(repository, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "start");
    ThreadStore.Record source = this.source(repository.toString());

    String id = await(this.service.implementPlan(source.id, PLAN, true));

    assertNotNull(id);
    Path worktree = Path.of(this.store.find(id).workPath);
    assertEquals(repository.resolveSibling("game.worktrees").resolve("add-dark-mode").toRealPath(),
      worktree.toRealPath());
    assertEquals("add-dark-mode", git(worktree, "branch", "--show-current").trim());
  }

  private static String git(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(output, 0, process.exitValue());
    return output;
  }
}
