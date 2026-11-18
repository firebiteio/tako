package io.firebite.tako.agent;

/** What keeps an agent from starting, as far as Tako can tell without starting it. */
public enum SetupProblem {
  /** The agent's program is not found. */
  NOT_INSTALLED,
  /** Tako holds the agent's login, and none is set. */
  NO_API_KEY
}
