package io.firebite.tako.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.store.ThreadPast;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.Conversation;
import io.firebite.tako.chat.watch.PullRequestStatus;
import io.firebite.tako.chat.watch.PullRequestWatcher;
import io.firebite.tako.chat.watch.WatchException;
import io.firebite.tako.ide.project.GitWorktree;
import io.firebite.tako.json.Json;
import io.firebite.tako.mcp.McpToolException;
import io.firebite.tako.mcp.McpTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * What Tako offers one thread's agent over MCP: starting threads of its own, following and steering any thread, and
 * watching its pull request.
 */
final class ThreadTools implements McpTools {
  static final String CREATE = "create_thread";
  static final String LIST = "list_threads";
  static final String GET = "get_thread";
  static final String SEND = "send_to_thread";
  static final String WAIT = "wait_for_thread";
  static final String STOP = "stop_thread";
  static final String WATCH = "watch_pull_request";
  static final String UNWATCH = "unwatch_pull_request";
  private static final long DEFAULT_WAIT_SECONDS = 300;
  private static final long MAX_WAIT_SECONDS = 3600;
  /** About 10k tokens, below what agents take from one tool result. */
  private static final int MAX_CONVERSATION_CHARS = 40_000;
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final String THREAD_ID = "threadId";
  private static final String CONVERSATION = "conversation";
  private static final String NOTE = "note";
  private static final String STATES = "starting, working, waiting (for the person to answer a permission prompt or "
    + "question, or to retry a failed worktree setup), idle (done, waiting for a prompt), stopped (the agent failed or exited) or closed (not open in Tako)";

  private final ChatService service;
  private final ThreadStore store;
  private final String threadId;

  /** @param threadId the thread whose agent calls */
  ThreadTools(ChatService service, ThreadStore store, String threadId) {
    this.service = service;
    this.store = store;
    this.threadId = threadId;
  }

  @Override
  public List<Tool> tools() {
    JsonArray agents = new JsonArray();
    for (AgentBackend backend : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
      agents.add(backend.id());
    }
    JsonObject createProperties = new JsonObject();
    createProperties.add("prompt", property("string", "The task. The new thread's agent sees nothing of your "
      + "conversation, so say everything it needs: the goal, the files and findings that matter, constraints and how "
      + "to check the result."));
    createProperties.add("title", property("string", "A short title for Tako's thread list, e.g. "
      + "\"Skeletal animation\". With worktree, it also names the branch."));
    createProperties.add("worktree", property("boolean", "Work in a new git worktree on a new branch, checked out "
      + "from the commit you are on. Uncommitted changes do not carry over. Use it when threads would edit the same "
      + "files at once. Tako first runs the repository's .worktreesetup commands in it, if it has them, such as "
      + "installing dependencies. Defaults to false: the thread works in your folder."));
    JsonObject agent = property("string", "The agent to run. Defaults to yours.");
    agent.add("enum", agents);
    createProperties.add("agent", agent);
    createProperties.add("model", property("string", "The model. Defaults to yours when the agent is yours, else "
      + "to the agent's default."));
    createProperties.add("mode", property("string", "The permission or approval mode, by the agent's id for it, "
      + "e.g. Claude Code's \"plan\" or \"acceptEdits\". Defaults to yours when the agent is yours, else to the "
      + "agent's default."));
    createProperties.add("effort", property("string", "The reasoning effort, by the agent's id for it, e.g. "
      + "\"low\", \"medium\" or \"high\". Defaults to yours when the agent is yours, else to the agent's default."));

    JsonObject threadIdProperties = new JsonObject();
    threadIdProperties.add(THREAD_ID, property("string", "The id create_thread or list_threads gave."));

    JsonObject getProperties = threadIdProperties.deepCopy();
    getProperties.add(CONVERSATION, property("boolean", "Also return the whole conversation as text: each prompt "
      + "and the agent's answers, without tool calls. The oldest turns are left out when it is long. Use it to read "
      + "a thread for context. Defaults to false."));

    JsonObject sendProperties = threadIdProperties.deepCopy();
    sendProperties.add("prompt", property("string", "The prompt, e.g. a follow-up or a correction. The thread's agent "
      + "knows its own conversation, but nothing of yours."));

    JsonObject waitProperties = threadIdProperties.deepCopy();
    waitProperties.add("timeoutSeconds", property("integer", "How long to wait at most, from 1 to " + MAX_WAIT_SECONDS
      + ". Defaults to " + DEFAULT_WAIT_SECONDS + "."));

    JsonObject watchProperties = new JsonObject();
    watchProperties.add("url", property("string", "The pull or merge request's address, e.g. "
      + "https://github.com/owner/repo/pull/12 or https://gitlab.com/group/project/-/merge_requests/7. Defaults to "
      + "the open pull or merge request of your branch."));

    return List.of(
      new Tool(WATCH, "Watches a GitHub pull request or GitLab merge request for your thread. Tako checks it every 2 "
        + "minutes with gh or glab and wakes you with a prompt when checks or pipeline jobs fail or all pass, someone "
        + "reviews, approves or comments, the branch gets merge conflicts, or it is merged or closed. Only changes after this call wake you; the result tells "
        + "the current state. Use it after you push or open a pull request and should follow up on CI and reviews. "
        + "The watch ends when the pull request is merged or closed. Call unwatch_pull_request when you hand the work "
        + "back to the person.", schema(watchProperties)),
      new Tool(UNWATCH, "Stops watching your thread's pull request, so Tako no longer wakes you about it.",
        schema(new JsonObject())),
      new Tool(CREATE, "Starts a new Tako chat thread that works on a task alongside you. Its agent starts on the "
        + "prompt at once, or in a new worktree once Tako ran its setup. The person sees the thread in Tako and answers its permission prompts. Threads in the same "
        + "folder edit the same files, so give overlapping tasks a worktree each. Returns the new thread's id.",
        schema(createProperties, "prompt", "title")),
      new Tool(LIST, "Lists the project's chat threads in Tako with their state: " + STATES + ".",
        schema(new JsonObject())),
      new Tool(GET, "Tells what a thread is doing: its state (" + STATES + "), its folder and branch, and the text "
        + "its agent wrote since its last prompt, or with conversation, the whole conversation instead.",
        schema(getProperties, THREAD_ID)),
      new Tool(SEND, "Sends a prompt to another thread as if the person typed it, and opens the thread if it is "
        + "closed. While the thread works or waits for the person, the prompt queues and goes once the running turn "
        + "ends, and the person can edit or remove it. The result has queued set when the thread was busy at the call, "
        + "a hint since its turn may end meanwhile. Fails while the thread is "
        + "starting. Returns at once; wait_for_thread then gives the answer once the queued prompts ran, but returns "
        + "early while the thread waits for the person.",
        schema(sendProperties, THREAD_ID, "prompt")),
      new Tool(WAIT, "Waits until another thread is done working: idle, waiting for the person, stopped or closed. "
        + "Returns what get_thread does, with timedOut set when the thread still works after the timeout. Call it "
        + "again to keep waiting.", schema(waitProperties, THREAD_ID)),
      new Tool(STOP, "Interrupts another thread's running turn, or cancels the worktree setup "
        + "its prompt waits for. The thread stays open and takes new prompts.",
        schema(threadIdProperties, THREAD_ID)));
  }

  @Override
  public String call(String name, JsonObject arguments) throws McpToolException {
    return switch (name) {
      case CREATE -> this.create(arguments);
      case LIST -> this.list();
      case GET -> this.get(arguments);
      case SEND -> this.send(arguments);
      case WAIT -> this.await(arguments);
      case STOP -> this.stop(arguments);
      case WATCH -> this.watch(arguments);
      case UNWATCH -> this.unwatch();
      default -> throw new McpToolException("Unknown tool " + name);
    };
  }

  private String create(JsonObject arguments) throws McpToolException {
    String prompt = required(arguments, "prompt");
    String title = required(arguments, "title");
    ThreadStore.Record parent = this.store.find(this.threadId);
    if (parent == null) {
      throw new McpToolException("Your thread no longer exists in Tako.");
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    String agent = Json.string(arguments, "agent");
    AgentBackend backend = AgentRegistry.find(agent != null ? agent : parent.agent(), environment);
    if (agent != null && !backend.id().equals(agent)) {
      throw new McpToolException("Unknown agent " + agent);
    }
    boolean isSameAgent = backend.id().equals(parent.agent());
    GitWorktree.Worktree worktree = null;
    if (Json.bool(arguments, "worktree")) {
      String folder = this.service.workFolder(parent);
      worktree = GitWorktree.create(Path.of(folder), title, environment);
      if (worktree == null) {
        throw new McpToolException("Could not create a git worktree from " + folder
          + ". Is it in a git repository with a commit?");
      }
    }
    NewThread spec = new NewThread(title, prompt, backend.id(),
      orDefault(Json.string(arguments, "model"), isSameAgent ? parent.model : ""),
      orDefault(Json.string(arguments, "mode"), isSameAgent ? parent.mode : ""),
      orDefault(Json.string(arguments, "effort"), isSameAgent ? parent.effort : ""),
      worktree != null ? worktree.folder().toString() : parent.workPath,
      worktree != null ? ThreadStore.PendingSetup.of(worktree.repository(), worktree.folder()) : null);
    AtomicReference<ThreadStore.Record> created = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> created.set(this.service.spawn(parent, spec)));

    JsonObject result = this.describe(created.get(), this.service.controller(created.get().id));
    if (worktree != null) {
      result.addProperty("branch", worktree.branch());
    }
    return GSON.toJson(result);
  }

  private String list() {
    JsonArray threads = new JsonArray();
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && !record.sideChat) {
        threads.add(this.describe(record, this.service.controller(record.id)));
      }
    }
    return GSON.toJson(threads);
  }

  private String get(JsonObject arguments) throws McpToolException {
    ThreadStore.Record record = this.find(arguments);
    ChatController controller = this.service.controller(record.id);
    JsonObject thread = this.details(record, controller);
    if (Json.bool(arguments, CONVERSATION)) {
      thread.remove("answer");
      this.addConversation(thread, record, controller);
    }
    return GSON.toJson(thread);
  }

  /**
   * Adds the open thread's conversation, or else what its earlier agents and its agent's records hold, with a note on
   * what cannot be read. Reads the records without starting the agent, so the thread stays as it is.
   */
  private void addConversation(JsonObject thread, ThreadStore.Record record, @Nullable ChatController controller) {
    String text = controller != null ? controller.conversation(MAX_CONVERSATION_CHARS) : "";
    if (!text.isEmpty()) {
      thread.addProperty(CONVERSATION, text);
      return;
    }
    Conversation conversation = new Conversation();
    conversation.restore(ThreadPast.read(this.store.pastFile(record.id)).firstSegments(record.agentSwitches));
    if (record.sessionId == null || !conversation.isEmpty()) {
      thread.addProperty(CONVERSATION, conversation.text(MAX_CONVERSATION_CHARS));
    }
    if (record.sessionId == null) {
      return;
    }
    AgentBackend backend = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap());
    switch (backend.history(this.service.workFolder(record), record.sessionId)) {
      case AgentHistory.Loaded loaded -> {
        conversation.reset(loaded.events());
        thread.addProperty(CONVERSATION, conversation.text(MAX_CONVERSATION_CHARS));
      }
      case AgentHistory.Missing missing -> thread.addProperty(NOTE, "The conversation cannot be read: the agent's "
        + "records of this session are gone.");
      case AgentHistory.FromSession fromSession -> {
        if (controller == null) {
          thread.addProperty(NOTE, "The conversation cannot be read: " + backend.displayName() + " gives it only "
            + "to a running thread, and this one is closed.");
        } else if (controller.state() == ChatController.State.STARTING) {
          thread.addProperty(NOTE, "The thread is starting and its conversation is still loading. Call get_thread "
            + "again in a moment.");
        } else {
          thread.addProperty(CONVERSATION, text);
        }
      }
    }
  }

  private String send(JsonObject arguments) throws McpToolException {
    ThreadStore.Record record = this.other(arguments);
    String prompt = required(arguments, "prompt");
    ChatController controller = this.service.controller(record.id);
    ChatController.State state = controller != null ? controller.state() : null;
    if (state == ChatController.State.STARTING) {
      throw new McpToolException("The thread is starting. Call wait_for_thread first.");
    }
    ApplicationManager.getApplication().invokeAndWait(() -> this.service.prompt(record, prompt));
    JsonObject thread = this.describe(record, this.service.controller(record.id));
    if (state == ChatController.State.WORKING || state == ChatController.State.WAITING) {
      thread.addProperty("queued", true);
    }
    return GSON.toJson(thread);
  }

  private String await(JsonObject arguments) throws McpToolException {
    ThreadStore.Record record = this.other(arguments);
    long seconds = Json.isNumber(arguments, "timeoutSeconds")
      ? Math.clamp(Json.number(arguments, "timeoutSeconds"), 1, MAX_WAIT_SECONDS)
      : DEFAULT_WAIT_SECONDS;
    ChatController controller = this.service.controller(record.id);
    if (controller != null) {
      try {
        controller.awaitSettled(TimeUnit.SECONDS.toMillis(seconds));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new McpToolException("Stopped waiting for the thread");
      }
      controller = this.service.controller(record.id);
    }
    JsonObject thread = this.details(record, controller);
    if (controller != null && controller.state().isBusy()) {
      thread.addProperty("timedOut", true);
    }
    return GSON.toJson(thread);
  }

  private String stop(JsonObject arguments) throws McpToolException {
    ThreadStore.Record record = this.other(arguments);
    ChatController controller = this.service.controller(record.id);
    if (controller != null) {
      controller.interrupt();
    }
    return GSON.toJson(this.describe(record, controller));
  }

  private String watch(JsonObject arguments) throws McpToolException {
    PullRequestWatcher.Started started;
    try {
      started = this.service.watcher().start(this.threadId, Json.string(arguments, "url"), true);
    } catch (WatchException e) {
      throw new McpToolException(e.getMessage());
    }
    return GSON.toJson(watchResult(started));
  }

  static JsonObject watchResult(PullRequestWatcher.Started started) {
    PullRequestStatus status = started.status();
    JsonObject result = Json.of("watching", status.label());
    result.addProperty("url", status.url());
    result.addProperty("title", status.title());
    JsonArray failed = new JsonArray();
    for (PullRequestStatus.Check check : status.checks()) {
      if (check.outcome() == PullRequestStatus.Outcome.FAILED) {
        failed.add(check.name());
      }
    }
    JsonObject checks = new JsonObject();
    checks.addProperty("total", status.checks().size());
    checks.addProperty("pending", status.count(PullRequestStatus.Outcome.PENDING));
    checks.add("failed", failed);
    result.add("checks", checks);
    result.addProperty("conflicting", "CONFLICTING".equals(status.mergeable()));
    result.addProperty(NOTE, started.kept()
      ? "You already watched it. Nothing changed about the watch."
      : "Only changes from now on wake you. Use the state above to act on what is already there.");
    return result;
  }

  private String unwatch() {
    String stopped = this.service.watcher().stop(this.threadId);
    JsonObject result = new JsonObject();
    result.addProperty("watching", false);
    if (stopped != null) {
      result.addProperty("stopped", stopped);
    }
    return GSON.toJson(result);
  }

  private ThreadStore.Record find(JsonObject arguments) throws McpToolException {
    String id = required(arguments, THREAD_ID);
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      throw new McpToolException("No thread " + id);
    }
    return record;
  }

  /** The thread the arguments name, which must not be the caller's own. */
  private ThreadStore.Record other(JsonObject arguments) throws McpToolException {
    ThreadStore.Record record = this.find(arguments);
    if (record.id.equals(this.threadId)) {
      throw new McpToolException("That is your own thread.");
    }
    return record;
  }

  private JsonObject details(ThreadStore.Record record, @Nullable ChatController controller) {
    JsonObject thread = this.describe(record, controller);
    if (controller != null) {
      String error = controller.error();
      if (error != null && !error.isBlank()) {
        thread.addProperty("error", error);
      }
      thread.addProperty("answer", controller.lastAnswer());
    }
    return thread;
  }

  private JsonObject describe(ThreadStore.Record record, @Nullable ChatController controller) {
    JsonObject thread = Json.of(THREAD_ID, record.id);
    thread.addProperty("title", record.title);
    thread.addProperty("agent", record.agent());
    thread.addProperty("state", controller == null ? "closed" : controller.state().name().toLowerCase(Locale.ROOT));
    thread.addProperty("folder", this.service.workFolder(record));
    if (record.branch != null) {
      thread.addProperty("branch", record.branch);
    }
    if (record.id.equals(this.threadId)) {
      thread.addProperty("isYou", true);
    }
    if (this.threadId.equals(record.parentId) && !record.sideChat) {
      thread.addProperty("createdByYou", true);
    }
    return thread;
  }

  private static String required(JsonObject arguments, String key) throws McpToolException {
    String value = Json.string(arguments, key);
    if (value == null || value.isBlank()) {
      throw new McpToolException("Missing " + key);
    }
    return value;
  }

  private static String orDefault(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static JsonObject property(String type, String description) {
    JsonObject property = Json.of("type", type);
    property.addProperty("description", description);
    return property;
  }

  private static JsonObject schema(JsonObject properties, String... required) {
    JsonObject schema = Json.of("type", "object");
    schema.add("properties", properties);
    JsonArray names = new JsonArray();
    for (String name : required) {
      names.add(name);
    }
    schema.add("required", names);
    return schema;
  }

  /**
   * @param workPath the folder to work in instead of the parent's project folder, see {@link ThreadStore.Record}
   * @param setup    the setup to run first in the worktree made for the thread
   */
  record NewThread(
    String title,
    String prompt,
    String agent,
    String model,
    String mode,
    String effort,
    @Nullable String workPath,
    ThreadStore.@Nullable PendingSetup setup
  ) {
  }
}
