package io.firebite.tako.agent.codex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for {@code codex app-server}. It answers the handshake, starts or resumes its one thread, then plays one
 * turn that streams a message and asks to run a command, echoing the decision it got.
 */
public final class FakeCodex {
  private static final String THREAD = "019a0000-0000-7000-8000-000000000001";

  private FakeCodex() {
  }

  public static void main(String[] args) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      JsonObject message = JsonParser.parseString(line).getAsJsonObject();
      String method = message.has("method") ? message.get("method").getAsString() : "";
      String id = message.has("id") ? message.get("id").toString() : null;
      switch (method) {
        case "initialize" -> respond(id, "{\"userAgent\":\"fake\",\"codexHome\":\"/tmp\",\"platformFamily\":\"unix\","
          + "\"platformOs\":\"linux\"}");
        case "account/read" -> respond(id, "{\"account\":{\"type\":\"chatgpt\",\"email\":null,\"planType\":\"plus\"},"
          + "\"requiresOpenaiAuth\":true}");
        case "model/list" -> respond(id, "{\"data\":[{\"id\":\"gpt-x\",\"model\":\"gpt-x\",\"displayName\":\"GPT X\","
          + "\"description\":\"Fast\",\"hidden\":false,\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"low\"},"
          + "{\"reasoningEffort\":\"high\"}]}],\"nextCursor\":null}");
        case "thread/start", "thread/resume" -> respond(id, "{\"thread\":{\"id\":\"" + THREAD + "\",\"turns\":[]},"
          + "\"model\":\"gpt-x\",\"cwd\":\"/work\",\"reasoningEffort\":\"low\"}");
        case "turn/start" -> turn(id, reader);
        default -> {
        }
      }
    }
  }

  private static void turn(String id, BufferedReader reader) throws IOException {
    respond(id, "{\"turn\":{\"id\":\"turn1\",\"items\":[],\"status\":\"inProgress\"}}");
    notify("turn/started", "{\"threadId\":\"" + THREAD + "\",\"turn\":{\"id\":\"turn1\",\"status\":\"inProgress\"}}");
    notify("item/started", "{\"item\":{\"type\":\"agentMessage\",\"id\":\"m1\",\"text\":\"\"}}");
    notify("item/agentMessage/delta", "{\"itemId\":\"m1\",\"delta\":\"Hel\"}");
    notify("item/agentMessage/delta", "{\"itemId\":\"m1\",\"delta\":\"lo\"}");
    notify("item/completed", "{\"item\":{\"type\":\"agentMessage\",\"id\":\"m1\",\"text\":\"Hello\"}}");
    notify("item/started",
      "{\"item\":{\"type\":\"commandExecution\",\"id\":\"c1\",\"command\":\"ls\",\"cwd\":\"/work\","
        + "\"status\":\"inProgress\"}}");
    emit("{\"method\":\"item/commandExecution/requestApproval\",\"id\":7,\"params\":{\"threadId\":\"" + THREAD
      + "\",\"turnId\":\"turn1\",\"itemId\":\"c1\",\"command\":\"ls\",\"reason\":\"List files\"}}");
    JsonObject answer = JsonParser.parseString(reader.readLine()).getAsJsonObject();
    String decision = answer.getAsJsonObject("result").get("decision").getAsString();
    notify("item/completed", "{\"item\":{\"type\":\"commandExecution\",\"id\":\"c1\",\"command\":\"ls\",\"status\":"
      + "\"completed\",\"aggregatedOutput\":\"" + decision + "\",\"exitCode\":0}}");
    notify("thread/tokenUsage/updated", "{\"tokenUsage\":{\"last\":{\"inputTokens\":1200,\"outputTokens\":30,"
      + "\"cachedInputTokens\":1000},\"modelContextWindow\":400000}}");
    notify("turn/completed", "{\"turn\":{\"id\":\"turn1\",\"status\":\"completed\",\"durationMs\":1500}}");
  }

  private static void respond(String id, String result) {
    emit("{\"id\":" + id + ",\"result\":" + result + "}");
  }

  private static void notify(String method, String params) {
    emit("{\"method\":\"" + method + "\",\"params\":" + params + "}");
  }

  private static void emit(String json) {
    System.out.println(json);
    System.out.flush();
  }
}
