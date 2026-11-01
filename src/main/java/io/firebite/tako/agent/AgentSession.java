package io.firebite.tako.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** One running agent serving one thread. Safe from any thread. */
public interface AgentSession {

  /** Connects and starts or resumes the session. Completes with what the agent offers. */
  CompletableFuture<AgentInfo> initialize();

  /**
   * Sends a prompt. One sent while a turn runs waits until the agent takes it, into that turn or as the next one. The
   * agent announces each prompt it takes with an {@link AgentEvent.PromptTaken}.
   *
   * @param key names the prompt in its {@link AgentEvent.PromptTaken}
   */
  void prompt(String text, List<ImageAttachment> images, int key);

  /**
   * Hands a prompt to the running turn without stopping it, for agents whose {@link AgentInfo.Capabilities#steer} says
   * so. The agent announces it with an {@link AgentEvent.PromptTaken}: joining the turn, or starting the next one if it
   * could not take it in time.
   *
   * @return completes with true when the agent has the prompt, false when it refused it, which then was not sent
   */
  default CompletableFuture<Boolean> steer(String text, List<ImageAttachment> images, int key) {
    return CompletableFuture.completedFuture(false);
  }

  void interrupt();

  void answer(String requestId, PermissionAnswer answer);

  void setModel(String model);

  void setMode(String mode);

  void setEffort(String effort);

  /** The context window broken down, or null when the agent cannot tell. */
  @Nullable
  CompletableFuture<ContextUsage> contextUsage();

  /** The change a pending permission prompt's tool call would make, or null when unknown. Reads files. */
  @Nullable
  EditPreview proposedEdit(String requestId);

  /** Whether the agent took the {@link AgentLaunch#toolsUrl} it was launched with, once {@link #initialize} is done. */
  default boolean takesTools() {
    return true;
  }

  boolean isAlive();

  void stop();
}
