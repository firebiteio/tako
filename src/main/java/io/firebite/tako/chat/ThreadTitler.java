package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ThreadTitles;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ui.bridge.message.ChatError;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** Has a thread's agent name it from its conversation. */
public final class ThreadTitler {
  private static final Logger LOG = Logger.getInstance(ThreadTitler.class);

  private final ChatService service;
  private final ThreadStore store;

  ThreadTitler(ChatService service, ThreadStore store) {
    this.service = service;
    this.store = store;
  }

  /**
   * Whether the thread's agent can name it now: it has a conversation to read and a one-shot call. Looks for the
   * agent's CLI, so call off the EDT.
   *
   * @param shown whether the chat holds messages of the thread
   */
  public boolean canRegenerate(String id, boolean shown) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return false;
    }
    AgentBackend backend = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap());
    return (shown || record.sessionId != null && backend.hasStoredHistory()) && backend.canAsk();
  }

  /**
   * Has the thread's agent name it from its first request and latest messages, and saves the title. Takes the
   * conversation the chat holds, else reads the session's history when the agent keeps it in files. Shows failures.
   * Keeps the thread as it is if it was removed or got another title meanwhile.
   *
   * @return completes on the EDT when done
   */
  public CompletableFuture<Void> regenerate(String id, List<ThreadTitles.Message> shown) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      done.complete(null);
      return done;
    }
    String folder = this.service.workFolder(record);
    String sessionId = record.sessionId;
    String oldTitle = record.title;
    AgentBackend backend = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap());
    Async.inBackground(() -> {
      try {
        String title = ask(backend, folder, sessionId, shown);
        Async.onEdt(() -> {
          if (this.store.find(id) == record && oldTitle.equals(record.title)) {
            record.title = title;
            this.service.postUpdate(record);
          }
          done.complete(null);
        });
      } catch (AgentException | RuntimeException e) {
        if (e instanceof RuntimeException) {
          LOG.warn("Could not regenerate the title of thread " + id, e);
        }
        Async.onEdt(() -> {
          this.service.post(
            new ChatError(TakoBundle.message("error.title", Objects.toString(e.getMessage(), e.toString()))));
          done.complete(null);
        });
      }
    });
    return done;
  }

  private static String ask(AgentBackend backend, String folder, @Nullable String sessionId,
    List<ThreadTitles.Message> shown) throws AgentException {
    List<ThreadTitles.Message> conversation = shown;
    if (conversation.isEmpty() && sessionId != null
      && backend.history(folder, sessionId) instanceof AgentHistory.Loaded loaded) {
      conversation = ThreadTitles.fromEvents(loaded.events());
    }
    String prompt = ThreadTitles.prompt(conversation);
    if (prompt == null) {
      throw new AgentException(TakoBundle.message("error.title.no.conversation"));
    }
    String title = ThreadTitles.title(backend.ask(Path.of(folder), prompt));
    if (title == null) {
      throw new AgentException(TakoBundle.message("error.title.blank"));
    }
    return title;
  }
}
