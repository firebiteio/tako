package io.firebite.tako.ui.state

import io.firebite.tako.ui.bridge.message.workspace.AgentOption

/**
 * Whether the empty thread offers the agents to pick the one of new threads from: until the person picked one, while
 * at least one is ready.
 */
val ThreadState.offersAgentPick: Boolean
  get() = !this.workspace.agentPicked && this.ownTurns.isEmpty() && this.canSwitchAgent
    && this.workspace.agents.any { it.problem == null }

/**
 * The agent of an empty thread while something keeps it from starting; null once it can, the thread has turns or it
 * offers the agents to pick from instead.
 */
val ThreadState.agentToSetUp: AgentOption?
  get() = if (this.ownTurns.isEmpty() && !this.offersAgentPick) {
    this.workspace.agentOf(this.agent)?.takeIf { it.problem != null }
  } else null

/** The agents the thread could switch to instead, which nothing keeps from starting. */
val ThreadState.readyAgents: List<AgentOption>
  get() = if (this.canSwitchAgent) this.workspace.agents.filter { it.id != this.agent && it.problem == null }
  else emptyList()
