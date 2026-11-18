package io.firebite.tako.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.annotations.OptionTag;
import io.firebite.tako.agent.AgentDefaults;
import org.jetbrains.annotations.NotNull;

/**
 * The plugin's settings, which say what agent new threads run and how each agent starts. Blank values keep its
 * defaults.
 */
@Service(Service.Level.APP)
@State(name = "TakoSettings", storages = @Storage("tako.xml"))
public final class ChatSettings implements PersistentStateComponent<ChatSettings.Data> {
  public static final int DEFAULT_IDLE_DAYS = 7;
  public static final int MIN_IDLE_DAYS = 1;
  public static final int MAX_IDLE_DAYS = 365;

  private Data data = new Data();

  public static ChatSettings getInstance() {
    return ApplicationManager.getApplication().getService(ChatSettings.class);
  }

  public static Data state() {
    return getInstance().getState();
  }

  @Override
  public @NotNull Data getState() {
    return this.data;
  }

  @Override
  public void loadState(@NotNull Data state) {
    this.data = state;
  }

  /** Option names predate the other agents, so Claude Code's keep theirs. */
  public static final class Data {
    /** The id of the agent new threads run. */
    public String defaultAgent = "claude";
    /** The person picked {@link #defaultAgent}, so empty threads stop offering the agents to pick from. */
    public boolean defaultAgentPicked;
    /** Lets one prompt start several runs at once, each in its own git worktree. */
    public boolean parallelRuns;
    /** A prompt sent while the agent works steers its running turn instead of waiting for it to end. */
    public boolean steerWhileWorking;
    /** Archives a thread in a worktree of its own once its pull request is merged or closed. */
    public boolean settleOnPullRequest;
    /** Archives a thread after {@link #settleIdleDays} without activity. */
    public boolean settleIdle;
    public int settleIdleDays = DEFAULT_IDLE_DAYS;
    /** Free text on which agent or model suits which work, for agents that hand tasks to other threads. */
    public String delegationNotes = "";
    /** What {@code /orchestrate} tells the agent. Blank keeps the built in instructions. */
    public String orchestrationInstructions = "";

    @OptionTag("executablePath")
    public String claudePath = "";
    public AuthMode authMode = AuthMode.API_KEY;
    @OptionTag("model")
    public String claudeModel = "";
    @OptionTag("permissionMode")
    public String claudeMode = "";
    @OptionTag("effort")
    public String claudeEffort = "";

    public String codexPath = "";
    public String codexModel = "";
    public String codexMode = "";
    public String codexEffort = "";

    /** ACP agents, one {@code Name = command line} per line. */
    public String acpAgents = "Gemini CLI = gemini --acp";

    /** The idle days within the allowed range, as a hand-edited file may hold any number. */
    public int idleDays() {
      return Math.clamp(this.settleIdleDays, MIN_IDLE_DAYS, MAX_IDLE_DAYS);
    }

    public AgentDefaults claudeDefaults() {
      return new AgentDefaults(this.claudeModel, this.claudeMode, this.claudeEffort);
    }

    public AgentDefaults codexDefaults() {
      return new AgentDefaults(this.codexModel, this.codexMode, this.codexEffort);
    }
  }
}
