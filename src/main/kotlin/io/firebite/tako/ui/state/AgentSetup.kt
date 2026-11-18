package io.firebite.tako.ui.state

import io.firebite.tako.ui.bridge.message.workspace.AgentOption

/** The agent of an empty thread while something keeps it from starting; null once it can or the thread has turns. */
val ThreadState.agentToSetUp: AgentOption?
  get() = if (this.ownTurns.isEmpty()) this.workspace.agentOf(this.agent)?.takeIf { it.problem != null } else null

/** The agents the thread could switch to instead, which nothing keeps from starting. */
val ThreadState.readyAgents: List<AgentOption>
  get() = if (this.canSwitchAgent) this.workspace.agents.filter { it.id != this.agent && it.problem == null }
  else emptyList()
