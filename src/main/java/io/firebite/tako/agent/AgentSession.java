package io.firebite.tako.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** One running agent serving one thread. Safe from any thread. */
public interface AgentSession {

  /** Connects and starts or resumes the session. Completes with what the agent offers. */
  CompletableFuture<AgentInfo> initialize();

  /** Sends a prompt. A prompt sent while the agent works waits for its turn to end. */
  void prompt(String text, List<ImageAttachment> images);

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

  boolean isAlive();

  void stop();
}
