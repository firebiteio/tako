package io.firebite.tako.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.watch.PullRequestStatus;
import io.firebite.tako.chat.watch.PullRequestWatch;
import io.firebite.tako.chat.watch.PullRequestWatcher;
import io.firebite.tako.mcp.McpToolException;
import io.firebite.tako.mcp.McpTools;
import java.util.ArrayList;
import java.util.List;

public class PullRequestWatchServiceTest extends BasePlatformTestCase {
  private static final String VIEW = "{\"number\":12,\"url\":\"https://github.com/o/r/pull/12\",\"title\":\"T\","
    + "\"state\":\"OPEN\",\"headRefOid\":\"abc\",\"baseRefName\":\"main\",\"mergeable\":\"CONFLICTING\","
    + "\"statusCheckRollup\":[{\"__typename\":\"CheckRun\",\"name\":\"build\",\"status\":\"COMPLETED\","
    + "\"conclusion\":\"FAILURE\"},{\"__typename\":\"CheckRun\",\"name\":\"test\",\"status\":\"QUEUED\"}],"
    + "\"reviews\":[]}";

  private ChatService service;
  private ThreadStore store;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.service = ChatService.getInstance(this.getProject());
    this.store = ThreadStore.getInstance(this.getProject());
  }

  private ThreadStore.Record watchedThread() {
    ThreadStore.Record record = this.store.create(null, null, null, "claude");
    record.watch = PullRequestWatch.baseline(PullRequestStatus.parse(VIEW, "[]"), "me");
    return record;
  }

  public void testArchivingEndsTheWatch() {
    ThreadStore.Record record = this.watchedThread();

    this.service.archive(record.id);

    assertNull(record.watch);
    assertNull(record.toItem().watching());
  }

  public void testUnwatchToolStopsTheCallersWatchOnly() throws McpToolException {
    ThreadStore.Record mine = this.watchedThread();
    ThreadStore.Record other = this.watchedThread();
    ThreadTools tools = new ThreadTools(this.service, this.store, mine.id);

    JsonObject first = JsonParser.parseString(tools.call(ThreadTools.UNWATCH, new JsonObject())).getAsJsonObject();
    JsonObject second = JsonParser.parseString(tools.call(ThreadTools.UNWATCH, new JsonObject())).getAsJsonObject();

    assertEquals("#12", first.get("stopped").getAsString());
    assertFalse(first.get("watching").getAsBoolean());
    assertFalse(second.has("stopped"));
    assertNull(mine.watch);
    assertNotNull(other.watch);
  }

  public void testWatchToolIsOffered() {
    List<String> names = new ArrayList<>();
    for (McpTools.Tool tool : new ThreadTools(this.service, this.store, "t").tools()) {
      names.add(tool.name());
    }

    assertTrue(names.contains(ThreadTools.WATCH));
    assertTrue(names.contains(ThreadTools.UNWATCH));
  }

  public void testWatchResultTellsTheCurrentState() {
    JsonObject result = ThreadTools.watchResult(new PullRequestWatcher.Started(PullRequestStatus.parse(VIEW, "[]"),
      false));

    assertEquals("#12", result.get("watching").getAsString());
    assertEquals(2, result.getAsJsonObject("checks").get("total").getAsInt());
    assertEquals(1, result.getAsJsonObject("checks").get("pending").getAsInt());
    assertEquals("build", result.getAsJsonObject("checks").getAsJsonArray("failed").get(0).getAsString());
    assertTrue(result.get("conflicting").getAsBoolean());
    assertTrue(result.get("note").getAsString().startsWith("Only changes from now on"));
  }

  public void testWatchToolWithoutPullRequestTellsTheAgentWhy() {
    ThreadStore.Record record = this.store.create(null, null, "Z:/tako-no-such-folder", "claude");
    ThreadTools tools = new ThreadTools(this.service, this.store, record.id);

    try {
      tools.call(ThreadTools.WATCH, new JsonObject());
      fail("The watch started");
    } catch (McpToolException e) {
      assertEquals("This thread's branch has no open GitHub pull request. Pass url.", e.getMessage());
    }
  }
}
