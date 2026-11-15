package io.firebite.tako.agent.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.ExecutableLocator;
import io.firebite.tako.agent.rpc.JsonRpcConnection;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Any agent speaking the Agent Client Protocol, started by a command line, such as Gemini CLI
 * ({@code gemini --acp}), OpenCode ({@code opencode acp}), or Claude Code and Codex through their ACP adapters.
 */
public final class AcpBackend implements AgentBackend {
  public static final String ID_PREFIX = "acp:";
  private static final Logger LOG = Logger.getInstance(AcpBackend.class);
  private static final long LIST_TIMEOUT_SECONDS = 15;

  private final String name;
  private final List<String> commandLine;
  private final Map<String, String> environment;

  /** @param commandLine the executable, found on PATH unless it is a path, and its arguments */
  public AcpBackend(String name, List<String> commandLine, Map<String, String> environment) {
    this.name = name;
    this.commandLine = List.copyOf(commandLine);
    this.environment = environment;
  }

  /** Splits a command line at spaces, keeping double-quoted parts together. */
  public static List<String> split(String commandLine) {
    List<String> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    boolean isQuoted = false;
    for (char c : commandLine.toCharArray()) {
      if (c == '"') {
        isQuoted = !isQuoted;
      } else if (Character.isWhitespace(c) && !isQuoted) {
        if (!part.isEmpty()) {
          parts.add(part.toString());
          part.setLength(0);
        }
      } else {
        part.append(c);
      }
    }
    if (!part.isEmpty()) {
      parts.add(part.toString());
    }
    return parts;
  }

  @Override
  public String id() {
    return ID_PREFIX + this.name;
  }

  @Override
  public String displayName() {
    return this.name;
  }

  @Override
  public AgentSession start(AgentLaunch launch, AgentListener listener) throws AgentException {
    try {
      return new AcpSession(this.name, this.command(launch.environment()), launch, listener);
    } catch (IOException e) {
      throw new AgentException(TakoBundle.message("error.agent.start", this.name, e.getMessage()), e);
    }
  }

  @Override
  public AgentHistory history(String projectPath, String sessionId) {
    return new AgentHistory.FromSession();
  }

  /** Asks a short-lived agent for its sessions in the folder, when it can list them. */
  @Override
  public List<AgentSessionInfo> sessions(String projectPath, int limit) {
    List<AgentSessionInfo> sessions = new ArrayList<>();
    try (JsonRpcConnection connection = JsonRpcConnection.start(this.command(this.environment), Path.of(projectPath),
      this.environment, true, JsonRpcConnection.Handler.NONE)) {
      JsonObject params = new JsonObject();
      params.addProperty("protocolVersion", AcpSession.PROTOCOL_VERSION);
      JsonElement initialized = connection.await("initialize", params, LIST_TIMEOUT_SECONDS);
      JsonObject capabilities = Json.object(Json.object(Json.asObject(initialized), "agentCapabilities"),
        "sessionCapabilities");
      if (Json.object(capabilities, "list") == null) {
        return sessions;
      }
      JsonElement listed = connection.await("session/list", Json.of("cwd", projectPath), LIST_TIMEOUT_SECONDS);
      for (JsonObject session : Json.objects(Json.asObject(listed), "sessions")) {
        String title = Json.string(session, "title");
        if (title != null && !title.isBlank() && sessions.size() < limit) {
          sessions.add(new AgentSessionInfo(String.valueOf(Json.string(session, "sessionId")), title,
            millis(Json.string(session, "updatedAt"))));
        }
      }
    } catch (Exception e) {
      LOG.info("Could not list the sessions of " + this.name, e);
    }
    return sessions;
  }

  /** The command's program is found; the agent checks its own login when it starts. */
  @Override
  public boolean canStart() {
    try {
      this.command(this.environment);
      return true;
    } catch (AgentException e) {
      return false;
    }
  }

  private List<String> command(Map<String, String> environment) throws AgentException {
    if (this.commandLine.isEmpty()) {
      throw new AgentException(TakoBundle.message("error.acp.no.command", this.name));
    }
    String program = this.commandLine.getFirst();
    String executable = Files.isRegularFile(Path.of(program))
      ? program
      : ExecutableLocator.locate("", program, List.of(), environment, SystemInfo.isWindows);
    if (executable == null) {
      throw new AgentException(TakoBundle.message("error.acp.not.found", program, this.name));
    }
    List<String> command = new ArrayList<>(this.commandLine);
    command.set(0, executable);
    return command;
  }

  private static long millis(String timestamp) {
    try {
      return timestamp == null ? 0 : Instant.parse(timestamp).toEpochMilli();
    } catch (DateTimeException e) {
      return 0;
    }
  }
}
