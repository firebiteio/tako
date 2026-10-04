package io.firebite.tako.agent.claude;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.ContextUsage;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.PermissionAnswer;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.agent.claude.protocol.FileChange;
import io.firebite.tako.agent.claude.protocol.TaskEvent;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/** One {@code claude} process serving one thread. */
final class ClaudeSession implements AgentSession, ClaudeProcessListener {
  private static final long SETTINGS_TIMEOUT_SECONDS = 5;
  private static final List<String> TEMP_VARIABLES = List.of("CLAUDE_CODE_TMPDIR", "TMPDIR", "TEMP", "TMP");

  private final ClaudeProcess process;
  private final AgentLaunch launch;
  private final String authDescription;
  private final Map<String, String> environment;
  private final AgentListener listener;
  private final SubagentRelay subagents;
  private final Map<String, JsonObject> pendingRequests = new ConcurrentHashMap<>();
  private volatile @Nullable String sessionId;

  ClaudeSession(ClaudeLaunch command, AgentLaunch launch, String authDescription, Map<String, String> environment,
    AgentListener listener) throws IOException {
    this.launch = launch;
    this.authDescription = authDescription;
    this.environment = environment;
    this.listener = listener;
    this.sessionId = launch.resumeSessionId();
    this.subagents = new SubagentRelay(this::subagentTranscript, this::emit);
    this.process = ClaudeProcess.start(command, launch.workDirectory(), this);
  }

  @Override
  public CompletableFuture<AgentInfo> initialize() {
    return this.process.initialize().thenCompose(initialized -> this.process.settings()
      .orTimeout(SETTINGS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .handle((settings, error) -> ClaudeBackend.info(initialized, this.launch.model(),
        Json.string(Json.object(settings, "applied"), "effort"), this.authDescription)));
  }

  @Override
  public void prompt(String text, List<ImageAttachment> images) {
    this.process.sendUser(text, images);
  }

  @Override
  public void interrupt() {
    this.process.interrupt();
  }

  @Override
  public void answer(String requestId, PermissionAnswer answer) {
    JsonObject request = this.pendingRequests.remove(requestId);
    if (request != null) {
      this.process.respond(requestId, ClaudePermissions.response(request, answer));
    }
  }

  @Override
  public void setModel(String model) {
    this.process.setModel(model);
  }

  @Override
  public void setMode(String mode) {
    this.process.setPermissionMode(mode);
  }

  @Override
  public void setEffort(String effort) {
    this.process.setEffort(effort);
  }

  @Override
  public CompletableFuture<ContextUsage> contextUsage() {
    return this.process.contextUsage().thenApply(ClaudeContextUsage::parse);
  }

  @Override
  public @Nullable EditPreview proposedEdit(String requestId) {
    JsonObject request = this.pendingRequests.get(requestId);
    JsonObject input = Json.object(request, "input");
    String filePath = Json.string(input, "file_path");
    if (input == null || filePath == null) {
      return null;
    }
    return ClaudeEdits.preview(String.valueOf(Json.string(request, "tool_name")), input,
      this.launch.readFile(filePath));
  }

  @Override
  public boolean isAlive() {
    return this.process.isAlive();
  }

  @Override
  public void stop() {
    this.process.destroy();
    this.subagents.dispose();
  }

  @Override
  public void onMessage(CliMessage message) {
    if (message.isSystemInit() && message.sessionId() != null) {
      this.sessionId = message.sessionId();
    }
    TaskEvent task = TaskEvent.from(message);
    if (task != null) {
      this.subagents.onTaskEvent(task);
    }
    FileChange change = null;
    if (message.type() == CliMessage.Type.USER) {
      change = FileChange.fromToolResult(message.takeToolUseResult());
      if (change != null) {
        this.listener.onFileEdited(change.filePath(), change.originalContent());
      }
      this.subagents.onToolResults(message.toolResults());
    }
    for (AgentEvent event : ClaudeTranslator.translate(message, change, false)) {
      this.listener.onEvent(event instanceof AgentEvent.SessionStarted started ? this.withScratchpad(started) : event);
    }
  }

  /**
   * The CLI names its scratchpad only while the feature is on, but a session it once had one for, such as a resumed
   * one, keeps the folder. Reads the disk.
   */
  private AgentEvent.SessionStarted withScratchpad(AgentEvent.SessionStarted started) {
    if (started.scratchpadPath() != null || started.sessionId() == null) {
      return started;
    }
    Path directory = scratchpadDirectory(this.environment, this.launch.workDirectory().toString(),
      started.sessionId());
    if (!Files.isDirectory(directory)) {
      return started;
    }
    return new AgentEvent.SessionStarted(started.sessionId(), started.workingDirectory(), started.model(),
      started.mode(), directory.toString());
  }

  /** Where the CLI keeps a session's scratchpad: {@code <temp>/claude/<encoded cwd>/<session-id>/scratchpad}. */
  static Path scratchpadDirectory(Map<String, String> environment, String workDirectory, String sessionId) {
    String temp = System.getProperty("java.io.tmpdir");
    for (String variable : TEMP_VARIABLES) {
      String value = environment.get(variable);
      if (value != null && !value.isEmpty()) {
        temp = value;
        break;
      }
    }
    return Path.of(temp, "claude", SessionStore.encode(workDirectory), sessionId, "scratchpad");
  }

  @Override
  public void onPermissionRequest(String requestId, JsonObject request) {
    this.pendingRequests.put(requestId, request);
    this.listener.onPermission(requestId, ClaudePermissions.prompt(request));
  }

  @Override
  public void onExit(int exitCode, String stderrTail) {
    this.subagents.stopAll();
    this.listener.onExit(exitCode, stderrTail);
  }

  /** A subagent's message, relayed from its transcript. */
  private void emit(CliMessage message) {
    for (AgentEvent event : ClaudeTranslator.translate(message, null, false)) {
      this.listener.onEvent(event);
    }
  }

  private @Nullable Path subagentTranscript(String taskId) {
    String session = this.sessionId;
    return session == null
      ? null
      : SessionStore.forProject(this.environment, this.launch.workDirectory().toString())
        .subagentTranscript(session, taskId);
  }

}
