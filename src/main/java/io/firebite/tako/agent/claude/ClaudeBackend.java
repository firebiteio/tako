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
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Runs Claude Code, the {@code claude} CLI, in stream-json mode. */
public final class ClaudeBackend implements AgentBackend {
  public static final String ID = "claude";
  public static final String NAME = "Claude Code";
  private static final Logger LOG = Logger.getInstance(ClaudeBackend.class);
  private static final AgentInfo.Capabilities CAPABILITIES = new AgentInfo.Capabilities(true, true);

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
    String home = System.getProperty("user.home");
    String executable = ExecutableLocator.locate(settings.claudePath, "claude",
      List.of(Path.of(home, ".claude", "local")), launch.environment(), SystemInfo.isWindows);
    if (executable == null) {
      throw new AgentException(TakoBundle.message("error.claude.not.found"));
    }
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
}
