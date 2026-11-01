package io.firebite.tako.agent.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentDefaults;
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
import io.firebite.tako.agent.rpc.JsonRpcConnection;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** OpenAI Codex through {@code codex app-server}. It signs in with the Codex CLI's own login. */
public final class CodexBackend implements AgentBackend {
  public static final String ID = "codex";
  public static final String NAME = "Codex";
  /** Reasoning efforts Codex's models take. Each model lists the ones it supports. */
  public static final List<String> EFFORTS = List.of("minimal", "low", "medium", "high", "xhigh");
  static final AgentInfo.Capabilities CAPABILITIES = new AgentInfo.Capabilities(true, false, false, false, true, true,
    true);
  private static final Logger LOG = Logger.getInstance(CodexBackend.class);
  private static final long LIST_TIMEOUT_SECONDS = 15;
  private static final long ASK_TIMEOUT_SECONDS = 90;
  private static final int TURN_PAGE = 100;
  private static final String THREAD_ID = "threadId";
  private static final String ASK_MODEL = "gpt-5.4-mini";
  private static final List<String> ASK_DISABLED_FEATURES = List.of("shell_tool", "unified_exec", "view_image",
    "apps", "plugins", "multi_agent", "hooks");

  private final String configuredPath;
  private final AgentDefaults defaults;
  private final Map<String, String> environment;

  public CodexBackend(String configuredPath, AgentDefaults defaults, Map<String, String> environment) {
    this.configuredPath = configuredPath;
    this.defaults = defaults;
    this.environment = environment;
  }

  /** The approval and sandbox modes a thread can run in. */
  public static List<AgentInfo.Mode> modes() {
    return CodexModes.options();
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
  public AgentSession start(AgentLaunch launch, AgentListener listener) throws AgentException {
    try {
      return new CodexSession(this.command(launch.environment()), launch.withDefaults(this.defaults), listener);
    } catch (IOException e) {
      throw new AgentException(TakoBundle.message("error.agent.start", NAME, e.getMessage()), e);
    }
  }

  @Override
  public AgentHistory history(String projectPath, String sessionId) {
    return new AgentHistory.FromSession();
  }

  /** Asks a short-lived app-server for the threads started in the folder. */
  @Override
  public List<AgentSessionInfo> sessions(String projectPath, int limit) {
    List<AgentSessionInfo> sessions = new ArrayList<>();
    try (JsonRpcConnection connection = JsonRpcConnection.start(this.command(this.environment), Path.of(projectPath),
      this.environment, false, JsonRpcConnection.Handler.NONE)) {
      JsonObject initialize = new JsonObject();
      initialize.add("clientInfo", JsonRpcConnection.clientInfo());
      connection.await("initialize", initialize, LIST_TIMEOUT_SECONDS);
      connection.notify("initialized", null);
      JsonObject params = Json.of("cwd", projectPath);
      params.addProperty("limit", limit);
      JsonElement listed = connection.await("thread/list", params, LIST_TIMEOUT_SECONDS);
      for (JsonObject thread : Json.objects(listed.isJsonObject() ? listed.getAsJsonObject() : null, "data")) {
        String name = Json.string(thread, "name");
        String preview = Json.string(thread, "preview");
        String title = name != null && !name.isBlank() ? name : preview != null && !preview.isBlank() ? preview : null;
        if (title != null) {
          sessions.add(new AgentSessionInfo(String.valueOf(Json.string(thread, "id")), AgentSessionInfo.titleOf(title),
            Json.number(thread, "updatedAt") * 1000));
        }
      }
    } catch (Exception e) {
      LOG.info("Could not list Codex threads of " + projectPath, e);
    }
    return sessions;
  }

  /**
   * Asks a short-lived app-server to revert the thread to before the turn of the first prompt to forget, with
   * {@code thread/revert}. Needs a Codex whose threads keep paginated history.
   */
  @Override
  public String rewind(String projectPath, String sessionId, List<String> prompts) throws AgentException {
    try (JsonRpcConnection connection = JsonRpcConnection.start(this.command(this.environment), Path.of(projectPath),
      this.environment, false, JsonRpcConnection.Handler.NONE)) {
      JsonObject initialize = new JsonObject();
      initialize.add("clientInfo", JsonRpcConnection.clientInfo());
      connection.await("initialize", initialize, LIST_TIMEOUT_SECONDS);
      connection.notify("initialized", null);
      JsonObject resume = Json.of(THREAD_ID, sessionId);
      resume.addProperty("cwd", projectPath);
      resume.addProperty("excludeTurns", true);
      connection.await("thread/resume", resume, LIST_TIMEOUT_SECONDS);
      String cut = cutTurn(turns(connection, sessionId), prompts);
      JsonObject revert = Json.of(THREAD_ID, sessionId);
      revert.addProperty("beforeTurnId", cut);
      connection.await("thread/revert", revert, LIST_TIMEOUT_SECONDS);
      return sessionId;
    } catch (AgentException e) {
      throw e;
    } catch (Exception e) {
      throw new AgentException(TakoBundle.message("error.rewind.codex", AgentException.messageOf(e)), e);
    }
  }

  /** Every turn of the thread, oldest first. */
  private static List<JsonObject> turns(JsonRpcConnection connection, String threadId) throws Exception {
    List<JsonObject> turns = new ArrayList<>();
    Set<String> cursors = new HashSet<>();
    String cursor = null;
    do {
      JsonObject params = Json.of(THREAD_ID, threadId);
      params.addProperty("limit", TURN_PAGE);
      params.addProperty("sortDirection", "asc");
      params.addProperty("itemsView", "full");
      if (cursor != null) {
        params.addProperty("cursor", cursor);
      }
      JsonElement page = connection.await("thread/turns/list", params, LIST_TIMEOUT_SECONDS);
      JsonObject result = page.isJsonObject() ? page.getAsJsonObject() : null;
      turns.addAll(Json.objects(result, "data"));
      cursor = Json.string(result, "nextCursor");
    } while (cursor != null && !cursor.isEmpty() && cursors.add(cursor));
    return turns;
  }

  /**
   * The id of the turn whose user message is the last of {@code prompts}, matching user messages to {@code prompts} in
   * order. A message without text, e.g. only an image, matches an empty prompt the chat sent live and is skipped
   * otherwise, since a replayed thread does not show it. The cut message must open its turn, since a revert drops
   * whole turns.
   *
   * @throws AgentException when the thread's prompts do not start with {@code prompts}
   */
  static String cutTurn(List<JsonObject> turns, List<String> prompts) throws AgentException {
    if (prompts.isEmpty()) {
      throw new AgentException(TakoBundle.message("error.rewind.mismatch"));
    }
    int matched = 0;
    for (JsonObject turn : turns) {
      boolean opensTurn = true;
      for (JsonObject item : Json.objects(turn, "items")) {
        if (!"userMessage".equals(Json.string(item, "type"))) {
          continue;
        }
        AgentEvent.Prompt prompt = CodexTranslator.prompt(item);
        String expected = prompts.get(matched);
        if (prompt == null && !PromptText.normalize(expected).isEmpty()) {
          opensTurn = false;
          continue;
        }
        if (!PromptText.same(prompt == null ? "" : prompt.text(), expected)) {
          throw new AgentException(TakoBundle.message("error.rewind.mismatch"));
        }
        matched++;
        if (matched == prompts.size()) {
          String id = Json.string(turn, "id");
          if (!opensTurn || id == null) {
            throw new AgentException(TakoBundle.message("error.rewind.mismatch"));
          }
          return id;
        }
        opensTurn = false;
      }
    }
    throw new AgentException(TakoBundle.message("error.rewind.mismatch"));
  }

  /** The CLI is found. */
  @Override
  public boolean canAsk() {
    return this.locate(this.environment) != null;
  }

  /** The CLI is found; Codex checks its own login when it starts. */
  @Override
  public boolean canStart() {
    return this.canAsk();
  }

  @Override
  public String ask(Path workDirectory, String prompt) throws AgentException {
    try {
      return Processes.run(askCommand(this.executable(this.environment)), workDirectory, this.environment, prompt,
        ASK_TIMEOUT_SECONDS);
    } catch (IOException e) {
      throw new AgentException(e.getMessage(), e);
    }
  }

  /**
   * {@code codex exec} with a small model at low effort, read-only, without saving the session. It skips the user's
   * config.toml, so no MCP servers start, and turns off shell, web search, images, apps, plugins, subagents and hooks.
   * It prints only the answer.
   */
  static List<String> askCommand(String executable) {
    List<String> command = new ArrayList<>(List.of(executable, "exec", "--ephemeral", "--skip-git-repo-check",
      "--ignore-user-config", "--ignore-rules", "--sandbox", "read-only", "--color", "never", "-m", ASK_MODEL,
      "-c", "model_reasoning_effort=low", "-c", "web_search=disabled"));
    for (String feature : ASK_DISABLED_FEATURES) {
      command.add("-c");
      command.add("features." + feature + "=false");
    }
    command.add("-");
    return command;
  }

  private List<String> command(Map<String, String> environment) throws AgentException {
    return List.of(this.executable(environment), "app-server");
  }

  private String executable(Map<String, String> environment) throws AgentException {
    String executable = this.locate(environment);
    if (executable == null) {
      throw new AgentException(TakoBundle.message("error.codex.not.found"));
    }
    return executable;
  }

  private @Nullable String locate(Map<String, String> environment) {
    return ExecutableLocator.locate(this.configuredPath, "codex", List.of(), environment, SystemInfo.isWindows);
  }

}
