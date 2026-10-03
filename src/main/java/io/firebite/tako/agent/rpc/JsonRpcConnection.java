package io.firebite.tako.agent.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.Processes;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/** JSON-RPC over a process's stdin/stdout, one message per line, as Codex's app-server and ACP agents speak it. */
public final class JsonRpcConnection implements AutoCloseable {
  private static final Logger LOG = Logger.getInstance(JsonRpcConnection.class);
  private static final int STDERR_TAIL_LIMIT = 8192;
  /** No handler for the method. */
  public static final int METHOD_NOT_FOUND = -32601;

  private final Process process;
  private final Writer stdin;
  private final boolean tagsVersion;
  private final Handler handler;
  private final Map<Long, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private final StringBuilder stderrTail = new StringBuilder();

  private JsonRpcConnection(Process process, boolean tagsVersion, Handler handler) {
    this.process = process;
    this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    this.tagsVersion = tagsVersion;
    this.handler = handler;
  }

  /** @param tagsVersion add {@code "jsonrpc": "2.0"} to every message, which Codex leaves out */
  public static JsonRpcConnection start(List<String> command, Path workDirectory, Map<String, String> environment,
    boolean tagsVersion, Handler handler) throws IOException {
    JsonRpcConnection connection = new JsonRpcConnection(Processes.start(command, workDirectory, environment),
      tagsVersion, handler);
    connection.startReaders();
    return connection;
  }

  /** Who connects, as the {@code clientInfo} of {@code initialize}. */
  public static JsonObject clientInfo() {
    JsonObject clientInfo = Json.of("name", "tako");
    clientInfo.addProperty("title", "Tako");
    clientInfo.addProperty("version", "0.1.0");
    return clientInfo;
  }

  /** Waits for the result, for short-lived connections. */
  public JsonElement await(String method, @Nullable JsonObject params, long timeoutSeconds) throws Exception {
    return this.request(method, params).get(timeoutSeconds, TimeUnit.SECONDS);
  }

  /** Completes with the result, or with an {@link RpcException}. */
  public CompletableFuture<JsonElement> request(String method, @Nullable JsonObject params) {
    long id = this.ids.incrementAndGet();
    CompletableFuture<JsonElement> future = new CompletableFuture<>();
    this.pending.put(id, future);
    JsonObject message = this.message();
    message.addProperty("id", id);
    message.addProperty("method", method);
    if (params != null) {
      message.add("params", params);
    }
    if (!this.write(message)) {
      this.pending.remove(id);
      future.completeExceptionally(new IOException(TakoBundle.message("error.agent.not.running", method)));
    }
    return future;
  }

  public void notify(String method, @Nullable JsonObject params) {
    JsonObject message = this.message();
    message.addProperty("method", method);
    if (params != null) {
      message.add("params", params);
    }
    this.write(message);
  }

  public void respond(JsonElement id, JsonElement result) {
    JsonObject message = this.message();
    message.add("id", id);
    message.add("result", result);
    this.write(message);
  }

  public void respondError(JsonElement id, int code, String text) {
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", text);
    JsonObject message = this.message();
    message.add("id", id);
    message.add("error", error);
    this.write(message);
  }

  public boolean isAlive() {
    return this.process.isAlive();
  }

  @Override
  public void close() {
    this.destroy();
  }

  public void destroy() {
    try {
      this.stdin.close();
    } catch (IOException ignored) {
    }
    Processes.destroyTree(this.process);
  }

  private JsonObject message() {
    return this.tagsVersion ? Json.of("jsonrpc", "2.0") : new JsonObject();
  }

  private boolean write(JsonObject message) {
    synchronized (this.stdin) {
      try {
        this.stdin.write(message.toString());
        this.stdin.write('\n');
        this.stdin.flush();
        return true;
      } catch (IOException e) {
        LOG.debug("Agent stdin closed", e);
        return false;
      }
    }
  }

  private void startReaders() {
    Thread stdout = new Thread(this::readStdout, "agent-stdout");
    stdout.setDaemon(true);
    stdout.start();
    Thread stderr = new Thread(this::readStderr, "agent-stderr");
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
      LOG.debug("Agent stdout closed", e);
    }
    int exitCode = this.waitForExit();
    for (CompletableFuture<JsonElement> future : this.pending.values()) {
      future.completeExceptionally(new IOException(TakoBundle.message("error.agent.exited", String.valueOf(exitCode))));
    }
    this.pending.clear();
    String stderr;
    synchronized (this.stderrTail) {
      stderr = this.stderrTail.toString();
    }
    this.handler.onExit(exitCode, stderr);
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
      LOG.debug("Agent stderr closed", e);
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
    JsonObject message = Json.parseObject(line);
    if (message == null) {
      if (!line.isBlank()) {
        LOG.debug("Not JSON from the agent: " + line);
      }
      return;
    }
    String method = Json.string(message, "method");
    JsonElement id = message.get("id");
    JsonObject params = Json.object(message, "params");
    if (params == null) {
      params = new JsonObject();
    }
    if (method != null && id != null && !id.isJsonNull()) {
      this.handler.onRequest(id, method, params);
    } else if (method != null) {
      this.handler.onNotification(method, params);
    } else if (id != null && id.isJsonPrimitive()) {
      this.complete(id.getAsLong(), message);
    }
  }

  private void complete(long id, JsonObject message) {
    CompletableFuture<JsonElement> future = this.pending.remove(id);
    if (future == null) {
      return;
    }
    JsonObject error = Json.object(message, "error");
    if (error != null) {
      future.completeExceptionally(new RpcException((int) Json.number(error, "code"),
        String.valueOf(Json.string(error, "message"))));
      return;
    }
    JsonElement result = message.get("result");
    future.complete(result != null ? result : JsonNull.INSTANCE);
  }

  /** Called on the connection's reader thread. */
  public interface Handler {
    /** Ignores everything, for connections that only ask. */
    Handler NONE = new Handler() {
      @Override
      public void onNotification(String method, JsonObject params) {
      }

      @Override
      public void onRequest(JsonElement id, String method, JsonObject params) {
      }

      @Override
      public void onExit(int exitCode, String stderrTail) {
      }
    };

    void onNotification(String method, JsonObject params);

    /** Answer with {@link #respond} or {@link #respondError}. */
    void onRequest(JsonElement id, String method, JsonObject params);

    void onExit(int exitCode, String stderrTail);
  }

  /** The other side answered a request with an error. */
  public static final class RpcException extends RuntimeException {
    private final int code;

    RpcException(int code, String message) {
      super(message);
      this.code = code;
    }

    public int code() {
      return this.code;
    }
  }
}
