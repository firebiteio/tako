package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.ChatHost;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.project.GitCommands;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** What a thread's controller tells the service, and asks of it. */
final class ThreadHost implements ChatHost {
  private static final Logger LOG = Logger.getInstance(ThreadHost.class);

  private final ChatService service;
  private final ThreadStore store;
  private final ThreadStore.Record record;

  ThreadHost(ChatService service, ThreadStore store, ThreadStore.Record record) {
    this.service = service;
    this.store = store;
    this.record = record;
  }

  /** The first prompt's title, unless the thread is gone or already has a name, e.g. one the person gave it. */
  @Override
  public void setTitle(String title) {
    if (this.isListed() && ThreadStore.Record.DEFAULT_TITLE.equals(this.record.title)) {
      this.record.title = title;
      this.service.postUpdate(this.record);
    }
  }

  /** Leaves an archived thread alone: its agent was stopped, a report still queued for the EDT comes too late. */
  @Override
  public void needsLook(boolean asks) {
    if (!this.isListed() || this.record.archived) {
      return;
    }
    List<ThreadStore.Record> changed = new ArrayList<>();
    if (asks) {
      changed.addAll(this.store.wakeWithAncestors(this.record));
    }
    if (!this.record.unread && !this.service.isShown(this.record.id)) {
      this.record.unread = true;
      changed.add(this.record);
    }
    this.service.postUpdates(changed);
  }

  /** Whether the record is still the thread's, so an update does not bring a closed thread back to the list. */
  private boolean isListed() {
    return this.store.find(this.record.id) == this.record;
  }

  /** Not for a thread closed meanwhile, which the chat would list again. */
  @Override
  public void recordChanged() {
    if (this.isListed()) {
      this.service.postUpdate(this.record);
    }
  }

  @Override
  public void activity() {
    this.record.updatedAt = System.currentTimeMillis();
    this.service.postUpdate(this.record);
  }

  @Override
  public void stateChanged() {
    this.service.fireActivity();
    this.service.wakeParents(this.record);
  }

  @Override
  public void prompted(String typed) {
    if (LimitResumes.movesOn(typed)) {
      this.service.dismissLimit(this.record.id);
    }
  }

  /** Ignored for a thread closed or archived meanwhile, so a late call from its agent does not bring it back. */
  @Override
  public void limitReached(@Nullable String label, @Nullable Long resetsAt, boolean namesReset) {
    if (!this.isListed() || this.record.archived) {
      return;
    }
    long reset = resetsAt != null ? resetsAt : 0;
    List<ThreadStore.Record> all = this.store.all();
    LimitResumes limits = this.service.limits();
    this.service.postUpdates(namesReset
      ? limits.onReset(this.record, label, reset, all)
      : limits.onLimit(this.record, label, reset, all));
  }

  @Override
  public List<String> additionalDirectories() {
    ProjectFolders folders = this.service.folders();
    String own = folders.folderOf(this.record.projectPath);
    List<String> directories = new ArrayList<>();
    for (ThreadStore.ProjectFolder folder : folders.all()) {
      if (!folder.path.isEmpty() && !ThreadStore.samePath(folder.path, own)) {
        directories.add(folder.path);
      }
    }
    try {
      directories.add(PastedText.createFolder(PastedText.folder()).toString());
    } catch (IOException e) {
      LOG.warn("Could not create the folder for pasted text", e);
    }
    return directories;
  }

  @Override
  public @Nullable String toolsUrl() {
    return this.service.toolsUrl(this.record.id);
  }

  @Override
  public void started(AgentInfo info) {
    ChatService.agents().remember(info);
  }

  @Override
  public Path pastFile() {
    return this.store.pastFile(this.record.id);
  }

  @Override
  public @Nullable String busyThreadIn(String topLevel) {
    Path top = Path.of(topLevel).toAbsolutePath().normalize();
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    for (Map.Entry<String, ChatController> entry : this.service.openControllers().entrySet()) {
      ChatController other = entry.getValue();
      String folder = other.gitFolder();
      if (entry.getKey().equals(this.record.id) || !other.state().isInTurn() || folder == null) {
        continue;
      }
      Path otherFolder = Path.of(folder).toAbsolutePath().normalize();
      String otherTop = GitCommands.topLevel(otherFolder, environment);
      boolean sharesFiles = top.startsWith(otherFolder) || otherTop != null && top.equals(Path.of(otherTop));
      if (sharesFiles) {
        ThreadStore.Record busy = this.store.find(entry.getKey());
        return busy != null ? busy.title : "";
      }
    }
    return null;
  }

  @Override
  public void requestAttention(String reason) {
    this.service.requestAttention(this.record, reason);
  }
}
