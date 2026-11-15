package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ui.bridge.message.thread.ChatImage;
import io.firebite.tako.ui.bridge.message.thread.Context;
import io.firebite.tako.ui.bridge.message.thread.Submit;
import io.firebite.tako.ui.bridge.message.workspace.ShowSideChat;
import io.firebite.tako.ui.bridge.message.workspace.ThreadAdded;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Starts a thread's side chats, on a fork of its session or with an excerpt of its conversation. */
public final class SideChatStarter {
  private static final Logger LOG = Logger.getInstance(SideChatStarter.class);

  private final ChatService service;
  private final ThreadStore store;

  SideChatStarter(ChatService service, ThreadStore store) {
    this.service = service;
    this.store = store;
  }

  /**
   * Starts a side chat of the thread, or of its main thread for a side chat, and shows it in the pane. Copies the main
   * thread's session when {@code forkPrompts} is above 0 and it has one, else attaches an excerpt of its conversation.
   * Must run on the EDT.
   *
   * @param forkPrompts how many prompts of the main thread's session to copy, 0 for a new session
   * @param first       the side chat's first prompt, sent unless its text is blank, else put in its composer
   * @return false when there is no such thread, so no side chat starts
   */
  public boolean start(String threadId, int forkPrompts, Submit first) {
    ThreadStore.Record found = this.store.find(threadId);
    if (found == null) {
      return false;
    }
    ThreadStore.Record main = SideChats.mainOf(this.store, found);
    ChatController controller = this.service.controller(main.id);
    String sessionId = main.sessionId;
    if (forkPrompts <= 0 || sessionId == null) {
      this.createOrRelease(main, null, 0, first, excerpt(main, controller));
      return true;
    }
    String folder = this.service.workFolder(main);
    AgentBackend backend = AgentRegistry.find(main.agent(), EnvironmentUtil.getEnvironmentMap());
    Async.inBackground(() -> {
      String forkId = null;
      try {
        forkId = backend.fork(folder, sessionId, forkPrompts);
      } catch (AgentException | RuntimeException e) {
        LOG.warn("Could not fork thread " + main.id + " for a side chat, attaching an excerpt instead", e);
      }
      int inherited = forkPrompts;
      if (forkId != null) {
        try {
          inherited = promptCount(backend.history(folder, forkId));
        } catch (RuntimeException e) {
          LOG.warn("Could not count the prompts of side chat session " + forkId, e);
        }
      }
      String session = forkId;
      int prompts = session == null ? 0 : inherited;
      ContextItem excerpt = session == null ? excerpt(main, controller) : null;
      Async.onEdt(() -> this.createOrRelease(main, session, prompts, first, excerpt));
    });
    return true;
  }

  /** Starts the agent of a side chat the pane shows, unless it runs. Doesn't select it. Must run on the EDT. */
  public void open(String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null || !record.sideChat || this.service.controller(id) != null) {
      return;
    }
    this.service.unarchive(record);
    this.service.newController(record).open();
  }

  /** Makes the side chat, else tells the main thread that it may start another. */
  private void createOrRelease(ThreadStore.Record main, @Nullable String sessionId, int inheritedPrompts,
    Submit first, @Nullable ContextItem excerpt) {
    boolean isCreated = false;
    try {
      isCreated = this.create(main, sessionId, inheritedPrompts, first, excerpt);
    } finally {
      if (!isCreated) {
        this.service.post(new ShowSideChat(main.id, true));
      }
    }
  }

  private static @Nullable ContextItem excerpt(ThreadStore.Record main, @Nullable ChatController controller) {
    return SideChats.excerpt(main.title, controller == null ? "" : controller.conversation(SideChats.EXCERPT_CHARS));
  }

  private static int promptCount(AgentHistory history) {
    int count = 0;
    if (history instanceof AgentHistory.Loaded loaded) {
      for (AgentEvent event : loaded.events()) {
        if (event instanceof AgentEvent.Prompt) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Makes the side chat unless the main thread was closed or archived meanwhile, then starts it.
   *
   * @return whether it made one
   */
  private boolean create(ThreadStore.Record main, @Nullable String sessionId, int inheritedPrompts,
    Submit first, @Nullable ContextItem excerpt) {
    if (this.store.find(main.id) != main || main.archived) {
      return false;
    }
    ThreadStore.Record record = this.store.create(sessionId, SideChats.title(first.text()), main.projectPath,
      main.agent());
    record.parentId = main.id;
    record.sideChat = true;
    record.inheritedPrompts = inheritedPrompts;
    record.model = main.model;
    record.mode = main.mode;
    record.effort = main.effort;
    record.workPath = main.workPath;
    this.store.save();
    this.service.post(new ThreadAdded(record.toItem()));
    List<ContextItem> context = new ArrayList<>(first.context());
    if (excerpt != null) {
      context.add(excerpt);
    }
    ChatController controller = this.service.newController(record);
    if (first.text().isBlank()) {
      controller.open();
      for (ContextItem item : context) {
        this.service.post(new Context(item, null), record.id);
      }
      for (ImageAttachment image : first.images()) {
        this.service.post(new ChatImage(image), record.id);
      }
    } else {
      controller.open(new Submit(first.text().strip(), context, first.images()));
    }
    this.service.post(new ShowSideChat(record.id, true));
    return true;
  }
}
