package io.firebite.tako.agent.claude;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.claude.protocol.CliMessage;

/** Called on the process's reader thread. */
public interface ClaudeProcessListener {
  void onMessage(CliMessage message);

  /** Claude asks to use a tool or asks the person a question. Answer with {@link ClaudeProcess#respond}. */
  void onPermissionRequest(String requestId, JsonObject request);

  void onExit(int exitCode, String stderrTail);
}
