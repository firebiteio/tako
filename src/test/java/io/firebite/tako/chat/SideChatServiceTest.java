package io.firebite.tako.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.mcp.McpToolException;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public class SideChatServiceTest extends BasePlatformTestCase {
  private static final long TIMEOUT_MILLIS = 10000;

  private ChatService service;
  private ThreadStore store;
  private String savedClaudePath;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.service = ChatService.getInstance(this.getProject());
    this.store = ThreadStore.getInstance(this.getProject());
    ChatSettings.Data settings = ChatSettings.state();
    this.savedClaudePath = settings.claudePath;
    settings.claudePath = this.getTestName(false) + "-no-such-claude";
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      List<String> ids = new ArrayList<>();
      for (ThreadStore.Record record : this.store.all()) {
        ids.add(record.id);
      }
      this.service.closeAll(ids);
      ChatSettings.state().claudePath = this.savedClaudePath;
    } finally {
      super.tearDown();
    }
  }

  private ThreadStore.Record thread() {
    return this.store.create(null, null, null, "claude");
  }

  private boolean start(String threadId, int forkPrompts, String question) {
    return this.service.startSideChat(threadId, forkPrompts, new ChatMessage.Submit(question, List.of(), List.of()));
  }

  private List<ThreadStore.Record> sideChatsOf(ThreadStore.Record main) {
    return SideChats.of(this.store, main.id);
  }

  public void testStartsASideChatInTheMainThreadsFolderWithItsModel() {
    ThreadStore.Record main = this.thread();
    main.projectPath = "D:/repo";
    main.workPath = "D:/repo-tako/one";
    main.model = "opus";
    main.mode = "bypassPermissions";
    main.effort = "high";

    assertTrue(this.start(main.id, 0, "Why is the cache 🐙 cold?"));

    List<ThreadStore.Record> sideChats = this.sideChatsOf(main);
    assertEquals(1, sideChats.size());
    ThreadStore.Record side = sideChats.get(0);
    assertEquals(main.id, side.parentId);
    assertEquals("D:/repo", side.projectPath);
    assertEquals("D:/repo-tako/one", side.workPath);
    assertEquals("opus", side.model);
    assertEquals("high", side.effort);
    assertEquals("bypassPermissions", side.mode);
    assertEquals("Why is the cache 🐙 cold?", side.title);
    assertNull(side.sessionId);
    assertEquals(0, side.inheritedPrompts);
    assertNotNull(this.service.controller(side.id));
  }

  public void testAnEmptyQuestionLeavesTheTitleToTheFirstPrompt() {
    ThreadStore.Record main = this.thread();

    this.start(main.id, 0, "  \n ");

    assertEquals(ThreadStore.Record.DEFAULT_TITLE, this.sideChatsOf(main).get(0).title);
  }

  public void testASideChatOfASideChatIsASiblingOnTheMainThread() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "first");
    ThreadStore.Record first = this.sideChatsOf(main).get(0);

    this.start(first.id, 0, "second");

    assertEquals(2, this.sideChatsOf(main).size());
    assertTrue(this.sideChatsOf(first).isEmpty());
  }

  public void testNothingStartsForAThreadThatIsGone() {
    int before = this.store.all().size();

    assertFalse(this.start("gone", 0, "why?"));
    assertEquals(before, this.store.all().size());
  }

  public void testAForkThatFailsStartsWithANewSession() throws InterruptedException {
    ThreadStore.Record main = this.thread();
    main.sessionId = "00000000-0000-0000-0000-00000000dead";

    this.start(main.id, 2, "why?");

    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (this.sideChatsOf(main).isEmpty() && System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      Thread.sleep(10);
    }
    ThreadStore.Record side = this.sideChatsOf(main).get(0);
    assertNull(side.sessionId);
    assertEquals(0, side.inheritedPrompts);
  }

  public void testArchivingTheMainThreadArchivesOnlyItsSideChats() {
    ThreadStore.Record main = this.thread();
    ThreadStore.Record spawned = this.thread();
    spawned.parentId = main.id;
    this.start(main.id, 0, "why?");
    ThreadStore.Record side = this.sideChatsOf(main).get(0);

    this.service.archive(main.id);

    assertTrue(side.archived);
    assertNull("its agent stopped", this.service.controller(side.id));
    assertFalse("a sub thread the agent made is not a side chat", spawned.archived);
  }

  public void testClosingTheMainThreadClosesItsSideChats() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "one");
    this.start(main.id, 0, "two");
    List<ThreadStore.Record> sideChats = this.sideChatsOf(main);

    this.service.close(main.id);

    for (ThreadStore.Record side : sideChats) {
      assertNull(this.store.find(side.id));
      assertNull(this.service.controller(side.id));
    }
  }

  public void testClosingASideChatKeepsTheMainThread() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "one");

    this.service.close(this.sideChatsOf(main).get(0).id);

    assertSame(main, this.store.find(main.id));
    assertTrue(this.sideChatsOf(main).isEmpty());
  }

  public void testSelectingASideChatSelectsItsMainThread() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "why?");
    ThreadStore.Record side = this.sideChatsOf(main).get(0);

    this.service.select(side.id);

    assertEquals(main.id, this.store.activeId());
  }

  public void testArchivingTheShownThreadNeverShowsASideChat() {
    ThreadStore.Record other = this.thread();
    this.start(other.id, 0, "why?");
    ThreadStore.Record main = this.thread();
    this.store.setActiveId(main.id);

    this.service.archive(main.id);

    ThreadStore.Record shown = this.store.find(this.store.activeId());
    assertFalse(shown.sideChat);
  }

  public void testRestoringTheMainThreadListsItsSideChatsWithoutStartingThem() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "why?");
    ThreadStore.Record side = this.sideChatsOf(main).get(0);
    this.service.archive(main.id);

    this.service.restore(main.id);

    assertFalse(main.archived);
    assertFalse(side.archived);
    assertNull(this.service.controller(side.id));
  }

  public void testShowingAnArchivedSideChatStartsItAgain() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "why?");
    ThreadStore.Record side = this.sideChatsOf(main).get(0);
    this.service.archive(main.id);
    this.service.select(main.id);

    this.service.openSideChat(side.id);
    this.service.openSideChat(side.id);

    assertFalse(side.archived);
    assertNotNull(this.service.controller(side.id));
  }

  public void testOpenSideChatLeavesOtherThreadsAlone() {
    ThreadStore.Record thread = this.thread();
    thread.archived = true;

    this.service.openSideChat(thread.id);
    this.service.openSideChat("gone");

    assertTrue(thread.archived);
    assertNull(this.service.controller(thread.id));
  }

  public void testAgentsListSubThreadsButNotSideChats() throws McpToolException {
    ThreadStore.Record main = this.thread();
    ThreadStore.Record spawned = this.thread();
    spawned.parentId = main.id;
    this.start(main.id, 0, "why?");
    ThreadStore.Record side = this.sideChatsOf(main).get(0);
    ThreadTools tools = new ThreadTools(this.service, this.store, main.id);

    JsonArray listed = JsonParser.parseString(tools.call(ThreadTools.LIST, new JsonObject())).getAsJsonArray();

    boolean sawSpawned = false;
    for (JsonElement element : listed) {
      JsonObject thread = element.getAsJsonObject();
      String id = thread.get("threadId").getAsString();
      assertFalse(id.equals(side.id));
      if (id.equals(spawned.id)) {
        sawSpawned = true;
        assertTrue(thread.has("createdByYou"));
      }
    }
    assertTrue(sawSpawned);
  }

  public void testAPromptToASideChatOfAnArchivedThreadListsTheThreadAgain() {
    ThreadStore.Record main = this.thread();
    this.start(main.id, 0, "why?");
    ThreadStore.Record side = this.sideChatsOf(main).get(0);
    this.service.archive(main.id);

    this.service.prompt(side, "and now?");

    assertFalse(main.archived);
    assertFalse(side.archived);
    assertNotNull(this.service.controller(side.id));
  }

  public void testExcerptCarriesTheConversationUnderTheMainThreadsTitle() {
    ContextItem excerpt = SideChats.excerpt("Fix \"parser\"", "Person: why?\n\nAgent: because");

    assertNotNull(excerpt);
    assertEquals(ContextItem.Kind.SELECTION, excerpt.kind());
    assertEquals("Main thread", excerpt.label());
    assertTrue(excerpt.text().contains("Fix \"parser\""));
    assertTrue(excerpt.text().contains("<main_thread>\nPerson: why?\n\nAgent: because\n</main_thread>"));
  }

  public void testNoExcerptWithoutAConversation() {
    assertNull(SideChats.excerpt("Main", ""));
    assertNull(SideChats.excerpt("Main", " \n\t"));
  }

  public void testTitleOfALongQuestionIsCut() {
    String title = SideChats.title("why ".repeat(100) + "?");

    assertTrue(title.length() < 100);
    assertTrue(title.startsWith("why why"));
  }

  public void testMainOfAnOrphanIsItself() {
    ThreadStore.Record orphan = this.thread();
    orphan.sideChat = true;
    orphan.parentId = "gone";

    assertSame(orphan, SideChats.mainOf(this.store, orphan));
  }
}
