package io.firebite.tako.agent;

import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** A coding agent a thread can run, such as Claude Code, Codex or an ACP agent. */
public interface AgentBackend {

  /** Stable id, stored with each thread. */
  String id();

  String displayName();

  /** Starts the agent's process for one thread. Nothing reaches {@code listener} before {@code initialize}. */
  AgentSession start(AgentLaunch launch, AgentListener listener) throws AgentException;

  /** A session's history before the agent starts. May read files. */
  AgentHistory history(String projectPath, String sessionId);

  /** The agent's sessions in a folder, newest first, that a thread could resume. May start a process. */
  List<AgentSessionInfo> sessions(String projectPath, int limit);

  /** What a session consumed, read without starting the agent. Null when unknown. */
  default @Nullable SessionCost sessionCost(String projectPath, String sessionId) {
    return null;
  }

  /**
   * Copies a session's first {@code prompts} turns into a new session, for agents whose
   * {@link AgentInfo.Capabilities#fork()} is set. Reads and writes files.
   *
   * @return the new session's id
   */
  default String fork(String projectPath, String sessionId, int prompts) throws AgentException {
    throw new UnsupportedOperationException(this.displayName() + " cannot fork sessions");
  }

  /**
   * Makes the session forget its prompts after the first {@code prompts.size() - 1}, for agents whose
   * {@link AgentInfo.Capabilities#rewind()} is set. The thread's own agent must not run. Changes nothing when the
   * session's prompts do not start with {@code prompts}. Reads and writes files or starts the agent.
   *
   * @param prompts the typed texts of the prompts to keep, then of the first one to forget
   * @return the session the thread continues
   */
  default String rewind(String projectPath, String sessionId, List<String> prompts) throws AgentException {
    throw new UnsupportedOperationException(this.displayName() + " cannot rewind sessions");
  }

  /** Whether the session's prompts or answers contain {@code query}, ignoring case. False when unknown. May read files. */
  default boolean sessionMentions(String projectPath, String sessionId, String query) {
    return false;
  }

  /** Whether {@link #history} reads a session from files, without the agent running. */
  default boolean hasStoredHistory() {
    return false;
  }

  /**
   * Whether {@link #start} can run now: the agent's CLI is found and, where Tako holds its login, one is set. A login
   * the CLI keeps itself is only checked once it starts. Looks for files, so call off the EDT.
   */
  default boolean canStart() {
    return true;
  }

  /** Whether {@link #ask} can run now, e.g. the agent's CLI is installed. Looks for files, so call off the EDT. */
  default boolean canAsk() {
    return false;
  }

  /**
   * Answers one prompt with a cheap model, without tools and without saving a session, e.g. to name a thread. Starts
   * a process, so call off the EDT.
   */
  default String ask(Path workDirectory, String prompt) throws AgentException {
    throw new UnsupportedOperationException(this.displayName() + " cannot answer one-shot prompts");
  }
}
