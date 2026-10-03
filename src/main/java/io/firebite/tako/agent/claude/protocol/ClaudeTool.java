package io.firebite.tako.agent.claude.protocol;

import io.firebite.tako.agent.ToolKind;
import org.jetbrains.annotations.Nullable;

/** The CLI's built-in tools and what each does. MCP and other tools map to none. */
public enum ClaudeTool {
  READ("Read", ToolKind.READ),
  NOTEBOOK_READ("NotebookRead", ToolKind.READ),
  WRITE("Write", ToolKind.EDIT),
  EDIT("Edit", ToolKind.EDIT),
  MULTI_EDIT("MultiEdit", ToolKind.EDIT),
  NOTEBOOK_EDIT("NotebookEdit", ToolKind.EDIT),
  BASH("Bash", ToolKind.COMMAND),
  POWERSHELL("PowerShell", ToolKind.COMMAND),
  BASH_OUTPUT("BashOutput", ToolKind.COMMAND),
  KILL_SHELL("KillShell", ToolKind.COMMAND),
  GREP("Grep", ToolKind.SEARCH),
  GLOB("Glob", ToolKind.SEARCH),
  LS("LS", ToolKind.SEARCH),
  WEB_FETCH("WebFetch", ToolKind.WEB),
  WEB_SEARCH("WebSearch", ToolKind.WEB),
  TASK("Task", ToolKind.AGENT),
  AGENT("Agent", ToolKind.AGENT),
  WORKFLOW("Workflow", ToolKind.AGENT),
  TODO_WRITE("TodoWrite", ToolKind.TODO),
  ASK_USER_QUESTION("AskUserQuestion", ToolKind.QUESTION),
  EXIT_PLAN_MODE("ExitPlanMode", ToolKind.PLAN);

  private final String wireName;
  private final ToolKind kind;

  ClaudeTool(String wireName, ToolKind kind) {
    this.wireName = wireName;
    this.kind = kind;
  }

  public static @Nullable ClaudeTool named(@Nullable String name) {
    for (ClaudeTool tool : values()) {
      if (tool.wireName.equals(name)) {
        return tool;
      }
    }
    return null;
  }

  /** What a tool of this name does, or {@link ToolKind#OTHER} for tools the CLI does not build in. */
  public static ToolKind kindOf(@Nullable String name) {
    ClaudeTool tool = named(name);
    return tool == null ? ToolKind.OTHER : tool.kind;
  }

  /** Writes one file whose content can be previewed from the call's input. */
  public boolean isTextEdit() {
    return this == WRITE || this == EDIT || this == MULTI_EDIT;
  }
}
