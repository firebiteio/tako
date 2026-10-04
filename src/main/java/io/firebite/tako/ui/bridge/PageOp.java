package io.firebite.tako.ui.bridge;

import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.context.ContextItem;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What the chat page asks of the IDE ({@code webview/src/bridge/protocol.ts} mirrors these). The wire name is the record
 * name in lower camel case, sent as {@code op}. Thread ops also carry the thread id as {@code thread}.
 */
public sealed interface PageOp {

  /** Ops the workspace handles, such as threads, projects and history. */
  sealed interface WorkspaceOp extends PageOp {
  }

  /** Ops one thread's controller handles. */
  sealed interface ThreadOp extends PageOp {
  }

  record Ready() implements WorkspaceOp {
  }

  /** @param agent the agent to run, or the default one when absent */
  record NewThread(
    @Omittable @Nullable String projectPath,
    @Omittable @Nullable String agent
  ) implements WorkspaceOp {
  }

  record AddProject() implements WorkspaceOp {
  }

  record RemoveProject(
    String path
  ) implements WorkspaceOp {
  }

  record CollapseProject(
    String path,
    boolean collapsed
  ) implements WorkspaceOp {
  }

  record Select(
    String thread
  ) implements WorkspaceOp {
  }

  record Close(
    String thread
  ) implements WorkspaceOp {
  }

  record Archive(
    String thread,
    boolean archived
  ) implements WorkspaceOp {
  }

  record History() implements WorkspaceOp {
  }

  record Usage() implements WorkspaceOp {
  }

  record OpenUrl(
    String url
  ) implements WorkspaceOp {
  }

  record Resume(
    String sessionId,
    @Omittable @Nullable String title,
    @Omittable @Nullable String projectPath,
    @Omittable @Nullable String agent
  ) implements WorkspaceOp {
  }

  /** Runs another agent in a thread that has not started a session yet. */
  record SetAgent(
    String thread,
    String agent
  ) implements WorkspaceOp {
  }

  record Copy(
    String text
  ) implements WorkspaceOp {
  }

  /** @param turn the page's number for this prompt's turn, which {@link TurnDiff} names later */
  record Send(
    String text,
    List<ContextItem> context,
    List<ImageAttachment> images,
    boolean includeActiveFile,
    int turn
  ) implements ThreadOp {
  }

  record Interrupt() implements ThreadOp {
  }

  /**
   * The answer to a {@link PageMessage.Permission}.
   *
   * @param answers    question → answer
   * @param suggestion the index of the suggestion approved with
   */
  record Permission(
    String requestId,
    boolean allow,
    @Omittable @Nullable String message,
    boolean interrupt,
    @Omittable @Nullable Map<String, String> answers,
    @Omittable @Nullable Integer suggestion
  ) implements ThreadOp {
  }

  /** Show what the tool call a permission prompt is about would change. */
  record Diff(
    String requestId
  ) implements ThreadOp {
  }

  record Open(
    String path,
    int line
  ) implements ThreadOp {
  }

  /** Insert code at the editor's caret. */
  record Insert(
    String text
  ) implements ThreadOp {
  }

  record SetModel(
    String model
  ) implements ThreadOp {
  }

  record SetMode(
    String mode
  ) implements ThreadOp {
  }

  record SetEffort(
    String effort
  ) implements ThreadOp {
  }

  record PasteImage() implements ThreadOp {
  }

  record PickImage() implements ThreadOp {
  }

  record ContextUsage() implements ThreadOp {
  }

  /** Project files matching an {@code @} mention, answered with {@link PageMessage.Files}. */
  record Files(
    String query,
    int requestId
  ) implements ThreadOp {
  }

  /** @param path one file of the turn, or all of them when absent */
  record TurnDiff(
    int turn,
    @Omittable @Nullable String path
  ) implements ThreadOp {
  }

  record Restart() implements ThreadOp {
  }

  /** The files in the agent's scratchpad, answered with {@link PageMessage.Scratchpad}. */
  record Scratchpad() implements ThreadOp {
  }

  /** Opens the agent's scratchpad folder in the system file manager. */
  record RevealScratchpad() implements ThreadOp {
  }
}
