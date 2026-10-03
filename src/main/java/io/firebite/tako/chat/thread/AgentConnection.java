package io.firebite.tako.chat.thread;

import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.PermissionPrompt;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/** A thread's current agent session. A replaced or stopped session's callbacks no longer arrive. */
final class AgentConnection {
  private final Object lock = new Object();
  private @Nullable AgentSession session;
  private int generation;

  /** Stops the current session, if any, and starts one whose callbacks reach {@code listener}. */
  AgentSession replace(AgentBackend backend, AgentLaunch launch, AgentListener listener) throws AgentException {
    synchronized (this.lock) {
      this.stopLocked();
      AgentSession started = backend.start(launch, new Current(this.generation, listener));
      this.session = started;
      return started;
    }
  }

  void stop() {
    synchronized (this.lock) {
      this.stopLocked();
    }
  }

  @Nullable
  AgentSession live() {
    synchronized (this.lock) {
      return this.session != null && this.session.isAlive() ? this.session : null;
    }
  }

  void ifLive(Consumer<AgentSession> action) {
    AgentSession live = this.live();
    if (live != null) {
      action.accept(live);
    }
  }

  private void stopLocked() {
    this.generation++;
    if (this.session != null) {
      this.session.stop();
      this.session = null;
    }
  }

  /** Passes callbacks on while its session is still the connection's current one. */
  private final class Current implements AgentListener {
    private final int generation;
    private final AgentListener listener;

    private Current(int generation, AgentListener listener) {
      this.generation = generation;
      this.listener = listener;
    }

    private boolean isCurrent() {
      synchronized (AgentConnection.this.lock) {
        return AgentConnection.this.generation == this.generation;
      }
    }

    @Override
    public void onEvent(AgentEvent event) {
      if (this.isCurrent()) {
        this.listener.onEvent(event);
      }
    }

    @Override
    public void onHistory(List<AgentEvent> events) {
      if (this.isCurrent()) {
        this.listener.onHistory(events);
      }
    }

    @Override
    public void onPermission(String requestId, PermissionPrompt prompt) {
      if (this.isCurrent()) {
        this.listener.onPermission(requestId, prompt);
      }
    }

    @Override
    public void onFileEdited(String path, String originalContent) {
      if (this.isCurrent()) {
        this.listener.onFileEdited(path, originalContent);
      }
    }

    @Override
    public void onExit(int exitCode, String stderrTail) {
      synchronized (AgentConnection.this.lock) {
        if (AgentConnection.this.generation != this.generation) {
          return;
        }
        AgentConnection.this.session = null;
      }
      this.listener.onExit(exitCode, stderrTail);
    }
  }
}
