package io.firebite.tako.agent;

/** The model, mode and effort an agent starts with when the thread chose none. Blank keeps the agent's own. */
public record AgentDefaults(
  String model,
  String mode,
  String effort
) {
  public static final AgentDefaults NONE = new AgentDefaults("", "", "");
}
