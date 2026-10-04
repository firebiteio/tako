package io.firebite.tako.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.project.GitWorktree;
import io.firebite.tako.json.Json;
import io.firebite.tako.mcp.McpToolException;
import io.firebite.tako.mcp.McpTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/** What Tako offers one thread's agent over MCP: starting threads of its own and following any thread. */
final class ThreadTools implements McpTools {
  static final String CREATE = "create_thread";
  static final String LIST = "list_threads";
  static final String GET = "get_thread";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final String THREAD_ID = "threadId";
  private static final String STATES = "starting, working, waiting (for the person to answer a permission prompt or "
    + "question), idle (done, waiting for a prompt), stopped (the agent failed or exited) or closed (not open in Tako)";

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
      + "files at once. Defaults to false: the thread works in your folder."));
    JsonObject agent = property("string", "The agent to run. Defaults to yours.");
    agent.add("enum", agents);
    createProperties.add("agent", agent);
    createProperties.add("model", property("string", "The model. Defaults to yours when the agent is yours, else "
      + "to the agent's default."));
    createProperties.add("mode", property("string", "The permission or approval mode, by the agent's id for it, "
      + "e.g. Claude Code's \"plan\" or \"acceptEdits\". Defaults to yours when the agent is yours, else to the "
      + "agent's default."));

    JsonObject threadIdProperties = new JsonObject();
    threadIdProperties.add(THREAD_ID, property("string", "The id create_thread or list_threads gave."));

    return List.of(
      new Tool(CREATE, "Starts a new Tako chat thread that works on a task alongside you. Its agent starts on the "
        + "prompt at once. The person sees the thread in Tako and answers its permission prompts. Threads in the same "
        + "folder edit the same files, so give overlapping tasks a worktree each. Returns the new thread's id.",
        schema(createProperties, "prompt", "title")),
      new Tool(LIST, "Lists the project's chat threads in Tako with their state: " + STATES + ".",
        schema(new JsonObject())),
      new Tool(GET, "Tells what a thread is doing: its state (" + STATES + "), its folder and branch, and the text "
        + "its agent wrote since its last prompt.", schema(threadIdProperties, THREAD_ID)));
  }

  @Override
  public String call(String name, JsonObject arguments) throws McpToolException {
    return switch (name) {
      case CREATE -> this.create(arguments);
      case LIST -> this.list();
      case GET -> this.get(arguments);
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
      isSameAgent ? parent.effort : "",
      worktree != null ? worktree.folder().toString() : parent.workPath);
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
      if (!record.archived) {
        threads.add(this.describe(record, this.service.controller(record.id)));
      }
    }
    return GSON.toJson(threads);
  }

  private String get(JsonObject arguments) throws McpToolException {
    String id = required(arguments, THREAD_ID);
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      throw new McpToolException("No thread " + id);
    }
    ChatController controller = this.service.controller(record.id);
    JsonObject thread = this.describe(record, controller);
    if (controller != null) {
      String error = controller.error();
      if (error != null && !error.isBlank()) {
        thread.addProperty("error", error);
      }
      thread.addProperty("answer", controller.lastAnswer());
    }
    return GSON.toJson(thread);
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
    if (this.threadId.equals(record.parentId)) {
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

  /** @param workPath the folder to work in instead of the parent's project folder, see {@link ThreadStore.Record} */
  record NewThread(
    String title,
    String prompt,
    String agent,
    String model,
    String mode,
    String effort,
    @Nullable String workPath
  ) {
  }
}
