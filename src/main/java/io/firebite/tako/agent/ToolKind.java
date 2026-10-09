package io.firebite.tako.agent;

import com.google.gson.annotations.SerializedName;

/** What a tool call does, which decides how the chat shows it. */
public enum ToolKind {
  @SerializedName("read") READ,
  @SerializedName("edit") EDIT,
  @SerializedName("command") COMMAND,
  @SerializedName("search") SEARCH,
  @SerializedName("web") WEB,
  /** A subagent or workflow, which runs as a task with steps of its own. */
  @SerializedName("agent") AGENT,
  @SerializedName("todo") TODO,
  @SerializedName("question") QUESTION,
  @SerializedName("plan") PLAN,
  @SerializedName("other") OTHER
}
