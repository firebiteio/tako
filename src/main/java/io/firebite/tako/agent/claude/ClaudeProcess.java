package io.firebite.tako.agent.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.Processes;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.json.Json;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/** One running {@code claude -p} speaking stream-json over stdin/stdout. */
public final class ClaudeProcess {
  private static final Logger LOG = Logger.getInstance(ClaudeProcess.class);
  private static final int STDERR_TAIL_LIMIT = 8192;
  private static final String CAN_USE_TOOL = "can_use_tool";
  private static final String SUCCESS = "success";
  private static final String ERROR = "error";

  private final Process process;
  private final Writer stdin;
  private final ClaudeProcessListener listener;
  private final Map<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
  private final AtomicLong requestCounter = new AtomicLong();
  private final StringBuilder stderrTail = new StringBuilder();

  private ClaudeProcess(Process process, ClaudeProcessListener listener) {
    this.process = process;
    this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    this.listener = listener;
  }

  public static ClaudeProcess start(ClaudeLaunch launch, Path workDirectory, ClaudeProcessListener listener)
    throws IOException {
    ClaudeProcess claude = new ClaudeProcess(Processes.start(launch.command(), workDirectory, launch.environment()),
      listener);
    claude.startReaders();
    return claude;
  }

  public CompletableFuture<JsonObject> initialize() {
    return this.request(controlRequest("initialize"));
  }

  public CompletableFuture<JsonObject> interrupt() {
    return this.request(controlRequest("interrupt"));
  }

  public CompletableFuture<JsonObject> setModel(String model) {
    JsonObject request = controlRequest("set_model");
    request.addProperty("model", model);
    return this.request(request);
  }

  public CompletableFuture<JsonObject> setPermissionMode(String mode) {
    JsonObject request = controlRequest("set_permission_mode");
    request.addProperty("mode", mode);
    return this.request(request);
  }

  public CompletableFuture<JsonObject> setEffort(String effort) {
    JsonObject request = controlRequest("apply_flag_settings");
    request.add("settings", Json.of("effortLevel", effort));
    return this.request(request);
  }

  /** The {@code /context} breakdown into categories, memory files, MCP tools and message tokens. */
  public CompletableFuture<JsonObject> contextUsage() {
    return this.request(controlRequest("get_context_usage"));
  }

  public CompletableFuture<JsonObject> settings() {
    return this.request(controlRequest("get_settings"));
  }

  public void sendUserText(String text) {
    this.sendUser(text, List.of());
  }

  /** A prompt with images, the images first as the Messages API recommends. Leaves out blank text. */
  public void sendUser(String text, List<ImageAttachment> images) {
    JsonArray content = new JsonArray();
    for (ImageAttachment image : images) {
      content.add(image.toContentBlock());
    }
    if (!text.isBlank() || images.isEmpty()) {
      JsonObject block = Json.of("type", "text");
      block.addProperty("text", text);
      content.add(block);
    }
    JsonObject message = Json.of("role", "user");
    message.add("content", content);
    this.write(CliMessage.of(CliMessage.Type.USER, message).json());
  }

  public CompletableFuture<JsonObject> request(JsonObject request) {
    String requestId = "req_" + this.requestCounter.incrementAndGet();
    CompletableFuture<JsonObject> future = new CompletableFuture<>();
    this.pendingRequests.put(requestId, future);
    JsonObject line = Json.of("type", CliMessage.Type.CONTROL_REQUEST.wireName());
    line.addProperty("request_id", requestId);
    line.add("request", request);
    if (!this.write(line)) {
      this.pendingRequests.remove(requestId);
      future.completeExceptionally(new IOException(TakoBundle.message("error.claude.not.running")));
    }
    return future;
  }

  public void respond(String requestId, JsonObject response) {
    JsonObject body = Json.of("subtype", SUCCESS);
    body.addProperty("request_id", requestId);
    body.add("response", response);
    this.writeControlResponse(body);
  }

  public void respondError(String requestId, String error) {
    JsonObject body = Json.of("subtype", ERROR);
    body.addProperty("request_id", requestId);
    body.addProperty("error", error);
    this.writeControlResponse(body);
  }

  public boolean isAlive() {
    return this.process.isAlive();
  }

  public void destroy() {
    try {
      this.stdin.close();
    } catch (IOException ignored) {
    }
    Processes.destroyTree(this.process);
  }

  private static JsonObject controlRequest(String subtype) {
    return Json.of("subtype", subtype);
  }

  private void writeControlResponse(JsonObject body) {
    JsonObject line = Json.of("type", CliMessage.Type.CONTROL_RESPONSE.wireName());
    line.add("response", body);
    this.write(line);
  }

  private boolean write(JsonObject line) {
    synchronized (this.stdin) {
      try {
        this.stdin.write(line.toString());
        this.stdin.write('\n');
        this.stdin.flush();
        return true;
      } catch (IOException e) {
        LOG.debug("claude stdin closed", e);
        return false;
      }
    }
  }

  private void startReaders() {
    Thread stdout = new Thread(this::readStdout, "claude-stdout");
    stdout.setDaemon(true);
    stdout.start();
    Thread stderr = new Thread(this::readStderr, "claude-stderr");
    stderr.setDaemon(true);
    stderr.start();
  }

  private void readStdout() {
    try (BufferedReader reader = new BufferedReader(
      new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        this.dispatch(line);
      }
    } catch (IOException e) {
      LOG.debug("claude stdout closed", e);
    }
    int exitCode = this.waitForExit();
    for (CompletableFuture<JsonObject> future : this.pendingRequests.values()) {
      future
        .completeExceptionally(new IOException(TakoBundle.message("error.claude.exited", String.valueOf(exitCode))));
    }
    this.pendingRequests.clear();
    String stderr;
    synchronized (this.stderrTail) {
      stderr = this.stderrTail.toString();
    }
    this.listener.onExit(exitCode, stderr);
  }

  private void readStderr() {
    try (BufferedReader reader = new BufferedReader(
      new InputStreamReader(this.process.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        synchronized (this.stderrTail) {
          this.stderrTail.append(line).append('\n');
          int overflow = this.stderrTail.length() - STDERR_TAIL_LIMIT;
          if (overflow > 0) {
            this.stderrTail.delete(0, overflow);
          }
        }
      }
    } catch (IOException e) {
      LOG.debug("claude stderr closed", e);
    }
  }

  private int waitForExit() {
    try {
      return this.process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private void dispatch(String line) {
    if (line.isBlank()) {
      return;
    }
    JsonObject json = Json.parseObject(line);
    if (json == null) {
      LOG.warn("Unparseable claude output: " + line);
      return;
    }
    CliMessage message = new CliMessage(json);
    switch (message.type()) {
      case CONTROL_RESPONSE -> this.completeRequest(Json.object(json, "response"));
      case CONTROL_REQUEST -> this.onControlRequest(Json.string(json, "request_id"), Json.object(json, "request"));
      default -> this.listener.onMessage(message);
    }
  }

  private void onControlRequest(@Nullable String requestId, @Nullable JsonObject request) {
    if (requestId == null || request == null) {
      return;
    }
    if (CAN_USE_TOOL.equals(Json.string(request, "subtype"))) {
      this.listener.onPermissionRequest(requestId, request);
    } else {
      this.respondError(requestId, "Not supported by Tako");
    }
  }

  private void completeRequest(@Nullable JsonObject response) {
    String requestId = Json.string(response, "request_id");
    CompletableFuture<JsonObject> future = requestId == null ? null : this.pendingRequests.remove(requestId);
    if (future == null) {
      return;
    }
    if (ERROR.equals(Json.string(response, "subtype"))) {
      future.completeExceptionally(new IllegalStateException(Json.string(response, "error")));
      return;
    }
    JsonObject body = Json.object(response, "response");
    future.complete(body == null ? new JsonObject() : body);
  }
}
