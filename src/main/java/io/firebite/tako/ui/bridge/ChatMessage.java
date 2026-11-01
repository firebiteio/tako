package io.firebite.tako.ui.bridge;

import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.chat.thread.QueuedPrompts;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ide.project.ProjectIcons;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What the IDE tells the chat.
 */
public sealed interface ChatMessage {

  sealed interface WorkspaceMessage extends ChatMessage {
  }

  /** Posted for one thread, along with its id. */
  sealed interface ThreadMessage extends ChatMessage {
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
    @Nullable ProjectIcons.Icon icon,
    @Nullable ProjectIcons.Icon iconDark
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
    String agent,
    long firstTurnMillis,
    @Nullable String parentId,
    @Nullable String watching,
    boolean pinned,
    boolean unread,
    long snoozedUntil,
    boolean sideChat,
    int inheritedPrompts
  ) {
  }

  /**
   * What the settings let the chat offer.
   *
   * @param steers a prompt sent while the agent works steers its turn by default, instead of queuing
   */
  record Settings(
    boolean parallelRuns,
    boolean steers
  ) implements WorkspaceMessage {
  }

  record Benches(
    List<BenchItem> items
  ) implements WorkspaceMessage {
  }

  /**
   * @param baseCommit the short hash of the commit the runs started from
   * @param threads    the runs' threads in run order, including closed ones
   */
  record BenchItem(
    String id,
    String title,
    @Nullable String projectPath,
    String baseCommit,
    List<String> threads
  ) {
  }

  /**
   * What a run changed against the commit the bench started from.
   *
   * @param error why the run's changes could not be read, such as a removed worktree
   */
  record RunChanges(
    int files,
    int added,
    int removed,
    @Nullable String error
  ) {
  }

  record Select(
    String thread
  ) implements WorkspaceMessage {
  }

  /**
   * Shows the side chat in its main thread's pane.
   *
   * @param started the side chat was just made, so the main thread may start another
   */
  record ShowSideChat(
    String thread,
    boolean started
  ) implements WorkspaceMessage {
  }

  /** An agent's session that no thread holds yet, offered to resume. */
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
  ) {
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

  record Error(
    String text
  ) implements WorkspaceMessage, ThreadMessage {
  }

  /** Something the person should know that does not fail the turn. */
  record Notice(
    String text
  ) implements ThreadMessage {
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

  record ContextUsage(
    io.firebite.tako.agent.ContextUsage data
  ) implements ThreadMessage {
  }

  record Exit(
    int code,
    String stderr
  ) implements ThreadMessage {
  }

  /**
   * The prompts the agent has not taken that the chat shows as queued, in order.
   *
   * @param held    they wait until a prompt is sent, after a stop, an error or the agent's end
   * @param running a turn runs or a prompt is on its way to the agent
   */
  record Queue(
    List<QueueItem> items,
    boolean held,
    boolean running
  ) implements ThreadMessage {
  }

  record QueueItem(
    int turn,
    QueuedPrompts.Status status
  ) {
  }

  /**
   * The thread closed with prompts queued, which went to the prompt stash.
   *
   * @param count how many the stash kept
   */
  record QueueStashed(
    int count
  ) implements ThreadMessage {
  }

  /**
   * How the {@code .worktreesetup} of the thread's new worktree is doing. DONE with no steps only takes the card away.
   *
   * @param step      the running or failed command's number, from 1
   * @param output    the end of the output so far
   * @param error     why it failed, when it did
   * @param startedAt when the setup started, in epoch millis
   * @param endedAt   when it ended, 0 while it runs
   */
  record Setup(
    SetupPhase phase,
    int step,
    int steps,
    String command,
    String output,
    @Nullable String error,
    long startedAt,
    long endedAt
  ) implements ThreadMessage {
  }

  enum SetupPhase {
    RUNNING,
    /** Failed or cancelled; the thread's prompts wait for a retry or a start without setup. */
    FAILED,
    DONE,
    /** The person started the agent without setup. */
    SKIPPED
  }

  /** Sends a prompt as if the person typed it, e.g. the one an agent gave the thread it created. */
  record Submit(
    String text,
    List<ContextItem> context,
    List<ImageAttachment> images
  ) implements ThreadMessage {
  }

  /**
   * No run of a {@link io.firebite.tako.chat.BenchRequest} started; gives its prompt back to its source thread.
   *
   * @param error why, empty when the person cancelled
   */
  record BenchFailed(
    Submit prompt,
    String error
  ) implements ThreadMessage {
  }

  /**
   * A rewind ended.
   *
   * @param rewound whether the agent forgot the prompt, so it goes back into the composer
   * @param error   what went wrong, or null
   */
  record Rewound(
    boolean rewound,
    @Nullable String error
  ) implements ThreadMessage {
  }
}
