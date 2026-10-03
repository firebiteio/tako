package io.firebite.tako.agent.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
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
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.agent.rpc.JsonRpcConnection;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/** One {@code codex app-server} serving one thread. */
final class CodexSession implements AgentSession, JsonRpcConnection.Handler {
  private static final Logger LOG = Logger.getInstance(CodexSession.class);
  private static final String THREAD_ID = "threadId";

  private final JsonRpcConnection connection;
  private final AgentLaunch launch;
  private final AgentListener listener;
  private final Map<String, Pending> pendingRequests = new ConcurrentHashMap<>();
  private final Map<String, JsonObject> items = new ConcurrentHashMap<>();
  private final PromptQueue prompts = new PromptQueue();
  private volatile @Nullable String threadId;
  private volatile @Nullable String turnId;
  private volatile String model;
  private volatile String mode;
  private volatile String effort;
  private int planUpdates;

  CodexSession(List<String> command, AgentLaunch launch, AgentListener listener) throws IOException {
    this.launch = launch;
    this.listener = listener;
    this.model = launch.model();
    this.mode = CodexModes.find(launch.mode()).value();
    this.effort = launch.effort();
    this.connection = JsonRpcConnection.start(command, launch.workDirectory(), launch.environment(), false, this);
  }

  @Override
  public CompletableFuture<AgentInfo> initialize() {
    JsonObject capabilities = new JsonObject();
    capabilities.addProperty("experimentalApi", false);
    capabilities.addProperty("requestAttestation", false);
    JsonObject params = new JsonObject();
    params.add("clientInfo", JsonRpcConnection.clientInfo());
    params.add("capabilities", capabilities);
    return this.connection.request("initialize", params)
      .thenCompose(initialized -> {
        this.connection.notify("initialized", null);
        return this.connection.request("account/read", new JsonObject());
      })
      .thenCompose(account -> {
        JsonObject result = account.isJsonObject() ? account.getAsJsonObject() : new JsonObject();
        if (Json.object(result, "account") == null && Json.bool(result, "requiresOpenaiAuth")) {
          throw new CompletionException(new AgentException(TakoBundle.message("error.codex.not.signed.in")));
        }
        String auth = authDescription(Json.object(result, "account"));
        return this.connection.request("model/list", new JsonObject())
          .thenCompose(models -> this.openThread()
            .thenApply(thread -> this.info(auth, models, thread)));
      });
  }

  @Override
  public void prompt(String text, List<ImageAttachment> images) {
    if (this.prompts.offer(text, images)) {
      this.startTurn(text, images);
    }
  }

  @Override
  public void interrupt() {
    String turn = this.turnId;
    this.prompts.clear();
    if (this.threadId != null && turn != null) {
      JsonObject params = Json.of(THREAD_ID, this.threadId);
      params.addProperty("turnId", turn);
      this.connection.request("turn/interrupt", params);
    }
  }

  @Override
  public void answer(String requestId, PermissionAnswer answer) {
    Pending pending = this.pendingRequests.remove(requestId);
    if (pending == null) {
      return;
    }
    JsonObject result = new JsonObject();
    if ("item/tool/requestUserInput".equals(pending.method())) {
      JsonObject answers = new JsonObject();
      if (answer.allow()) {
        for (Map.Entry<String, String> entry : answer.answers().entrySet()) {
          String questionId = pending.questionIds().get(entry.getKey());
          if (questionId != null) {
            JsonArray values = new JsonArray();
            values.add(entry.getValue());
            JsonObject value = new JsonObject();
            value.add("answers", values);
            answers.add(questionId, value);
          }
        }
      }
      result.add("answers", answers);
    } else {
      String decision = !answer.allow()
        ? answer.interrupt() ? "cancel" : "decline"
        : answer.suggestion() != null ? "acceptForSession" : "accept";
      result.addProperty("decision", decision);
    }
    this.connection.respond(pending.id(), result);
  }

  @Override
  public void setModel(String model) {
    this.model = model;
  }

  @Override
  public void setMode(String mode) {
    this.mode = CodexModes.find(mode).value();
  }

  @Override
  public void setEffort(String effort) {
    this.effort = effort;
  }

  @Override
  public @Nullable CompletableFuture<ContextUsage> contextUsage() {
    return null;
  }

  @Override
  public @Nullable EditPreview proposedEdit(String requestId) {
    return null;
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
    switch (method) {
      case "turn/started" -> {
        this.turnId = Json.string(Json.object(params, "turn"), "id");
        this.emit(new AgentEvent.Busy());
      }
      case "item/started" -> {
        JsonObject item = Json.object(params, "item");
        if (item != null) {
          this.items.put(String.valueOf(Json.string(item, "id")), item);
          this.recordOriginals(item);
          CodexTranslator.started(item).forEach(this::emit);
        }
      }
      case "item/agentMessage/delta" -> this.emit(new AgentEvent.TextDelta(String.valueOf(Json.string(params,
        "itemId")), String.valueOf(Json.string(params, "delta"))));
      case "item/completed" -> {
        JsonObject item = Json.object(params, "item");
        if (item != null) {
          this.items.remove(String.valueOf(Json.string(item, "id")));
          CodexTranslator.completed(item).forEach(this::emit);
        }
      }
      case "turn/completed" -> this.onTurnCompleted(Json.object(params, "turn"));
      case "turn/plan/updated" -> CodexTranslator.plan("plan-" + Json.string(params, "turnId") + "-"
        + this.planUpdates++, params).forEach(this::emit);
      case "thread/tokenUsage/updated" -> this.emit(CodexTranslator.usage(params));
      case "thread/compacted" -> this.emit(new AgentEvent.Compacted());
      case "account/rateLimits/updated" -> this.emit(new AgentEvent.RateLimits(
        CodexTranslator.rateLimits(Json.object(params, "rateLimits"))));
      case "error" -> {
        if (Json.bool(params, "willRetry")) {
          this.emit(new AgentEvent.Retrying(null, Json.string(Json.object(params, "error"), "message")));
        }
      }
      default -> {
      }
    }
  }

  @Override
  public void onRequest(JsonElement id, String method, JsonObject params) {
    String requestId = "codex-" + id;
    Map<String, String> questionIds = new HashMap<>();
    PermissionPrompt prompt = switch (method) {
      case "item/commandExecution/requestApproval" -> this.commandPrompt(params);
      case "item/fileChange/requestApproval" -> this.filePrompt(params);
      case "item/tool/requestUserInput" -> questionPrompt(params, questionIds);
      default -> null;
    };
    if (prompt == null) {
      this.connection.respondError(id, JsonRpcConnection.METHOD_NOT_FOUND, "Not supported by Tako: " + method);
      return;
    }
    this.pendingRequests.put(requestId, new Pending(id, method, questionIds));
    this.listener.onPermission(requestId, prompt);
  }

  @Override
  public void onExit(int exitCode, String stderrTail) {
    this.listener.onExit(exitCode, stderrTail);
  }

  private CompletableFuture<JsonObject> openThread() {
    String resume = this.launch.resumeSessionId();
    if (resume == null) {
      return this.startThread();
    }
    JsonObject params = this.threadParams();
    params.addProperty(THREAD_ID, resume);
    return this.connection.request("thread/resume", params)
      .thenApply(JsonElement::getAsJsonObject)
      .thenApply(response -> {
        this.listener.onHistory(CodexTranslator.history(Json.object(response, "thread")));
        return response;
      })
      .exceptionallyCompose(error -> {
        LOG.info("Codex thread " + resume + " could not be resumed, starting a new one", error);
        return this.startThread();
      });
  }

  private CompletableFuture<JsonObject> startThread() {
    return this.connection.request("thread/start", this.threadParams()).thenApply(JsonElement::getAsJsonObject);
  }

  private JsonObject threadParams() {
    JsonObject params = Json.of("cwd", this.launch.workDirectory().toString());
    if (!this.model.isBlank()) {
      params.addProperty("model", this.model);
    }
    CodexModes.Mode mode = CodexModes.find(this.mode);
    params.addProperty("approvalPolicy", mode.approvalPolicy());
    params.addProperty("sandbox", mode.sandbox());
    return params;
  }

  private AgentInfo info(String auth, JsonElement modelList, JsonObject started) {
    JsonObject thread = Json.object(started, "thread");
    this.threadId = Json.string(thread, "id");
    String resolvedModel = Json.string(started, "model");
    String startedEffort = Json.string(started, "reasoningEffort");
    if (this.effort.isBlank() && startedEffort != null) {
      this.effort = startedEffort;
    }
    this.emit(new AgentEvent.SessionStarted(this.threadId, Json.string(started, "cwd"), resolvedModel, this.mode));
    List<AgentInfo.Model> models = new ArrayList<>();
    JsonObject list = modelList.isJsonObject() ? modelList.getAsJsonObject() : new JsonObject();
    for (JsonObject model : Json.objects(list, "data")) {
      if (Json.bool(model, "hidden")) {
        continue;
      }
      List<String> efforts = new ArrayList<>();
      for (JsonObject option : Json.objects(model, "supportedReasoningEfforts")) {
        efforts.add(String.valueOf(Json.string(option, "reasoningEffort")));
      }
      String value = String.valueOf(Json.string(model, "model"));
      String name = Json.string(model, "displayName");
      models.add(new AgentInfo.Model(value, name != null ? name : value, Json.string(model, "description"), value,
        efforts));
    }
    return new AgentInfo(CodexBackend.ID, CodexBackend.NAME, auth, CodexBackend.CAPABILITIES, models,
      this.model.isBlank() ? resolvedModel : this.model, CodexModes.options(), this.mode,
      this.effort.isBlank() ? null : this.effort, List.of());
  }

  private void startTurn(String text, List<ImageAttachment> images) {
    JsonArray input = new JsonArray();
    JsonObject textInput = Json.of("type", "text");
    textInput.addProperty("text", text);
    textInput.add("text_elements", new JsonArray());
    input.add(textInput);
    for (ImageAttachment image : images) {
      JsonObject imageInput = Json.of("type", "image");
      imageInput.addProperty("url", "data:" + image.mediaType() + ";base64," + image.data());
      input.add(imageInput);
    }
    JsonObject params = Json.of(THREAD_ID, String.valueOf(this.threadId));
    params.add("input", input);
    if (!this.model.isBlank()) {
      params.addProperty("model", this.model);
    }
    if (!this.effort.isBlank()) {
      params.addProperty("effort", this.effort);
    }
    CodexModes.Mode mode = CodexModes.find(this.mode);
    params.addProperty("approvalPolicy", mode.approvalPolicy());
    params.add("sandboxPolicy", mode.sandboxPolicy());
    this.connection.request("turn/start", params).whenComplete((response, error) -> {
      if (error != null) {
        this.emit(
          new AgentEvent.TurnEnded(null,
            TakoBundle.message("error.codex.turn.not.started", AgentException.messageOf(error)),
            null));
        this.nextPrompt();
      }
    });
  }

  private void onTurnCompleted(@Nullable JsonObject turn) {
    if (turn == null) {
      return;
    }
    if ("interrupted".equals(Json.string(turn, "status"))) {
      this.emit(new AgentEvent.Interrupted());
    }
    this.emit(CodexTranslator.turnEnded(turn));
    this.nextPrompt();
  }

  private void nextPrompt() {
    this.turnId = null;
    PromptQueue.Queued next = this.prompts.next();
    if (next != null) {
      this.startTurn(next.text(), next.images());
    }
  }

  /** Keeps the files a change is about to touch as they are, for the turn's diff. */
  private void recordOriginals(JsonObject item) {
    if (!"fileChange".equals(Json.string(item, "type"))) {
      return;
    }
    for (JsonObject change : Json.objects(item, "changes")) {
      String path = Json.string(change, "path");
      if (path != null) {
        boolean isNew = "add".equals(Json.string(Json.object(change, "kind"), "type"));
        String content = isNew ? "" : this.launch.readFile(path);
        if (content != null) {
          this.listener.onFileEdited(path, content);
        }
      }
    }
  }

  private PermissionPrompt commandPrompt(JsonObject params) {
    JsonObject input = Json.of("command", String.valueOf(Json.string(params, "command")));
    input.addProperty("cwd", Json.string(params, "cwd"));
    return new PermissionPrompt(PermissionPrompt.Kind.TOOL, CodexTranslator.SHELL, ToolKind.COMMAND, input,
      Json.string(params, "itemId"), null, Json.string(params, "reason"), false,
      List.of(new PermissionPrompt.Suggestion(TakoBundle.message("permission.approve.session"), null)), List.of(),
      null);
  }

  private PermissionPrompt filePrompt(JsonObject params) {
    JsonObject item = this.items.get(String.valueOf(Json.string(params, "itemId")));
    List<JsonObject> changes = Json.objects(item, "changes");
    JsonObject input = Json.of("file_path", changes.isEmpty()
      ? ""
      : String.valueOf(Json.string(changes.getFirst(),
        "path")));
    return new PermissionPrompt(PermissionPrompt.Kind.TOOL, "Edit", ToolKind.EDIT, input,
      Json.string(params, "itemId"),
      changes.size() > 1 ? TakoBundle.message("permission.codex.changes", changes.size()) : null,
      Json.string(params, "reason"), false,
      List.of(new PermissionPrompt.Suggestion(TakoBundle.message("permission.approve.session"), null)), List.of(),
      null);
  }

  private static PermissionPrompt questionPrompt(JsonObject params, Map<String, String> questionIds) {
    List<PermissionPrompt.Question> questions = new ArrayList<>();
    for (JsonObject question : Json.objects(params, "questions")) {
      String text = String.valueOf(Json.string(question, "question"));
      questionIds.put(text, String.valueOf(Json.string(question, "id")));
      List<PermissionPrompt.Option> options = new ArrayList<>();
      for (JsonObject option : Json.objects(question, "options")) {
        options.add(new PermissionPrompt.Option(String.valueOf(Json.string(option, "label")),
          Json.string(option, "description")));
      }
      questions.add(new PermissionPrompt.Question(text, Json.string(question, "header"), false, options));
    }
    return new PermissionPrompt(PermissionPrompt.Kind.QUESTION, "request_user_input", ToolKind.QUESTION,
      new JsonObject(), Json.string(params, "itemId"), null, null, false, List.of(), questions, null);
  }

  private static String authDescription(@Nullable JsonObject account) {
    String type = Json.string(account, "type");
    if ("chatgpt".equals(type)) {
      String plan = Json.string(account, "planType");
      return plan != null
        ? TakoBundle.message("auth.codex.chatgpt.plan", plan)
        : TakoBundle.message("auth.codex.chatgpt");
    }
    return "apiKey".equals(type) ? TakoBundle.message("auth.codex.api.key") : TakoBundle.message("auth.codex.login");
  }

  private void emit(AgentEvent event) {
    this.listener.onEvent(event);
  }

  /** @param questionIds Codex's question ids by question text */
  private record Pending(
    JsonElement id,
    String method,
    Map<String, String> questionIds
  ) {
  }
}
