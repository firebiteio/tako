package io.firebite.tako.agent.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for {@code claude -p} in stream-json mode, with one prompt, one permission prompt and one result. A prompt
 * saying "fail" ends its turn with an error instead, one saying "exit now" ends the process, and an interrupt ends the
 * turn as interrupted. A new session is named after the process, a resumed one keeps its id.
 */
public final class FakeClaude {
  private FakeClaude() {
  }

  public static void main(String[] args) throws IOException {
    String session = "s" + ProcessHandle.current().pid();
    for (int i = 0; i + 1 < args.length; i++) {
      if (args[i].equals("--resume")) {
        session = args[i + 1];
      }
    }
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.contains("\"subtype\":\"initialize\"")) {
        String id = requestId(line);
        emit("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"" + id
          + "\",\"response\":{\"commands\":[{\"name\":\"compact\"}]}}}");
      } else if (line.contains("\"subtype\":\"bogus\"")) {
        emit("{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\",\"request_id\":\"" + requestId(line)
          + "\",\"error\":\"Unsupported control request subtype: bogus\"}}");
      } else if (line.contains("\"subtype\":\"get_settings\"")
        || line.contains("\"subtype\":\"apply_flag_settings\"")) {
        emit("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"" + requestId(line)
          + "\",\"response\":{\"applied\":{\"effort\":\"medium\"}}}}");
      } else if (line.contains("\"subtype\":\"interrupt\"")) {
        emit("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"" + requestId(line)
          + "\",\"response\":{}}}");
        emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":"
          + "\"[Request interrupted by user]\"}]}}");
        emit("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"session_id\":\"" + session + "\"}");
      } else if (line.startsWith("{\"type\":\"user\"") && line.contains("exit now")) {
        System.exit(3);
      } else if (line.startsWith("{\"type\":\"user\"") && line.contains("fail")) {
        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + session + "\"}");
        emit("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"session_id\":\"" + session + "\"}");
      } else if (line.startsWith("{\"type\":\"user\"")) {
        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + session + "\"}");
        emit("{\"type\":\"control_request\",\"request_id\":\"perm_1\",\"request\":{\"subtype\":\"can_use_tool\","
          + "\"tool_name\":\"Write\",\"input\":{\"file_path\":\"a.txt\",\"content\":\"ok\"}}}");
      } else if (line.contains("\"request_id\":\"perm_1\"")) {
        String verdict = line.contains("\"behavior\":\"allow\"") ? "allowed" : "denied";
        emit("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + verdict + "\"}]}}");
        emit("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"" + session + "\",\"result\":\"" + verdict
          + "\"}");
      }
    }
    System.err.println("fake claude done");
  }

  private static String requestId(String line) {
    int start = line.indexOf("\"request_id\":\"") + "\"request_id\":\"".length();
    return line.substring(start, line.indexOf('"', start));
  }

  private static void emit(String json) {
    System.out.println(json);
    System.out.flush();
  }
}
