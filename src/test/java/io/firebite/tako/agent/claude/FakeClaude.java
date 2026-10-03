package io.firebite.tako.agent.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Stands in for {@code claude -p} in stream-json mode, with one prompt, one permission prompt and one result. */
public final class FakeClaude {
  private FakeClaude() {
  }

  public static void main(String[] args) throws IOException {
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
      } else if (line.startsWith("{\"type\":\"user\"")) {
        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\"}");
        emit("{\"type\":\"control_request\",\"request_id\":\"perm_1\",\"request\":{\"subtype\":\"can_use_tool\","
          + "\"tool_name\":\"Write\",\"input\":{\"file_path\":\"a.txt\",\"content\":\"ok\"}}}");
      } else if (line.contains("\"request_id\":\"perm_1\"")) {
        String verdict = line.contains("\"behavior\":\"allow\"") ? "allowed" : "denied";
        emit("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + verdict + "\"}]}}");
        emit("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s1\",\"result\":\"" + verdict + "\"}");
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
