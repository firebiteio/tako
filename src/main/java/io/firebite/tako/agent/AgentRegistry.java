package io.firebite.tako.agent;

import io.firebite.tako.agent.acp.AcpBackend;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.agent.codex.CodexBackend;
import io.firebite.tako.settings.ChatSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** The agents the settings offer, which are Claude Code, Codex and each configured ACP agent. */
public final class AgentRegistry {
  private AgentRegistry() {
  }

  /** Every agent, the default one first. */
  public static List<AgentBackend> backends(Map<String, String> environment) {
    ChatSettings.Data settings = ChatSettings.state();
    List<AgentBackend> backends = new ArrayList<>();
    backends.add(new ClaudeBackend(environment));
    backends.add(new CodexBackend(settings.codexPath, settings.codexDefaults(), environment));
    for (AcpAgent agent : acpAgents(settings.acpAgents)) {
      backends.add(new AcpBackend(agent.name(), AcpBackend.split(agent.command()), environment));
    }
    for (int i = 0; i < backends.size(); i++) {
      if (backends.get(i).id().equals(settings.defaultAgent)) {
        backends.addFirst(backends.remove(i));
        break;
      }
    }
    return backends;
  }

  /** The agent of that id, or the default one when it is unknown, e.g. after its settings line was removed. */
  public static AgentBackend find(@Nullable String id, Map<String, String> environment) {
    List<AgentBackend> backends = backends(environment);
    for (AgentBackend backend : backends) {
      if (backend.id().equals(id)) {
        return backend;
      }
    }
    return backends.getFirst();
  }

  /** The default agent's id. */
  public static String defaultId(Map<String, String> environment) {
    return backends(environment).getFirst().id();
  }

  /** The agents to choose from, by id and name, as the settings would offer them with those ACP agents. */
  public static List<Choice> choices(String acpAgents) {
    List<Choice> choices = new ArrayList<>();
    choices.add(new Choice(ClaudeBackend.ID, ClaudeBackend.NAME));
    choices.add(new Choice(CodexBackend.ID, CodexBackend.NAME));
    for (AcpAgent agent : acpAgents(acpAgents)) {
      choices.add(new Choice(AcpBackend.ID_PREFIX + agent.name(), agent.name()));
    }
    return choices;
  }

  /** {@code Name = command line}, one per line. Skips blank lines and lines without {@code =}. */
  public static List<AcpAgent> acpAgents(String lines) {
    List<AcpAgent> agents = new ArrayList<>();
    for (String line : lines.split("\\R")) {
      int separator = line.indexOf('=');
      if (separator > 0 && !line.substring(separator + 1).isBlank()) {
        agents.add(new AcpAgent(line.substring(0, separator).trim(), line.substring(separator + 1).trim()));
      }
    }
    return agents;
  }

  public record AcpAgent(
    String name,
    String command
  ) {
  }

  public record Choice(
    String id,
    String name
  ) {
  }
}
