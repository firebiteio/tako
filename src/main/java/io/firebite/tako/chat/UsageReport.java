package io.firebite.tako.chat;

import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.agent.claude.transcript.SessionUsage;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ui.bridge.PageMessage;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Sums the usage of every session of the workspace's project folders into the page's usage view. */
final class UsageReport {
  private static final Logger LOG = Logger.getInstance(UsageReport.class);
  private static final int TOP_SESSIONS = 8;

  private UsageReport() {
  }

  /** Reads every transcript, which {@link SessionStore} caches, so call off the EDT. */
  static PageMessage.Usage build(List<ThreadStore.ProjectFolder> folders, ThreadStore threads,
    Map<String, String> environment) {
    List<FolderSession> all = new ArrayList<>();
    List<PageMessage.ProjectCost> projects = new ArrayList<>();
    for (ThreadStore.ProjectFolder folder : folders) {
      SessionStore sessions = SessionStore.forProject(environment, folder.path);
      double cost = 0;
      long tokens = 0;
      int count = 0;
      try {
        for (SessionUsage usage : sessions.allUsage()) {
          all.add(new FolderSession(folder, usage, sessions));
          cost += usage.costUsd();
          tokens += usage.totalTokens();
          count++;
        }
      } catch (IOException e) {
        LOG.warn("Could not read session usage of " + folder.path, e);
      }
      projects.add(new PageMessage.ProjectCost(folder.name, folder.path, cost, tokens, count));
    }

    Map<String, ModelTotals> byModel = new TreeMap<>();
    TreeMap<LocalDate, Double> byDay = new TreeMap<>();
    double totalCost = 0;
    long totalTokens = 0;
    int estimated = 0;
    double estimatedCost = 0;
    for (FolderSession session : all) {
      SessionUsage usage = session.usage();
      totalCost += usage.costUsd();
      totalTokens += usage.totalTokens();
      if (usage.estimated()) {
        estimated++;
        estimatedCost += usage.costUsd();
      }
      for (Map.Entry<LocalDate, Double> day : usage.costByDay().entrySet()) {
        byDay.merge(day.getKey(), day.getValue(), Double::sum);
      }
      for (SessionUsage.ModelUsage model : usage.models()) {
        byModel.computeIfAbsent(model.model(), name -> new ModelTotals()).add(model);
      }
    }

    List<PageMessage.ModelCost> models = new ArrayList<>();
    for (Map.Entry<String, ModelTotals> entry : byModel.entrySet()) {
      ModelTotals totals = entry.getValue();
      models.add(new PageMessage.ModelCost(entry.getKey(), totals.input, totals.output, totals.cacheRead,
        totals.cacheWrite, totals.cost));
    }
    List<PageMessage.DayCost> days = new ArrayList<>();
    for (Map.Entry<LocalDate, Double> entry : byDay.entrySet()) {
      days.add(new PageMessage.DayCost(entry.getKey().toString(), entry.getValue()));
    }
    return new PageMessage.Usage(totalCost, totalTokens, all.size(), estimated, estimatedCost,
      SessionStore.retentionDays(environment), byDay.isEmpty() ? null : byDay.firstKey().toString(), models, days,
      projects, topSessions(all, threads));
  }

  private static List<PageMessage.SessionCost> topSessions(List<FolderSession> all, ThreadStore threads) {
    List<FolderSession> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparingDouble((FolderSession session) -> session.usage().costUsd()).reversed());
    List<PageMessage.SessionCost> top = new ArrayList<>();
    for (FolderSession session : sorted.subList(0, Math.min(TOP_SESSIONS, sorted.size()))) {
      SessionUsage usage = session.usage();
      ThreadStore.Record thread = threads.findBySession(usage.sessionId());
      String title = thread != null ? thread.title : session.sessions().title(usage.sessionId());
      top.add(new PageMessage.SessionCost(usage.sessionId(), session.folder().path, session.folder().name,
        title != null ? title : usage.sessionId(), usage.costUsd(), usage.totalTokens(),
        thread != null ? thread.id : null, thread != null && thread.archived, usage.estimated()));
    }
    return top;
  }

  private record FolderSession(
    ThreadStore.ProjectFolder folder,
    SessionUsage usage,
    SessionStore sessions
  ) {
  }

  private static final class ModelTotals {
    long input;
    long output;
    long cacheRead;
    long cacheWrite;
    double cost;

    void add(SessionUsage.ModelUsage model) {
      this.input += model.inputTokens();
      this.output += model.outputTokens();
      this.cacheRead += model.cacheReadTokens();
      this.cacheWrite += model.cacheWriteTokens();
      this.cost += model.costUsd();
    }
  }
}
