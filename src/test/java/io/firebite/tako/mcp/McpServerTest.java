package io.firebite.tako.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.firebite.tako.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class McpServerTest {
  private final HttpClient client = HttpClient.newHttpClient();
  private McpServer server;
  private String url;

  @Before
  public void setUp() throws Exception {
    this.server = McpServer.start();
    this.url = this.server.publish("thread", new McpTools() {
      @Override
      public List<Tool> tools() {
        return List.of(new Tool("echo", "Says the text back.", Json.of("type", "object")));
      }

      @Override
      public String call(String name, JsonObject arguments) throws McpToolException {
        if (!"echo".equals(name)) {
          throw new McpToolException("Unknown tool " + name);
        }
        String text = Json.string(arguments, "text");
        if (text == null) {
          throw new McpToolException("Missing text");
        }
        return text;
      }
    });
  }

  @After
  public void tearDown() {
    this.server.close();
  }

  @Test
  public void initializeAgreesOnTheClientsVersionAndOffersTools() throws Exception {
    JsonObject result = this.request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}");

    assertEquals("2025-06-18", Json.string(result, "protocolVersion"));
    assertTrue(Json.object(result, "capabilities").has("tools"));
    assertEquals("tako", Json.string(Json.object(result, "serverInfo"), "name"));
  }

  @Test
  public void initializeOffersTheNewestVersionForAnUnknownOne() throws Exception {
    JsonObject result = this.request("initialize", "{\"protocolVersion\":\"1999-01-01\"}");

    assertEquals("2025-11-25", Json.string(result, "protocolVersion"));
  }

  @Test
  public void listsAndCallsTools() throws Exception {
    JsonObject listed = this.request("tools/list", "{}");
    assertEquals("echo", Json.string(Json.objects(listed, "tools").getFirst(), "name"));

    JsonObject called = this.request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}");
    assertFalse(Json.bool(called, "isError"));
    assertEquals("hi", Json.string(Json.objects(called, "content").getFirst(), "text"));
  }

  @Test
  public void aFailedCallIsAnErrorResult() throws Exception {
    JsonObject called = this.request("tools/call", "{\"name\":\"echo\",\"arguments\":{}}");

    assertTrue(Json.bool(called, "isError"));
    assertEquals("Missing text", Json.string(Json.objects(called, "content").getFirst(), "text"));
  }

  @Test
  public void aKeyKeepsItsUrl() {
    assertEquals(this.url, this.server.publish("thread", null));
  }

  @Test
  public void notificationsAreAccepted() throws Exception {
    assertEquals(202, this.send(this.url, "POST", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
  }

  @Test
  public void onlyPublishedUrlsAnswer() throws Exception {
    String withdrawn = this.url;
    this.server.withdraw("thread");

    assertEquals(404, this.send(withdrawn, "POST", message("ping", "{}")));
  }

  @Test
  public void eventStreamsAreNotOffered() throws Exception {
    assertEquals(405, this.send(this.url, "GET", null));
  }

  private JsonObject request(String method, String params) throws Exception {
    Response response = this.post(this.url, message(method, params));
    assertEquals(200, response.status());
    assertEquals(1, Json.number(response.body(), "id"));
    return Json.object(response.body(), "result");
  }

  private Response post(String target, String body) throws Exception {
    HttpResponse<String> response = this.client.send(HttpRequest.newBuilder(URI.create(target))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build(), HttpResponse.BodyHandlers.ofString());
    return new Response(response.statusCode(), JsonParser.parseString(response.body()).getAsJsonObject());
  }

  private int send(String target, String method, String body) throws Exception {
    HttpRequest.BodyPublisher publisher = body == null
      ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofString(body);
    return this.client.send(HttpRequest.newBuilder(URI.create(target)).method(method, publisher).build(),
      HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static String message(String method, String params) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
  }

  private record Response(
    int status,
    JsonObject body
  ) {
  }
}
