package io.firebite.tako.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Collects what a session reports, for tests to wait on. */
public final class RecordingListener implements AgentListener {
  private static final long TIMEOUT_SECONDS = 20;

  public final BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
  public final BlockingQueue<Permission> permissions = new LinkedBlockingQueue<>();
  public final List<List<AgentEvent>> histories = new ArrayList<>();
  public final List<String> editedFiles = new ArrayList<>();

  @Override
  public void onEvent(AgentEvent event) {
    this.events.add(event);
  }

  @Override
  public synchronized void onHistory(List<AgentEvent> events) {
    this.histories.add(events);
  }

  @Override
  public void onPermission(String requestId, PermissionPrompt prompt) {
    this.permissions.add(new Permission(requestId, prompt));
  }

  @Override
  public synchronized void onFileEdited(String path, String originalContent) {
    this.editedFiles.add(path + "=" + originalContent);
  }

  @Override
  public void onExit(int exitCode, String stderrTail) {
  }

  /** The events until one of that type, which is last. */
  public <T extends AgentEvent> List<AgentEvent> until(Class<T> type) throws InterruptedException {
    List<AgentEvent> seen = new ArrayList<>();
    while (true) {
      AgentEvent event = this.events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (event == null) {
        throw new AssertionError("No " + type.getSimpleName() + " after " + seen);
      }
      seen.add(event);
      if (type.isInstance(event)) {
        return seen;
      }
    }
  }

  public Permission nextPermission() throws InterruptedException {
    Permission permission = this.permissions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (permission == null) {
      throw new AssertionError("No permission prompt");
    }
    return permission;
  }

  public record Permission(
    String requestId,
    PermissionPrompt prompt
  ) {
  }
}
