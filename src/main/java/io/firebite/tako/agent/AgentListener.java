package io.firebite.tako.agent;

import java.util.List;

/** What an {@link AgentSession} reports. Called on its reader thread. */
public interface AgentListener {

  void onEvent(AgentEvent event);

  /** The resumed session's past, sent once after it started. */
  void onHistory(List<AgentEvent> events);

  void onPermission(String requestId, PermissionPrompt prompt);

  /** A file the agent changes, with its content before the change, for the turn's diff. */
  void onFileEdited(String path, String originalContent);

  void onExit(int exitCode, String stderrTail);
}
