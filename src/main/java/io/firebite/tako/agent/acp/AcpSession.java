package io.firebite.tako.agent.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.ContextUsage;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.PermissionAnswer;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.agent.PromptQueue;
import io.firebite.tako.agent.rpc.JsonRpcConnection;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/** One ACP agent process serving one thread. See https://agentclientprotocol.com. */
final class AcpSession implements AgentSession, JsonRpcConnection.Handler {
  static final int PROTOCOL_VERSION = 1;
  private static final String SESSION_ID = "sessionId";
  private static final String MCP_SERVERS = "mcpServers";
  private static final String CATEGORY_MODEL = "model";
  private static final String CATEGORY_EFFORT = "thought_level";
  private static final String KIND = "kind";

  private final String name;
  private final JsonRpcConnection connection;
  private final AgentLaunch launch;
  private final AgentListener listener;
  private final AcpTranslator translator;
  private final Map<String, Pending> pendingRequests = new ConcurrentHashMap<>();
  private final PromptQueue prompts = new PromptQueue();
  /** Config option ids by category, such as {@code model} and {@code thought_level}. */
  private final Map<String, String> configIds = new ConcurrentHashMap<>();
  private final List<AgentEvent> history = new ArrayList<>();
  private volatile @Nullable String sessionId;
  private volatile boolean isLoading;
  private volatile boolean takesTools;
  private volatile long turnStartedAt;
  private int requests;

  AcpSession(String name, List<String> command, AgentLaunch launch, AgentListener listener) throws IOException {
    this.name = name;
    this.launch = launch;
    this.listener = listener;
    this.translator = new AcpTranslator(listener::onFileEdited);
    this.connection = JsonRpcConnection.start(command, launch.workDirectory(), launch.environment(), true, this);
  }

  @Override
  public CompletableFuture<AgentInfo> initialize() {
    JsonObject fs = new JsonObject();
    fs.addProperty("readTextFile", false);
    fs.addProperty("writeTextFile", false);
    JsonObject capabilities = new JsonObject();
    capabilities.add("fs", fs);
    capabilities.addProperty("terminal", false);
    JsonObject params = new JsonObject();
    params.addProperty("protocolVersion", PROTOCOL_VERSION);
    params.add("clientCapabilities", capabilities);
    params.add("clientInfo", JsonRpcConnection.clientInfo());
    return this.connection.request("initialize", params).thenCompose(initialized -> {
      JsonObject agent = initialized.getAsJsonObject();
      return this.openSession(agent).thenApply(session -> this.info(agent, session));
    });
  }

  @Override
  public void prompt(String text, List<ImageAttachment> images, int key) {
    if (this.prompts.offer(text, images, key)) {
      this.startTurn(text, images, key);
    }
  }

  @Override
  public void interrupt() {
    for (String requestId : List.copyOf(this.pendingRequests.keySet())) {
      this.answer(requestId, new PermissionAnswer(false, null, true, Map.of(), null));
    }
    this.connection.notify("session/cancel", Json.of(SESSION_ID, String.valueOf(this.sessionId)));
  }

  @Override
  public void answer(String requestId, PermissionAnswer answer) {
    Pending pending = this.pendingRequests.remove(requestId);
    if (pending == null) {
      return;
    }
    JsonObject outcome = new JsonObject();
    String optionId = answer.interrupt() ? null : optionFor(pending.params(), answer);
    if (optionId == null) {
      outcome.addProperty("outcome", "cancelled");
    } else {
      outcome.addProperty("outcome", "selected");
      outcome.addProperty("optionId", optionId);
    }
    JsonObject result = new JsonObject();
    result.add("outcome", outcome);
    this.connection.respond(pending.id(), result);
    if (answer.interrupt()) {
      this.connection.notify("session/cancel", Json.of(SESSION_ID, String.valueOf(this.sessionId)));
    }
  }

  @Override
  public void setModel(String model) {
    this.setConfig(CATEGORY_MODEL, model);
  }

  @Override
  public void setMode(String mode) {
    JsonObject params = Json.of(SESSION_ID, String.valueOf(this.sessionId));
    params.addProperty("modeId", mode);
    this.connection.request("session/set_mode", params);
  }

  @Override
  public void setEffort(String effort) {
    this.setConfig(CATEGORY_EFFORT, effort);
  }

  @Override
  public @Nullable CompletableFuture<ContextUsage> contextUsage() {
    return null;
  }

  @Override
  public @Nullable EditPreview proposedEdit(String requestId) {
    Pending pending = this.pendingRequests.get(requestId);
    for (JsonObject content : Json.objects(pending == null ? null : pending.toolCall(), "content")) {
      if ("diff".equals(Json.string(content, "type"))) {
        String oldText = Json.string(content, "oldText");
        return new EditPreview(String.valueOf(Json.string(content, "path")), oldText != null ? oldText : "",
          String.valueOf(Json.string(content, "newText")));
      }
    }
    return null;
  }

  @Override
  public boolean takesTools() {
    return this.takesTools;
  }

  @Override
  public boolean isAlive() {
    return this.connection.isAlive();
  }

  @Override
  public void stop() {
    this.connection.destroy();
  }

  @Override
  public void onNotification(String method, JsonObject params) {
    if (!"session/update".equals(method)) {
      return;
    }
    for (AgentEvent event : this.translator.update(Json.object(params, "update") instanceof JsonObject update
      ? update
      : new JsonObject())) {
      this.emit(event);
    }
  }

  @Override
  public void onRequest(JsonElement id, String method, JsonObject params) {
    if (!"session/request_permission".equals(method)) {
      this.connection.respondError(id, JsonRpcConnection.METHOD_NOT_FOUND, "Not supported by Tako: " + method);
      return;
    }
    String requestId = "acp-" + this.requests++;
    JsonObject toolCall = this.toolCall(params);
    this.pendingRequests.put(requestId, new Pending(id, params, toolCall));
    this.listener.onPermission(requestId, prompt(params, toolCall));
  }

  @Override
  public void onExit(int exitCode, String stderrTail) {
    this.listener.onExit(exitCode, stderrTail);
  }

  /** Tako's tools, for agents that take MCP servers over HTTP. */
  private JsonArray mcpServers(@Nullable JsonObject capabilities) {
    JsonArray servers = new JsonArray();
    String toolsUrl = this.launch.toolsUrl();
    if (toolsUrl != null && Json.bool(Json.object(capabilities, "mcpCapabilities"), "http")) {
      JsonObject server = Json.of("type", "http");
      server.addProperty("name", AgentLaunch.TOOLS_SERVER_NAME);
      server.addProperty("url", toolsUrl);
      server.add("headers", new JsonArray());
      servers.add(server);
    }
    this.takesTools = !servers.isEmpty();
    return servers;
  }

  private CompletableFuture<JsonObject> openSession(JsonObject agent) {
    JsonObject capabilities = Json.object(agent, "agentCapabilities");
    String resume = this.launch.resumeSessionId();
    JsonObject params = Json.of("cwd", this.launch.workDirectory().toString());
    params.add(MCP_SERVERS, this.mcpServers(capabilities));
    if (Json.object(Json.object(capabilities, "sessionCapabilities"), "additionalDirectories") != null) {
      JsonArray directories = new JsonArray();
      this.launch.additionalDirectories().forEach(directories::add);
      params.add("additionalDirectories", directories);
    }
    if (resume != null && Json.bool(capabilities, "loadSession")) {
      JsonObject load = params.deepCopy();
      load.addProperty(SESSION_ID, resume);
      this.isLoading = true;
      return this.connection.request("session/load", load)
        .thenApply(loaded -> {
          this.sessionId = resume;
          this.endLoading();
          return loaded.isJsonObject() ? loaded.getAsJsonObject() : new JsonObject();
        })
        .exceptionallyCompose(error -> {
          this.isLoading = false;
          synchronized (this.history) {
            this.history.clear();
          }
          return this.newSession(params, agent);
        });
    }
    return this.newSession(params, agent);
  }

  private CompletableFuture<JsonObject> newSession(JsonObject params, JsonObject agent) {
    return this.connection.request("session/new", params)
      .thenApply(created -> {
        JsonObject session = created.getAsJsonObject();
        this.sessionId = Json.string(session, SESSION_ID);
        return session;
      })
      .exceptionally(error -> {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (!Json.objects(agent, "authMethods").isEmpty()) {
          throw new CompletionException(new AgentException(
            TakoBundle.message("error.acp.sign.in", this.name, cause.getMessage()), cause));
        }
        throw new CompletionException(cause);
      });
  }

  private void endLoading() {
    List<AgentEvent> events;
    synchronized (this.history) {
      this.history.addAll(this.translator.flush());
      events = List.copyOf(this.history);
      this.history.clear();
    }
    this.isLoading = false;
    this.listener.onHistory(events);
  }

  private AgentInfo info(JsonObject agent, JsonObject session) {
    List<AgentInfo.Model> models = new ArrayList<>();
    List<String> efforts = new ArrayList<>();
    String model = null;
    String effort = null;
    for (JsonObject option : Json.objects(session, "configOptions")) {
      String category = Json.string(option, "category");
      String id = String.valueOf(Json.string(option, "id"));
      if (CATEGORY_MODEL.equals(category)) {
        this.configIds.put(CATEGORY_MODEL, id);
        model = Json.string(option, "currentValue");
        for (JsonObject value : selectOptions(option)) {
          String valueId = String.valueOf(Json.string(value, "value"));
          String valueName = Json.string(value, "name");
          models.add(new AgentInfo.Model(valueId, valueName != null ? valueName : valueId,
            Json.string(value, "description"), valueId, efforts));
        }
      } else if (CATEGORY_EFFORT.equals(category)) {
        this.configIds.put(CATEGORY_EFFORT, id);
        effort = Json.string(option, "currentValue");
        for (JsonObject value : selectOptions(option)) {
          efforts.add(String.valueOf(Json.string(value, "value")));
        }
      }
    }
    JsonObject modeState = Json.object(session, "modes");
    List<AgentInfo.Mode> modes = new ArrayList<>();
    for (JsonObject mode : Json.objects(modeState, "availableModes")) {
      String description = Json.string(mode, "description");
      modes.add(new AgentInfo.Mode(String.valueOf(Json.string(mode, "id")), String.valueOf(Json.string(mode, "name")),
        description != null ? description : ""));
    }
    String mode = Json.string(modeState, "currentModeId");
    this.applyLaunchChoices(model, mode, effort);
    if (!this.launch.model().isBlank()) {
      model = this.launch.model();
    }
    if (!this.launch.mode().isBlank() && !modes.isEmpty()) {
      mode = this.launch.mode();
    }
    if (!this.launch.effort().isBlank() && !efforts.isEmpty()) {
      effort = this.launch.effort();
    }
    this.emit(new AgentEvent.SessionStarted(this.sessionId, this.launch.workDirectory().toString(), model, mode,
      null));
    JsonObject capabilities = Json.object(agent, "agentCapabilities");
    JsonObject promptCapabilities = Json.object(capabilities, "promptCapabilities");
    return new AgentInfo(AcpBackend.ID_PREFIX + this.name, this.name, TakoBundle.message("auth.acp.login", this.name),
      new AgentInfo.Capabilities(Json.bool(promptCapabilities, "image"), false, false, false,
        Json.bool(capabilities, "loadSession")),
      models, model, modes, mode, effort, List.of());
  }

  /** Applies the thread's model, mode and effort where the agent's defaults differ. */
  private void applyLaunchChoices(@Nullable String model, @Nullable String mode, @Nullable String effort) {
    if (!this.launch.model().isBlank() && !this.launch.model().equals(model)) {
      this.setModel(this.launch.model());
    }
    if (!this.launch.mode().isBlank() && mode != null && !this.launch.mode().equals(mode)) {
      this.setMode(this.launch.mode());
    }
    if (!this.launch.effort().isBlank() && !this.launch.effort().equals(effort)) {
      this.setEffort(this.launch.effort());
    }
  }

  private void setConfig(String category, String value) {
    String configId = this.configIds.get(category);
    if (configId == null) {
      return;
    }
    JsonObject params = Json.of(SESSION_ID, String.valueOf(this.sessionId));
    params.addProperty("configId", configId);
    params.addProperty("value", value);
    this.connection.request("session/set_config_option", params);
  }

  private void startTurn(String text, List<ImageAttachment> images, int key) {
    this.emit(new AgentEvent.PromptTaken(key, true));
    JsonArray prompt = new JsonArray();
    JsonObject textBlock = Json.of("type", "text");
    textBlock.addProperty("text", text);
    prompt.add(textBlock);
    for (ImageAttachment image : images) {
      JsonObject imageBlock = Json.of("type", "image");
      imageBlock.addProperty("mimeType", image.mediaType());
      imageBlock.addProperty("data", image.data());
      prompt.add(imageBlock);
    }
    JsonObject params = Json.of(SESSION_ID, String.valueOf(this.sessionId));
    params.add("prompt", prompt);
    this.turnStartedAt = System.currentTimeMillis();
    this.emit(new AgentEvent.Busy());
    this.connection.request("session/prompt", params).whenComplete((response, error) -> {
      this.translator.flush().forEach(this::emit);
      String stopReason = response != null && response.isJsonObject()
        ? Json.string(response.getAsJsonObject(), "stopReason")
        : null;
      if ("cancelled".equals(stopReason)) {
        this.emit(new AgentEvent.Interrupted());
      }
      String failure = error != null
        ? TakoBundle.message("error.acp.failed", this.name, AgentException.messageOf(error))
        : "refusal".equals(stopReason)
          ? TakoBundle.message("error.acp.refused", this.name)
          : "max_tokens".equals(stopReason) ? TakoBundle.message("error.token.limit") : null;
      this.emit(new AgentEvent.TurnEnded(System.currentTimeMillis() - this.turnStartedAt, failure, null));
      this.nextPrompt();
    });
  }

  private void nextPrompt() {
    PromptQueue.Queued next = this.prompts.next();
    if (next != null) {
      this.startTurn(next.text(), next.images(), next.key());
    }
  }

  /** The tool call a request is about, as earlier updates described it, with the request's own fields applied. */
  private JsonObject toolCall(JsonObject params) {
    JsonObject toolCall = Json.object(params, "toolCall");
    JsonObject known = this.translator.tool(String.valueOf(Json.string(toolCall, "toolCallId")));
    JsonObject merged = known != null ? known.deepCopy() : new JsonObject();
    AcpTranslator.merge(merged, toolCall);
    return merged;
  }

  private static PermissionPrompt prompt(JsonObject params, JsonObject merged) {
    String toolCallId = String.valueOf(Json.string(merged, "toolCallId"));
    List<PermissionPrompt.Suggestion> suggestions = new ArrayList<>();
    for (JsonObject option : alwaysOptions(params)) {
      suggestions.add(new PermissionPrompt.Suggestion(String.valueOf(Json.string(option, "name")), null));
    }
    String title = Json.string(merged, "title");
    boolean hasDiff = false;
    for (JsonObject content : Json.objects(merged, "content")) {
      hasDiff |= "diff".equals(Json.string(content, "type"));
    }
    return new PermissionPrompt(PermissionPrompt.Kind.TOOL, AcpTranslator.toolName(merged),
      AcpTranslator.kind(Json.string(merged, KIND)), AcpTranslator.input(merged), toolCallId, title, null, hasDiff,
      suggestions, List.of(), null);
  }

  /**
   * The option the answer picks. A suggestion picks by its index among the "always" options, any other answer
   * picks the first option that allows or rejects once. Null cancels.
   */
  private static @Nullable String optionFor(JsonObject params, PermissionAnswer answer) {
    List<JsonObject> always = alwaysOptions(params);
    Integer index = answer.suggestion();
    if (index != null && index >= 0 && index < always.size()) {
      return Json.string(always.get(index), "optionId");
    }
    String wanted = answer.allow() ? "allow_once" : "reject_once";
    String fallback = answer.allow() ? "allow_always" : "reject_always";
    String found = null;
    for (JsonObject option : Json.objects(params, "options")) {
      String kind = Json.string(option, KIND);
      if (wanted.equals(kind)) {
        return Json.string(option, "optionId");
      }
      if (fallback.equals(kind) && found == null) {
        found = Json.string(option, "optionId");
      }
    }
    return found;
  }

  /** The options that remember the answer. The composer offers them as suggestions, in this order. */
  private static List<JsonObject> alwaysOptions(JsonObject params) {
    List<JsonObject> always = new ArrayList<>();
    for (JsonObject option : Json.objects(params, "options")) {
      String kind = Json.string(option, KIND);
      if ("allow_always".equals(kind) || "reject_always".equals(kind)) {
        always.add(option);
      }
    }
    return always;
  }

  private static List<JsonObject> selectOptions(JsonObject option) {
    List<JsonObject> values = new ArrayList<>();
    for (JsonObject entry : Json.objects(option, "options")) {
      List<JsonObject> grouped = Json.objects(entry, "options");
      if (grouped.isEmpty()) {
        values.add(entry);
      } else {
        values.addAll(grouped);
      }
    }
    return values;
  }

  private void emit(AgentEvent event) {
    if (this.isLoading) {
      synchronized (this.history) {
        this.history.add(event);
      }
      return;
    }
    if (event instanceof AgentEvent.Prompt) {
      return;
    }
    this.listener.onEvent(event);
  }

  /** @param toolCall the call as the agent described it so far, including this request */
  private record Pending(
    JsonElement id,
    JsonObject params,
    JsonObject toolCall
  ) {
  }
}
