package io.firebite.tako.ui.bridge.message.workspace;

/** An agent's session that no thread holds yet, offered to resume. */
public record SessionItem(
  String id,
  String title,
  long lastModified,
  String projectPath,
  String projectName,
  String agent,
  String agentName
) {
}
