package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.agent.claude.transcript.SessionUsage;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ui.bridge.message.usage.Usage;
import io.firebite.tako.ui.bridge.message.workspace.SessionItem;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** What the agents' stored sessions of the project folders tell. Reads files, so call off the EDT. */
public final class SessionQueries {
  private static final Logger LOG = Logger.getInstance(SessionQueries.class);
  private static final int SESSION_LIST_LIMIT = 50;

  private final ChatService service;
  private final ThreadStore store;

  SessionQueries(ChatService service, ThreadStore store) {
    this.service = service;
    this.store = store;
  }

  /** Every agent's sessions in every project folder that no thread holds, newest first. Starts agents to ask. */
  public List<SessionItem> history() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    List<CompletableFuture<List<SessionItem>>> lookups = new ArrayList<>();
    for (AgentBackend backend : AgentRegistry.backends(environment)) {
      for (ThreadStore.ProjectFolder folder : this.service.folders().all()) {
        lookups.add(CompletableFuture.supplyAsync(() -> {
          List<SessionItem> items = new ArrayList<>();
          for (AgentSessionInfo session : backend.sessions(folder.path, SESSION_LIST_LIMIT)) {
            items.add(new SessionItem(session.id(), session.title(), session.lastModified(), folder.path,
              folder.name, backend.id(), backend.displayName()));
          }
          return items;
        }, AppExecutorUtil.getAppExecutorService()));
      }
    }
    List<SessionItem> found = new ArrayList<>();
    for (CompletableFuture<List<SessionItem>> lookup : lookups) {
      for (SessionItem item : lookup.join()) {
        if (this.store.findBySession(item.id()) == null) {
          found.add(item);
        }
      }
    }
    found.sort(Comparator.comparingLong(SessionItem::lastModified).reversed());
    return found.subList(0, Math.min(SESSION_LIST_LIMIT, found.size()));
  }

  /** The threads whose sessions mention {@code query}, as far as their agents can tell. */
  public Set<String> search(String query) {
    Map<String, AgentBackend> backends = new HashMap<>();
    for (AgentBackend backend : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
      backends.put(backend.id(), backend);
    }
    Set<String> found = new HashSet<>();
    for (ThreadStore.Record record : this.store.all()) {
      AgentBackend backend = backends.get(record.agent());
      String sessionId = record.sessionId;
      if (backend != null && sessionId != null
        && backend.sessionMentions(this.service.workFolder(record), sessionId, query)) {
        found.add(record.id);
      }
    }
    return found;
  }

  /** Cost and tokens of every session of the project folders. */
  public Usage usage() {
    return UsageReport.build(this.service.folders().all(), this.store, EnvironmentUtil.getEnvironmentMap());
  }

  /** What the project folders' sessions cost today. */
  public double todayCost() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    LocalDate today = LocalDate.now();
    double cost = 0;
    for (ThreadStore.ProjectFolder folder : this.service.folders().all()) {
      try {
        for (SessionUsage usage : SessionStore.forProject(environment, folder.path).allUsage()) {
          cost += usage.costByDay().getOrDefault(today, 0.0);
        }
      } catch (IOException e) {
        LOG.debug("Could not read the session usage of " + folder.path, e);
      }
    }
    return cost;
  }

  /** Reads the transcripts once, so the first usage report is quick. */
  void warmUsage() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    for (ThreadStore.ProjectFolder folder : this.service.folders().all()) {
      try {
        SessionStore.forProject(environment, folder.path).allUsage();
      } catch (IOException e) {
        LOG.debug("Could not warm the usage cache", e);
      }
    }
  }
}
