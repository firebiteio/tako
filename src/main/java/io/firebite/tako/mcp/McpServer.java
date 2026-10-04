package io.firebite.tako.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.firebite.tako.agent.rpc.JsonRpcConnection;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

/**
 * A Model Context Protocol server on the loopback interface. It speaks Streamable HTTP without event streams, so each
 * POSTed request gets one JSON answer. Each published set of tools has its own secret URL, which tells the server who
 * calls and keeps other local processes and web pages out. See
 * https://modelcontextprotocol.io/specification/2025-06-18/basic/transports.
 */
public final class McpServer implements AutoCloseable {
  private static final Logger LOG = Logger.getInstance(McpServer.class);
  private static final String PATH = "/mcp/";
  /** The newest first, which a client asking for another version gets. */
  private static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26",
    "2024-11-05");
  private static final int TOKEN_BYTES = 24;
  private static final int PARSE_ERROR = -32700;

  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, McpTools> published = new ConcurrentHashMap<>();
  /** The secret of each published key. */
  private final Map<String, String> tokens = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  private McpServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  /** Listens on a free loopback port. */
  public static McpServer start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    McpServer mcp = new McpServer(server, executor);
    server.createContext(PATH, mcp::handle);
    server.setExecutor(executor);
    server.start();
    return mcp;
  }

  /**
   * Serves the tools under {@code key} until withdrawn. Returns their URL, whose secret grants access. A key already
   * published keeps its URL and tools.
   */
  public String publish(String key, McpTools tools) {
    String token = this.tokens.computeIfAbsent(key, ignored -> {
      byte[] secret = new byte[TOKEN_BYTES];
      this.random.nextBytes(secret);
      String created = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
      this.published.put(created, tools);
      return created;
    });
    InetSocketAddress address = this.server.getAddress();
    return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + PATH + token;
  }

  public void withdraw(String key) {
    String token = this.tokens.remove(key);
    if (token != null) {
      this.published.remove(token);
    }
  }

  @Override
  public void close() {
    this.server.stop(0);
    this.executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      McpTools tools = this.published.get(exchange.getRequestURI().getPath().substring(PATH.length()));
      if (tools == null) {
        reply(exchange, 404, null);
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        reply(exchange, 405, null);
        return;
      }
      JsonObject message = Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),
        StandardCharsets.UTF_8));
      if (message == null) {
        reply(exchange, 400, error(JsonNull.INSTANCE, PARSE_ERROR, "Expected one JSON-RPC message"));
        return;
      }
      String method = Json.string(message, "method");
      JsonElement id = message.get("id");
      if (method == null || id == null) {
        reply(exchange, 202, null);
        return;
      }
      reply(exchange, 200, answer(tools, id, method, Json.object(message, "params")));
    } finally {
      exchange.close();
    }
  }

  private static JsonObject answer(McpTools tools, JsonElement id, String method, @Nullable JsonObject params) {
    return switch (method) {
      case "initialize" -> result(id, initialized(Json.string(params, "protocolVersion")));
      case "ping" -> result(id, new JsonObject());
      case "tools/list" -> result(id, list(tools));
      case "tools/call" -> call(tools, id, params);
      default -> error(id, JsonRpcConnection.METHOD_NOT_FOUND, "Unknown method " + method);
    };
  }

  private static JsonObject initialized(@Nullable String requestedVersion) {
    JsonObject capabilities = new JsonObject();
    capabilities.add("tools", new JsonObject());
    JsonObject result = new JsonObject();
    result.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requestedVersion)
      ? requestedVersion
      : PROTOCOL_VERSIONS.getFirst());
    result.add("capabilities", capabilities);
    result.add("serverInfo", JsonRpcConnection.clientInfo());
    return result;
  }

  private static JsonObject list(McpTools tools) {
    JsonArray array = new JsonArray();
    for (McpTools.Tool tool : tools.tools()) {
      JsonObject entry = Json.of("name", tool.name());
      entry.addProperty("description", tool.description());
      entry.add("inputSchema", tool.inputSchema());
      array.add(entry);
    }
    JsonObject result = new JsonObject();
    result.add("tools", array);
    return result;
  }

  private static JsonObject call(McpTools tools, JsonElement id, @Nullable JsonObject params) {
    String name = String.valueOf(Json.string(params, "name"));
    JsonObject arguments = Json.object(params, "arguments");
    try {
      return result(id, content(tools.call(name, arguments != null ? arguments : new JsonObject()), false));
    } catch (McpToolException e) {
      return result(id, content(e.getMessage(), true));
    } catch (RuntimeException e) {
      LOG.warn("Tool " + name + " failed", e);
      return result(id, content("Tako failed to run " + name + ": " + e, true));
    }
  }

  private static JsonObject content(String text, boolean isError) {
    JsonObject block = Json.of("type", "text");
    block.addProperty("text", text);
    JsonArray blocks = new JsonArray();
    blocks.add(block);
    JsonObject result = new JsonObject();
    result.add("content", blocks);
    result.addProperty("isError", isError);
    return result;
  }

  private static JsonObject result(JsonElement id, JsonObject result) {
    return response(id, "result", result);
  }

  private static JsonObject error(JsonElement id, int code, String message) {
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);
    return response(id, "error", error);
  }

  private static JsonObject response(JsonElement id, String key, JsonObject value) {
    JsonObject response = Json.of("jsonrpc", "2.0");
    response.add("id", id);
    response.add(key, value);
    return response;
  }

  private static void reply(HttpExchange exchange, int status, @Nullable JsonObject body) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
