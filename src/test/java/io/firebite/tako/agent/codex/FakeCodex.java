package io.firebite.tako.agent.codex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for {@code codex app-server}. It answers the handshake, starts or resumes its one thread, then plays one
 * turn that streams a message and asks to run a command, echoing the decision it got. It also lists a fixed history of
 * turns and reverts them.
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
        case "thread/turns/list" -> turns(id, message.getAsJsonObject("params"));
        case "thread/revert" -> revert(id, message.getAsJsonObject("params"));
        case "turn/steer" -> emit("{\"id\":" + id + ",\"error\":{\"code\":-32600,\"message\":\"no active turn\"}}");
        default -> {
        }
      }
    }
  }

  /**
   * Two pages of turns: "first"; one without a user message; "second" with the follow-up "steer" in the same turn;
   * "third".
   */
  private static void turns(String id, JsonObject params) {
    if (!params.has("cursor")) {
      respond(id, "{\"data\":[" + turn("t1", userMessage("first")) + "," + turn("t2",
        "{\"type\":\"commandExecution\",\"id\":\"c\",\"command\":\"ls\"}") + "],\"nextCursor\":\"page2\"}");
      return;
    }
    respond(id, "{\"data\":[" + turn("t3", userMessage("second") + "," + userMessage("steer")) + ","
      + turn("t4", userMessage("third")) + "],\"nextCursor\":null}");
  }

  /** Writes the turn it reverts to before into the file {@code FAKE_CODEX_LOG} names, or fails as told. */
  private static void revert(String id, JsonObject params) throws IOException {
    if ("fail".equals(System.getenv("FAKE_CODEX_REVERT"))) {
      emit("{\"id\":" + id + ",\"error\":{\"code\":-32600,\"message\":\"thread uses legacy history\"}}");
      return;
    }
    Files.writeString(Path.of(System.getenv("FAKE_CODEX_LOG")), params.get("beforeTurnId").getAsString(),
      StandardCharsets.UTF_8);
    respond(id, "{\"thread\":{\"id\":\"" + THREAD + "\",\"turns\":[]}}");
  }

  private static String turn(String turnId, String items) {
    return "{\"id\":\"" + turnId + "\",\"status\":\"completed\",\"items\":[" + items + "]}";
  }

  private static String userMessage(String text) {
    return "{\"type\":\"userMessage\",\"id\":\"u-" + text + "\",\"content\":[{\"type\":\"text\",\"text\":\"" + text
      + "\"}]}";
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
    while (answer.has("method") && answer.get("method").getAsString().equals("turn/steer")) {
      steer(answer);
      answer = JsonParser.parseString(reader.readLine()).getAsJsonObject();
    }
    String decision = answer.getAsJsonObject("result").get("decision").getAsString();
    notify("item/completed", "{\"item\":{\"type\":\"commandExecution\",\"id\":\"c1\",\"command\":\"ls\",\"status\":"
      + "\"completed\",\"aggregatedOutput\":\"" + decision + "\",\"exitCode\":0}}");
    notify("thread/tokenUsage/updated", "{\"tokenUsage\":{\"last\":{\"inputTokens\":1200,\"outputTokens\":30,"
      + "\"cachedInputTokens\":1000},\"modelContextWindow\":400000}}");
    notify("turn/completed", "{\"turn\":{\"id\":\"turn1\",\"status\":\"completed\",\"durationMs\":1500}}");
  }

  /** Takes a steer into the running turn, unless its text says "refuse" or it names another turn. */
  private static void steer(JsonObject request) {
    String id = request.get("id").toString();
    JsonObject params = request.getAsJsonObject("params");
    String text = params.getAsJsonArray("input").get(0).getAsJsonObject().get("text").getAsString();
    if (text.contains("refuse") || !"turn1".equals(params.get("expectedTurnId").getAsString())) {
      emit("{\"id\":" + id + ",\"error\":{\"code\":-32600,\"message\":\"no active turn to steer\"}}");
      return;
    }
    respond(id, "{\"turnId\":\"turn1\"}");
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
