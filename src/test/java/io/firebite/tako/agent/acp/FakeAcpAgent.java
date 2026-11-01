package io.firebite.tako.agent.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Stands in for an ACP agent. It offers a model and two modes, replays session s0 on load and fails to load any other,
 * and plays one prompt that streams text, asks to edit a file and reports the edit as a diff, echoing the option it got.
 * Given a file as its argument, it appends each prompt's content blocks there, one prompt per line.
 */
public final class FakeAcpAgent {
  private FakeAcpAgent() {
  }

  public static void main(String[] args) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      JsonObject message = JsonParser.parseString(line).getAsJsonObject();
      String method = message.has("method") ? message.get("method").getAsString() : "";
      String id = message.has("id") ? message.get("id").toString() : null;
      switch (method) {
        case "initialize" -> respond(id, "{\"protocolVersion\":1,\"agentCapabilities\":{\"loadSession\":true,"
          + "\"promptCapabilities\":{\"image\":true}},\"authMethods\":[],\"agentInfo\":{\"name\":\"fake\","
          + "\"version\":\"1\"}}");
        case "session/new" -> respond(id, session("s1"));
        case "session/load" -> {
          if (!"s0".equals(message.getAsJsonObject("params").get("sessionId").getAsString())) {
            emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32002,\"message\":\"No such session\"}}");
            continue;
          }
          update("s0", "{\"sessionUpdate\":\"user_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"earlier "
            + "question\"}}");
          update("s0", "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"earlier "
            + "answer\"}}");
          respond(id, session("s0"));
        }
        case "session/prompt" -> {
          if (args.length > 0) {
            StringBuilder text = new StringBuilder();
            for (JsonElement block : message.getAsJsonObject("params").getAsJsonArray("prompt")) {
              if (block.getAsJsonObject().has("text")) {
                text.append(block.getAsJsonObject().get("text").getAsString().replace("\n", "\\n"));
              }
            }
            Files.writeString(Path.of(args[0]), text + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
          }
          prompt(id, reader);
        }
        default -> {
        }
      }
    }
  }

  private static String session(String sessionId) {
    return "{\"sessionId\":\"" + sessionId + "\",\"modes\":{\"currentModeId\":\"ask\",\"availableModes\":["
      + "{\"id\":\"ask\",\"name\":\"Ask\"},{\"id\":\"code\",\"name\":\"Code\",\"description\":\"Edits freely\"}]},"
      + "\"configOptions\":[{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\","
      + "\"currentValue\":\"fast\",\"options\":[{\"value\":\"fast\",\"name\":\"Fast\"},{\"value\":\"smart\","
      + "\"name\":\"Smart\"}]}]}";
  }

  private static void prompt(String id, BufferedReader reader) throws IOException {
    update("s1", "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hmm\"}}");
    update("s1", "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Sure, \"}}");
    update("s1", "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"editing.\"}}");
    update("s1", "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"title\":\"Edit a.txt\",\"kind\":\"edit\","
      + "\"status\":\"pending\",\"locations\":[{\"path\":\"a.txt\"}],\"content\":[{\"type\":\"diff\",\"path\":"
      + "\"a.txt\",\"oldText\":\"one\\ntwo\",\"newText\":\"one\\nthree\"}]}");
    emit("{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"session/request_permission\",\"params\":{\"sessionId\":\"s1\","
      + "\"toolCall\":{\"toolCallId\":\"t1\"},\"options\":[{\"optionId\":\"yes\",\"name\":\"Allow\",\"kind\":"
      + "\"allow_once\"},{\"optionId\":\"always\",\"name\":\"Always allow edits\",\"kind\":\"allow_always\"},"
      + "{\"optionId\":\"no\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]}}");
    JsonObject answer = JsonParser.parseString(reader.readLine()).getAsJsonObject();
    String option = answer.getAsJsonObject("result").getAsJsonObject("outcome").get("optionId").getAsString();
    update("s1", "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t1\",\"status\":\"completed\","
      + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"" + option + "\"}},"
      + "{\"type\":\"diff\",\"path\":\"a.txt\",\"oldText\":\"one\\ntwo\",\"newText\":\"one\\nthree\"}]}");
    update("s1", "{\"sessionUpdate\":\"usage_update\",\"used\":5000,\"size\":200000}");
    respond(id, "{\"stopReason\":\"end_turn\"}");
  }

  private static void update(String sessionId, String update) {
    emit("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"" + sessionId
      + "\",\"update\":" + update + "}}");
  }

  private static void respond(String id, String result) {
    emit("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
  }

  private static void emit(String json) {
    System.out.println(json);
    System.out.flush();
  }
}
