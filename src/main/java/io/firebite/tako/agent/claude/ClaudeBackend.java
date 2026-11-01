package io.firebite.tako.agent.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.ExecutableLocator;
import io.firebite.tako.agent.Processes;
import io.firebite.tako.agent.PromptText;
import io.firebite.tako.agent.SessionCost;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.agent.claude.transcript.SessionUsage;
import io.firebite.tako.json.Json;
import io.firebite.tako.settings.ApiKeyStore;
import io.firebite.tako.settings.AuthMode;
import io.firebite.tako.settings.ChatSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** Runs Claude Code, the {@code claude} CLI, in stream-json mode. */
public final class ClaudeBackend implements AgentBackend {
  public static final String ID = "claude";
  public static final String NAME = "Claude Code";
  private static final Logger LOG = Logger.getInstance(ClaudeBackend.class);
  private static final AgentInfo.Capabilities CAPABILITIES = new AgentInfo.Capabilities(true, true, true, true, true,
    true);
  private static final long ASK_TIMEOUT_SECONDS = 60;

  private final Map<String, String> environment;

  public ClaudeBackend(Map<String, String> environment) {
    this.environment = environment;
  }

  /** Values of {@code --permission-mode}. */
  public static List<AgentInfo.Mode> modes() {
    return List.of(
      new AgentInfo.Mode("default", TakoBundle.message("mode.claude.default"),
        TakoBundle.message("mode.claude.default.description")),
      new AgentInfo.Mode("acceptEdits", TakoBundle.message("mode.claude.accept.edits"),
        TakoBundle.message("mode.claude.accept.edits.description")),
      new AgentInfo.Mode("plan", TakoBundle.message("mode.claude.plan"),
        TakoBundle.message("mode.claude.plan.description")),
      new AgentInfo.Mode("auto", TakoBundle.message("mode.claude.auto"),
        TakoBundle.message("mode.claude.auto.description")),
      new AgentInfo.Mode("dontAsk", TakoBundle.message("mode.claude.dont.ask"),
        TakoBundle.message("mode.claude.dont.ask.description")),
      new AgentInfo.Mode("bypassPermissions", TakoBundle.message("mode.claude.full.access"),
        TakoBundle.message("mode.claude.full.access.description")));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return NAME;
  }

  @Override
  public AgentSession start(AgentLaunch threadLaunch, AgentListener listener) throws AgentException {
    ChatSettings.Data settings = ChatSettings.state();
    AgentLaunch launch = threadLaunch.withDefaults(settings.claudeDefaults());
    String executable = executable(settings, launch.environment());
    String apiKey = settings.authMode == AuthMode.API_KEY ? ApiKeyStore.load() : null;
    ClaudeLaunch command;
    try {
      command = ClaudeLaunch.build(executable, settings.authMode, apiKey,
        new ClaudeLaunch.Options(launch.model(), launch.mode(), launch.effort(), launch.resumeSessionId(),
          launch.additionalDirectories(), toolsConfig(launch.toolsUrl())),
        launch.environment());
    } catch (IllegalStateException e) {
      throw new AgentException(e.getMessage(), e);
    } catch (IOException e) {
      throw new AgentException(TakoBundle.message("error.agent.start", executable, e.getMessage()), e);
    }
    String authDescription = settings.authMode == AuthMode.API_KEY
      ? TakoBundle.message("auth.claude.api.key")
      : TakoBundle.message("auth.claude.cli.login");
    try {
      return new ClaudeSession(command, launch, authDescription, this.environment, listener);
    } catch (IOException e) {
      throw new AgentException(TakoBundle.message("error.agent.start", executable, e.getMessage()), e);
    }
  }

  @Override
  public AgentHistory history(String projectPath, String sessionId) {
    try {
      List<AgentEvent> events = new ArrayList<>();
      for (SessionStore.TranscriptMessage message : SessionStore.forProject(this.environment, projectPath)
        .load(sessionId)) {
        events.addAll(ClaudeTranslator.translate(message.message(), message.change(), true));
      }
      return new AgentHistory.Loaded(events);
    } catch (IOException e) {
      LOG.info("Session " + sessionId + " not found, starting fresh", e);
      return new AgentHistory.Missing();
    }
  }

  @Override
  public List<AgentSessionInfo> sessions(String projectPath, int limit) {
    List<AgentSessionInfo> sessions = new ArrayList<>();
    try {
      for (SessionStore.SessionInfo session : SessionStore.forProject(this.environment, projectPath).list(limit)) {
        sessions.add(new AgentSessionInfo(session.id(), session.title(), session.lastModified()));
      }
    } catch (IOException e) {
      LOG.warn("Could not list sessions of " + projectPath, e);
    }
    return sessions;
  }

  @Override
  public @Nullable SessionCost sessionCost(String projectPath, String sessionId) {
    try {
      SessionUsage usage = SessionStore.forProject(this.environment, projectPath).usage(sessionId);
      return usage == null ? null : usage.toCost();
    } catch (IOException e) {
      LOG.debug("No usage for session " + sessionId, e);
      return null;
    }
  }

  /** Copies the transcript up to the prompt after the first {@code prompts}, counted as {@link #history} shows them. */
  @Override
  public String fork(String projectPath, String sessionId, int prompts) throws AgentException {
    try {
      return SessionStore.forProject(this.environment, projectPath).fork(sessionId, new PromptCut(prompts));
    } catch (IOException e) {
      throw new AgentException(TakoBundle.message("error.fork", e.getMessage()), e);
    }
  }

  /**
   * Checks the transcript's prompts, then copies it up to the prompt to forget into a new session, like {@link #fork}.
   * The original stays as it is.
   */
  @Override
  public String rewind(String projectPath, String sessionId, List<String> prompts) throws AgentException {
    SessionStore store = SessionStore.forProject(this.environment, projectPath);
    try {
      List<String> sessionPrompts = new ArrayList<>();
      for (SessionStore.TranscriptMessage message : store.load(sessionId)) {
        for (AgentEvent event : ClaudeTranslator.translate(message.message(), message.change(), true)) {
          if (event instanceof AgentEvent.Prompt prompt) {
            sessionPrompts.add(prompt.text());
          }
        }
      }
      if (!PromptText.startsWith(sessionPrompts, prompts)) {
        throw new AgentException(TakoBundle.message("error.rewind.mismatch"));
      }
      return store.fork(sessionId, new PromptCut(prompts.size() - 1));
    } catch (IOException e) {
      throw new AgentException(TakoBundle.message("error.rewind", e.getMessage()), e);
    }
  }

  /** Parses only the transcript lines that contain the query, then checks their prompt or answer text. */
  @Override
  public boolean sessionMentions(String projectPath, String sessionId, String query) {
    String needle = query.toLowerCase(Locale.ROOT);
    try {
      for (SessionStore.TranscriptMessage message : SessionStore.forProject(this.environment, projectPath)
        .loadMentioning(sessionId, needle)) {
        for (AgentEvent event : ClaudeTranslator.translate(message.message(), message.change(), true)) {
          String text = switch (event) {
            case AgentEvent.Prompt prompt -> prompt.text();
            case AgentEvent.Text answer -> answer.parentId() == null ? answer.text() : null;
            default -> null;
          };
          if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
          }
        }
      }
    } catch (IOException e) {
      LOG.debug("Could not search session " + sessionId, e);
    }
    return false;
  }

  @Override
  public boolean hasStoredHistory() {
    return true;
  }

  /** The CLI is found and, in API key mode, a key is set. */
  @Override
  public boolean canAsk() {
    ChatSettings.Data settings = ChatSettings.state();
    if (locate(settings, this.environment) == null) {
      return false;
    }
    if (settings.authMode != AuthMode.API_KEY) {
      return true;
    }
    String apiKey = ApiKeyStore.load();
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public String ask(Path workDirectory, String prompt) throws AgentException {
    ChatSettings.Data settings = ChatSettings.state();
    String executable = executable(settings, this.environment);
    try {
      ClaudeLaunch command = ClaudeLaunch.ask(executable, settings.authMode,
        settings.authMode == AuthMode.API_KEY ? ApiKeyStore.load() : null, askSettings(), this.environment);
      return Processes.run(command.command(), workDirectory, command.environment(), prompt, ASK_TIMEOUT_SECONDS);
    } catch (IllegalStateException | IOException e) {
      throw new AgentException(e.getMessage(), e);
    }
  }

  /**
   * Writes settings that turn off hooks, so a one-shot call runs none of the user's, project's or plugins' hooks.
   * {@code --bare} would too, but it also stops reading the CLI login.
   */
  private static String askSettings() throws IOException {
    Path file = Path.of(System.getProperty("java.io.tmpdir"), "tako-ask-settings.json");
    if (!Files.exists(file)) {
      file.toFile().deleteOnExit();
    }
    JsonObject settings = new JsonObject();
    settings.addProperty("disableAllHooks", true);
    Files.writeString(file, settings.toString(), StandardCharsets.UTF_8);
    return file.toString();
  }

  private static String executable(ChatSettings.Data settings, Map<String, String> environment)
    throws AgentException {
    String executable = locate(settings, environment);
    if (executable == null) {
      throw new AgentException(TakoBundle.message("error.claude.not.found"));
    }
    return executable;
  }

  private static @Nullable String locate(ChatSettings.Data settings, Map<String, String> environment) {
    String home = System.getProperty("user.home");
    return ExecutableLocator.locate(settings.claudePath, "claude", List.of(Path.of(home, ".claude", "local")),
      environment, SystemInfo.isWindows);
  }

  /**
   * Writes Tako's tools to an MCP config file, one per URL, so relaunches reuse it. Inline JSON would not survive a
   * Windows command line.
   */
  private static @Nullable String toolsConfig(@Nullable String toolsUrl) throws IOException {
    if (toolsUrl == null) {
      return null;
    }
    JsonObject server = Json.of("type", "http");
    server.addProperty("url", toolsUrl);
    JsonObject servers = new JsonObject();
    servers.add(AgentLaunch.TOOLS_SERVER_NAME, server);
    JsonObject config = new JsonObject();
    config.add("mcpServers", servers);
    Path file = Path.of(System.getProperty("java.io.tmpdir"),
      "tako-mcp-" + Integer.toHexString(toolsUrl.hashCode()) + ".json");
    if (!Files.exists(file)) {
      file.toFile().deleteOnExit();
    }
    Files.writeString(file, config.toString(), StandardCharsets.UTF_8);
    return file.toString();
  }

  /** What the CLI's {@code initialize} answer offers. */
  static AgentInfo info(JsonObject initialized, String requestedModel, @Nullable String effort,
    String authDescription) {
    List<AgentInfo.Command> commands = new ArrayList<>();
    for (JsonObject command : Json.objects(initialized, "commands")) {
      commands.add(new AgentInfo.Command(String.valueOf(Json.string(command, "name")), orEmpty(Json.string(command,
        "description")), orEmpty(Json.string(command, "argumentHint"))));
    }
    List<AgentInfo.Model> models = new ArrayList<>();
    for (JsonObject model : Json.objects(initialized, "models")) {
      String value = String.valueOf(Json.string(model, "value"));
      String displayName = Json.string(model, "displayName");
      List<String> efforts = new ArrayList<>();
      JsonArray levels = Json.array(model, "supportedEffortLevels");
      if (Json.bool(model, "supportsEffort") && levels != null) {
        for (JsonElement level : levels) {
          efforts.add(level.getAsString());
        }
      }
      models.add(new AgentInfo.Model(value, displayName != null && !displayName.isEmpty() ? displayName : value,
        Json.string(model, "description"), Json.string(model, "resolvedModel"), efforts));
    }
    return new AgentInfo(ID, NAME, authDescription, CAPABILITIES, models, requestedModel, modes(),
      Json.string(initialized, "current_permission_mode"), effort, commands);
  }

  private static String orEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }

  /** Accepts the first message past the first {@code prompts} prompts, counted as {@link #history} shows them. */
  private static final class PromptCut implements Predicate<SessionStore.TranscriptMessage> {
    private final int prompts;
    private int seen;

    private PromptCut(int prompts) {
      this.prompts = prompts;
    }

    @Override
    public boolean test(SessionStore.TranscriptMessage message) {
      for (AgentEvent event : ClaudeTranslator.translate(message.message(), message.change(), true)) {
        if (event instanceof AgentEvent.Prompt && ++this.seen > this.prompts) {
          return true;
        }
      }
      return false;
    }
  }
}
