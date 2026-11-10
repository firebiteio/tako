package io.firebite.tako.chat;

import io.firebite.tako.agent.AgentInfo;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/** What each agent offered when one of its threads last started, so {@link ThreadTools} can name its models. */
final class AgentCatalog {
  private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();

  void remember(AgentInfo info) {
    this.agents.put(info.agent(), info);
  }

  /** @param agent the backend id */
  @Nullable
  AgentInfo find(String agent) {
    return this.agents.get(agent);
  }
}
