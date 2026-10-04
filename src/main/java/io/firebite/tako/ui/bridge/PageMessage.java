package io.firebite.tako.ui.bridge;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.project.FileIndex;
import io.firebite.tako.ide.project.GitInfo;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What the IDE tells the chat page ({@code webview/src/bridge/protocol.ts} mirrors these). The wire name is the record
 * name in lower camel case, sent as {@code kind}, without null fields.
 */
public sealed interface PageMessage {

  sealed interface WorkspaceMessage extends PageMessage {
  }

  /** Posted for one thread, which the page learns from the added {@code thread} field. */
  sealed interface ThreadMessage extends PageMessage {
  }

  record Project(
    @Nullable String projectRoot,
    String projectName
  ) implements WorkspaceMessage {
  }

  record Projects(
    List<ProjectItem> items
  ) implements WorkspaceMessage {
  }

  /** @param primary the IDE project, which cannot be removed */
  record ProjectItem(
    String path,
    String name,
    boolean collapsed,
    boolean primary,
    @Nullable String icon,
    @Nullable String iconDark
  ) {
  }

  /** The agents a thread can run, the default first. */
  record Agents(
    List<AgentOption> items
  ) implements WorkspaceMessage {
  }

  record AgentOption(
    String id,
    String name
  ) {
  }

  record Threads(
    List<ThreadItem> items
  ) implements WorkspaceMessage {
  }

  record ThreadAdded(
    ThreadItem item
  ) implements WorkspaceMessage {
  }

  record ThreadUpdated(
    ThreadItem item
  ) implements WorkspaceMessage {
  }

  record ThreadRemoved(
    String thread
  ) implements WorkspaceMessage {
  }

  record ThreadItem(
    String id,
    String title,
    boolean archived,
    long updatedAt,
    boolean hasSession,
    double costUsd,
    long tokens,
    @Nullable String branch,
    @Nullable String worktree,
    @Nullable String projectPath,
    @Nullable GitInfo.PullRequest pullRequest,
    String agent
  ) {
  }

  record Select(
    String thread
  ) implements WorkspaceMessage {
  }

  /** The agents' sessions that no thread holds yet, offered to resume. */
  record Sessions(
    List<SessionItem> items
  ) implements WorkspaceMessage {
  }

  record SessionItem(
    String id,
    String title,
    long lastModified,
    String projectPath,
    String projectName,
    String agent,
    String agentName
  ) {
  }

  /** Cost and tokens of every session of the workspace's project folders, at API list prices. */
  record Usage(
    double totalCost,
    long totalTokens,
    int sessions,
    int estimatedSessions,
    double estimatedCost,
    int retentionDays,
    @Nullable String oldestDay,
    List<ModelCost> models,
    List<DayCost> days,
    List<ProjectCost> projects,
    List<SessionCost> top
  ) implements WorkspaceMessage {
  }

  record ModelCost(
    String model,
    long input,
    long output,
    long cacheRead,
    long cacheWrite,
    double cost
  ) {
  }

  /** @param day ISO date */
  record DayCost(
    String day,
    double cost
  ) {
  }

  record ProjectCost(
    String name,
    String path,
    double cost,
    long tokens,
    int sessions
  ) {
  }

  /** @param thread the thread holding the session, if any */
  record SessionCost(
    String sessionId,
    String projectPath,
    String projectName,
    String title,
    double cost,
    long tokens,
    @Nullable String thread,
    boolean archived,
    boolean estimated
  ) {
  }

  record Theme(
    String css
  ) implements WorkspaceMessage {
  }

  record Error(
    String text
  ) implements WorkspaceMessage, ThreadMessage {
  }

  /** The thread's agent is ready, with what it offers the composer. */
  record Init(
    AgentInfo info,
    @Nullable String projectRoot
  ) implements ThreadMessage {
  }

  record Event(
    AgentEvent event
  ) implements ThreadMessage {
  }

  /** The thread's past, replayed when the thread opens. */
  record Replay(
    List<AgentEvent> events
  ) implements ThreadMessage {
  }

  /** The agent waits for the person. */
  record Permission(
    String requestId,
    PermissionPrompt prompt
  ) implements ThreadMessage {
  }

  /** @param prefill a prompt to propose when the composer is empty */
  record Context(
    ContextItem item,
    @Nullable String prefill
  ) implements ThreadMessage {
  }

  record Image(
    ImageAttachment image
  ) implements ThreadMessage {
  }

  record Files(
    int requestId,
    List<FileIndex.Match> items
  ) implements ThreadMessage {
  }

  /** @param files paths relative to {@code path} with {@code /} separators, newest first */
  record Scratchpad(
    String path,
    List<String> files
  ) implements ThreadMessage {
  }

  record ContextUsage(
    io.firebite.tako.agent.ContextUsage data
  ) implements ThreadMessage {
  }

  record Exit(
    int code,
    String stderr
  ) implements ThreadMessage {
  }

  /** Sends a prompt as if the person typed it, e.g. the one an agent gave the thread it created. */
  record Submit(
    String text
  ) implements ThreadMessage {
  }
}
