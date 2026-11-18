package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.agent.SetupProblem;
import org.jetbrains.annotations.Nullable;

/** @param problem what keeps the agent from starting, null when nothing Tako can see does */
public record AgentOption(
  String id,
  String name,
  @Nullable SetupProblem problem
) {
  public AgentOption(String id, String name) {
    this(id, name, null);
  }
}
