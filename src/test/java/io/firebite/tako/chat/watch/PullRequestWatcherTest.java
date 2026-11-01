package io.firebite.tako.chat.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.project.GitInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PullRequestWatcherTest {
  private static final GitInfo.PullRequest PULL_REQUEST = new GitInfo.PullRequest("#12", "Watch", Gh.URL, "open");

  private final FakeGh gh = new FakeGh();
  private final FakeHost host = new FakeHost();
  private Path folder;
  private Path file;
  private ThreadStore store;
  private PullRequestWatcher watcher;
  private long now = 1_000_000;

  @Before
  public void setUp() throws IOException {
    this.folder = Files.createTempDirectory("tako-watch");
    this.file = this.folder.resolve("threads.json");
    this.store = new ThreadStore(this.file);
    this.watcher = this.newWatcher();
  }

  @After
  public void tearDown() throws IOException {
    Files.deleteIfExists(this.file);
    Files.deleteIfExists(this.folder.resolve("threads.json.tmp"));
    Files.deleteIfExists(this.folder);
  }

  private PullRequestWatcher newWatcher() {
    return new PullRequestWatcher(this.store, this.gh, this.host, () -> this.now);
  }

  private ThreadStore.Record thread() {
    ThreadStore.Record record = this.store.create(null, null, null, "claude");
    record.pullRequest = PULL_REQUEST;
    return record;
  }

  private ThreadStore.Record watched() throws WatchException {
    ThreadStore.Record record = this.thread();
    this.watcher.start(record.id, null, false);
    return record;
  }

  private void later() {
    this.now += PullRequestWatcher.POLL_MILLIS;
  }

  private WatchException startFails(ThreadStore.Record record, @Nullable String url) {
    try {
      this.watcher.start(record.id, url, false);
    } catch (WatchException e) {
      return e;
    }
    fail("The watch started");
    return null;
  }

  @Test
  public void startsOnTheBranchPullRequest() throws WatchException {
    ThreadStore.Record record = this.thread();

    PullRequestWatcher.Started started = this.watcher.start(record.id, null, false);

    assertFalse(started.kept());
    assertEquals("#12", started.status().label());
    assertEquals("#12", record.watch.number);
    assertEquals(FakeGh.SELF, record.watch.self);
    assertEquals("#12", record.toItem().watching());
    assertEquals(1, this.host.changes);
    assertEquals(List.of("pr", "view", Gh.URL, "--json", PullRequestStatus.VIEW_FIELDS), this.gh.calls.get(0));
  }

  @Test
  public void startFailsWithoutGh() {
    this.gh.view = new GhCli.Result(GhCli.NOT_STARTED, "", "Cannot run program");

    assertEquals(WatchException.Reason.MISSING, this.startFails(this.thread(), null).reason());
  }

  @Test
  public void startFailsLoggedOut() {
    this.gh.view = new GhCli.Result(4, "", "To get started with GitHub CLI, please run:  gh auth login");

    WatchException e = this.startFails(this.thread(), null);

    assertEquals(WatchException.Reason.LOGGED_OUT, e.reason());
    assertTrue(e.getMessage().contains("gh auth login"));
  }

  @Test
  public void startFailsRateLimited() {
    this.gh.view = new GhCli.Result(1, "", "API rate limit exceeded");

    assertEquals(WatchException.Reason.RATE_LIMITED, this.startFails(this.thread(), null).reason());
  }

  @Test
  public void startsWhenCommentsCannotBeReadWithoutTellingOldOnesLater() throws WatchException {
    this.gh.comments = new GhCli.Result(1, "", "HTTP 502");
    ThreadStore.Record record = this.watched();
    assertTrue(record.watch.commentsUnknown);

    this.gh.comments = ok(Gh.recorded("comments.json"));
    this.later();
    this.watcher.tick();

    assertEquals(List.of(), this.host.prompts);
    assertFalse(record.watch.commentsUnknown);
  }

  @Test
  public void startFailsWithoutLogin() {
    this.gh.self = new GhCli.Result(0, "\n", "");

    assertEquals(WatchException.Reason.FAILED, this.startFails(this.thread(), null).reason());
  }

  @Test
  public void startFailsOnMergedPullRequest() {
    this.gh.view = ok(Gh.view("MERGED", Gh.SHA_A, "UNKNOWN", List.of(), List.of()));

    WatchException e = this.startFails(this.thread(), null);

    assertEquals(WatchException.Reason.NOT_OPEN, e.reason());
    assertEquals("#12 is merged. Only open pull requests can be watched.", e.getMessage());
  }

  @Test
  public void startFailsWithoutPullRequest() {
    ThreadStore.Record record = this.thread();
    record.pullRequest = null;
    ThreadStore.Record gitLab = this.thread();
    gitLab.pullRequest = new GitInfo.PullRequest("!7", "MR", "https://gitlab.com/o/r/-/merge_requests/7", "open");
    ThreadStore.Record merged = this.thread();
    merged.pullRequest = new GitInfo.PullRequest("#12", "Watch", Gh.URL, "merged");

    assertEquals(WatchException.Reason.NO_PULL_REQUEST, this.startFails(record, null).reason());
    assertEquals(WatchException.Reason.NO_PULL_REQUEST, this.startFails(gitLab, null).reason());
    assertEquals(WatchException.Reason.NO_PULL_REQUEST, this.startFails(merged, null).reason());
    assertTrue(this.gh.calls.isEmpty());
  }

  @Test
  public void startFailsOnBadUrl() {
    assertEquals(WatchException.Reason.BAD_URL, this.startFails(this.thread(), "https://github.com/o/r/issues/1")
      .reason());
  }

  @Test
  public void startFailsOnArchivedOrUnknownThread() {
    ThreadStore.Record record = this.thread();
    record.archived = true;

    assertEquals(WatchException.Reason.ARCHIVED, this.startFails(record, null).reason());
    try {
      this.watcher.start("no-such-thread", null, false);
      fail("The watch started");
    } catch (WatchException e) {
      assertEquals(WatchException.Reason.NO_THREAD, e.reason());
    }
  }

  @Test
  public void startFailsWhenTheThreadIsClosedMeanwhile() {
    ThreadStore.Record record = this.thread();
    this.gh.onView = () -> this.store.remove(record.id);

    assertEquals(WatchException.Reason.NO_THREAD, this.startFails(record, null).reason());
    assertNull(record.watch);
  }

  @Test
  public void secondStartWhileOneRunsIsRefused() {
    ThreadStore.Record record = this.thread();
    List<WatchException> nested = new ArrayList<>();
    this.gh.onView = () -> {
      try {
        this.watcher.start(record.id, null, false);
      } catch (WatchException e) {
        nested.add(e);
      }
    };

    try {
      this.watcher.start(record.id, null, false);
    } catch (WatchException e) {
      fail(e.getMessage());
    }

    assertEquals(WatchException.Reason.STARTING, nested.get(0).reason());
    assertNotNull(record.watch);
  }

  @Test
  public void freshLookupFindsThePullRequestOpenedMidTurn() throws WatchException {
    ThreadStore.Record record = this.thread();
    record.pullRequest = null;
    this.host.lookup = PULL_REQUEST;

    this.watcher.start(record.id, null, true);

    assertEquals("#12", record.watch.number);
  }

  @Test
  public void watchingTheSamePullRequestAgainKeepsWhatItSaw() throws WatchException {
    ThreadStore.Record record = this.watched();
    PullRequestWatch first = record.watch;

    PullRequestWatcher.Started started = this.watcher.start(record.id, Gh.URL + "/files", false);

    assertTrue(started.kept());
    assertSame(first, record.watch);
  }

  @Test
  public void watchingAnotherPullRequestReplacesTheWatch() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = ok(Gh.recorded("view-failing.json").replace("/pull/12", "/pull/13").replace("\"number\": 12",
      "\"number\": 13"));

    this.watcher.start(record.id, "https://github.com/firebiteio/tako/pull/13", false);

    assertEquals("#13", record.watch.number);
  }

  @Test
  public void stopEndsTheWatch() throws WatchException {
    ThreadStore.Record record = this.watched();

    assertEquals("#12", this.watcher.stop(record.id));
    assertNull(record.watch);
    assertNull(this.watcher.stop(record.id));
    assertNull(this.watcher.stop("no-such-thread"));
  }

  @Test
  public void nothingChangedTellsNothing() throws WatchException {
    this.watched();
    this.later();
    this.watcher.tick();

    assertEquals(List.of(), this.host.prompts);
  }

  @Test
  public void notPolledBeforeItsTime() throws WatchException {
    this.watched();
    int calls = this.gh.calls.size();

    this.now += PullRequestWatcher.POLL_MILLIS - 1;
    this.watcher.tick();

    assertEquals(calls, this.gh.calls.size());
  }

  @Test
  public void busyThreadIsNotPolledAndHearsTheNewsOnceItSettles() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = ok(Gh.recorded("view-failing.json").replace("\"state\": \"ERROR\"", "\"state\": \"FAILURE\"")
      .replace("SKIPPED", "FAILURE"));
    int calls = this.gh.calls.size();
    this.later();
    for (ChatController.State state : List.of(ChatController.State.STARTING, ChatController.State.WORKING,
      ChatController.State.WAITING)) {
      this.host.states.put(record.id, state);
      this.watcher.tick();
    }
    assertEquals(calls, this.gh.calls.size());

    this.host.states.put(record.id, ChatController.State.IDLE);
    this.watcher.tick();

    assertEquals(1, this.host.prompts.size());
    assertTrue(this.host.prompts.get(0), this.host.prompts.get(0).contains("- Checks failed"));
    assertTrue(this.host.prompts.get(0).contains("  - deploy"));
  }

  @Test
  public void stoppedAndClosedThreadsAreWoken() throws WatchException {
    ThreadStore.Record stopped = this.watched();
    ThreadStore.Record closed = this.watched();
    this.host.states.put(stopped.id, ChatController.State.STOPPED);
    this.gh.comments = ok(Gh.comments(Gh.comment(2000, "dave", "please fix")));
    this.later();

    this.watcher.tick();

    assertEquals(List.of(closed.id, stopped.id), this.host.delivered);
  }

  @Test
  public void refusedDeliveryKeepsTheNewsForTheNextTick() throws WatchException {
    ThreadStore.Record record = this.watched();
    PullRequestWatch watch = record.watch;
    this.gh.comments = ok(Gh.comments(Gh.comment(2000, "dave", "please fix")));
    this.host.accepts = false;
    this.later();

    this.watcher.tick();

    assertSame(watch, record.watch);
    this.host.accepts = true;
    this.now += 1000;
    this.watcher.tick();
    this.later();
    this.watcher.tick();

    assertEquals(2, this.host.prompts.size());
    assertTrue(this.host.prompts.get(1).contains("dave commented on src/Foo.java:3: \"please fix\""));
    assertEquals(List.of(record.id), this.host.delivered);
  }

  @Test
  public void rateLimitPausesWithoutCountingAsFailure() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = new GhCli.Result(1, "", "GraphQL: API rate limit exceeded");
    this.later();
    this.watcher.tick();
    int calls = this.gh.calls.size();

    this.later();
    this.watcher.tick();

    assertEquals(calls, this.gh.calls.size());
    assertEquals(0, record.watch.failures);
    this.now += PullRequestWatcher.RATE_LIMIT_MILLIS;
    this.watcher.tick();
    assertEquals(calls + 1, this.gh.calls.size());
  }

  @Test
  public void repeatedFailuresEndTheWatch() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = new GhCli.Result(1, "", "error connecting to api.github.com");
    for (int i = 1; i < PullRequestWatcher.MAX_FAILURES; i++) {
      this.later();
      this.watcher.tick();
      assertEquals(i, record.watch.failures);
    }

    this.later();
    this.watcher.tick();

    assertNull(record.watch);
    assertEquals(List.of("#12 FAILURES error connecting to api.github.com"), this.host.stops);
  }

  @Test
  public void oneSuccessResetsTheFailures() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = new GhCli.Result(GhCli.TIMED_OUT, "", "gh did not answer within 20 seconds");
    this.later();
    this.watcher.tick();
    assertEquals(1, record.watch.failures);

    this.gh.view = ok(Gh.recorded("view-failing.json"));
    this.later();
    this.watcher.tick();

    assertEquals(0, record.watch.failures);
  }

  @Test
  public void loggingOutEndsTheWatch() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = new GhCli.Result(4, "", "");
    this.later();

    this.watcher.tick();

    assertNull(record.watch);
    assertEquals("#12 LOGGED_OUT ", this.host.stops.get(0));
  }

  @Test
  public void uninstalledGhEndsTheWatch() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = new GhCli.Result(GhCli.NOT_STARTED, "", "gone");
    this.later();

    this.watcher.tick();

    assertNull(record.watch);
    assertTrue(this.host.stops.get(0).startsWith("#12 MISSING"));
  }

  @Test
  public void stopDuringAPollDropsItsResult() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.comments = ok(Gh.comments(Gh.comment(2000, "dave", "please fix")));
    this.gh.onView = () -> this.watcher.stop(record.id);
    this.later();

    this.watcher.tick();

    assertNull(record.watch);
    assertEquals(List.of(), this.host.prompts);
  }

  @Test
  public void closingTheThreadDuringAPollDropsItsResult() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = new GhCli.Result(1, "", "boom");
    this.gh.onView = () -> this.store.remove(record.id);
    this.later();

    this.watcher.tick();

    assertEquals(List.of(), this.host.stops);
    assertEquals(List.of(), this.host.prompts);
  }

  @Test
  public void archivedThreadLosesItsWatchWithoutPolling() throws WatchException {
    ThreadStore.Record record = this.watched();
    int calls = this.gh.calls.size();
    record.archived = true;
    this.later();

    this.watcher.tick();

    assertNull(record.watch);
    assertEquals(calls, this.gh.calls.size());
  }

  @Test
  public void mergedWakesOnceAndEnds() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.view = ok(Gh.view("MERGED", Gh.SHA_A, "UNKNOWN", List.of(), List.of()));
    this.later();

    this.watcher.tick();
    this.later();
    this.watcher.tick();

    assertNull(record.watch);
    assertEquals(1, this.host.prompts.size());
    assertTrue(this.host.prompts.get(0).contains("was merged"));
  }

  @Test
  public void restartRepeatsNothingAndTellsWhatIsNew() throws WatchException {
    ThreadStore.Record record = this.watched();
    this.gh.comments = ok(Gh.comments(Gh.comment(2000, "dave", "please fix")));
    this.later();
    this.watcher.tick();
    assertEquals(1, this.host.prompts.size());

    this.store = new ThreadStore(this.file);
    this.watcher = this.newWatcher();
    ThreadStore.Record loaded = this.store.find(record.id);
    assertEquals(0, loaded.watch.nextPollAt);
    this.watcher.tick();
    assertEquals(1, this.host.prompts.size());

    this.gh.comments = ok(Gh.comments(Gh.comment(2001, "erin", "one more"), Gh.comment(2000, "dave", "please fix")));
    this.later();
    this.watcher.tick();

    assertEquals(2, this.host.prompts.size());
    assertTrue(this.host.prompts.get(1).contains("erin"));
    assertFalse(this.host.prompts.get(1).contains("dave"));
  }

  @Test
  public void brokenUrlInASaveEndsTheWatchQuietly() throws WatchException {
    ThreadStore.Record record = this.watched();
    record.watch.url = "nonsense";
    this.later();

    this.watcher.tick();

    assertNull(record.watch);
    assertEquals(List.of(), this.host.stops);
  }

  private static GhCli.Result ok(String out) {
    return new GhCli.Result(0, out, "");
  }

  /** Answers gh's commands with recorded output. */
  private static final class FakeGh implements GhCli.Runner {
    static final String SELF = "tako-user";

    final List<List<String>> calls = new ArrayList<>();
    GhCli.Result view = ok(Gh.recorded("view-failing.json"));
    GhCli.Result comments = ok(Gh.recorded("comments.json"));
    GhCli.Result self = ok(SELF + "\n");
    @Nullable
    Runnable onView;

    @Override
    public GhCli.Result run(Path directory, List<String> args) {
      this.calls.add(args);
      if (args.get(0).equals("pr")) {
        Runnable hook = this.onView;
        this.onView = null;
        if (hook != null) {
          hook.run();
        }
        return this.view;
      }
      return args.contains("user") ? this.self : this.comments;
    }
  }

  private final class FakeHost implements PullRequestWatcher.Host {
    final Map<String, ChatController.State> states = new HashMap<>();
    final List<String> prompts = new ArrayList<>();
    final List<String> delivered = new ArrayList<>();
    final List<String> stops = new ArrayList<>();
    @Nullable
    GitInfo.PullRequest lookup;
    boolean accepts = true;
    int changes;

    @Override
    public @Nullable ChatController.State state(String threadId) {
      return this.states.get(threadId);
    }

    @Override
    public Path folder(ThreadStore.Record record) {
      return PullRequestWatcherTest.this.folder;
    }

    @Override
    public @Nullable GitInfo.PullRequest lookup(ThreadStore.Record record) {
      return this.lookup;
    }

    @Override
    public boolean deliver(ThreadStore.Record record, PullRequestWatch watch, String prompt) {
      assertSame(watch, record.watch);
      this.prompts.add(prompt);
      if (this.accepts) {
        this.delivered.add(record.id);
      }
      return this.accepts;
    }

    @Override
    public void stopped(ThreadStore.Record record, String number, PullRequestWatcher.Stop reason, String message) {
      this.stops.add(number + " " + reason + " " + message);
    }

    @Override
    public void changed(ThreadStore.Record record) {
      this.changes++;
    }
  }
}
