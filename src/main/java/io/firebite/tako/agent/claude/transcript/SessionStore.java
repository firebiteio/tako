package io.firebite.tako.agent.claude.transcript;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.agent.claude.protocol.FileChange;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Reads the CLI's own transcripts, stored as {@code <config>/projects/<encoded cwd>/<session-id>.jsonl}. */
public final class SessionStore {
  static final String TRANSCRIPT_EXTENSION = ".jsonl";
  private static final String CONFIG_DIR_VARIABLE = "CLAUDE_CONFIG_DIR";
  private static final Pattern SESSION_ID = Pattern.compile("[0-9a-fA-F-]{36}");
  private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9_-]+");
  private static final int DEFAULT_RETENTION_DAYS = 30;
  private static final String COST_STATE = "cost-state";
  private static final String LAST_PROMPT = "last-prompt";

  private final Path directory;

  public SessionStore(Path directory) {
    this.directory = directory;
  }

  public static SessionStore forProject(Map<String, String> environment, String projectPath) {
    return new SessionStore(configDirectory(environment).resolve("projects").resolve(encode(projectPath)));
  }

  public static String encode(String path) {
    return path.replaceAll("[^A-Za-z0-9]", "-");
  }

  public List<SessionInfo> list(int limit) throws IOException {
    List<Path> files = new ArrayList<>();
    if (!Files.isDirectory(this.directory)) {
      return List.of();
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.directory, "*" + TRANSCRIPT_EXTENSION)) {
      for (Path file : stream) {
        files.add(file);
      }
    }
    files.sort(Comparator.comparingLong(SessionStore::lastModified).reversed());
    List<SessionInfo> sessions = new ArrayList<>();
    for (Path file : files) {
      if (sessions.size() >= limit) {
        break;
      }
      String title = this.readTitle(file);
      if (title != null) {
        sessions.add(new SessionInfo(sessionId(file), title, lastModified(file)));
      }
    }
    return sessions;
  }

  /** The main thread's user and assistant messages. */
  public List<TranscriptMessage> load(String sessionId) throws IOException {
    return this.load(sessionId, null);
  }

  /**
   * Like {@link #load(String)}, but only the messages whose transcript line contains {@code needle}, which is
   * lowercase. Parses no other line.
   */
  public List<TranscriptMessage> loadMentioning(String sessionId, String needle) throws IOException {
    return this.load(sessionId, needle);
  }

  private List<TranscriptMessage> load(String sessionId, @Nullable String needle) throws IOException {
    List<TranscriptMessage> messages = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(this.transcript(sessionId), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (needle != null && !line.toLowerCase(Locale.ROOT).contains(needle)) {
          continue;
        }
        TranscriptMessage message = mainMessage(Json.parseObject(line));
        if (message != null) {
          messages.add(message);
        }
      }
    }
    return messages;
  }

  /**
   * Copies the transcript into a new session, up to the first main-thread message {@code isCut} accepts. Leaves out
   * the cumulative cost, which is the original's, and the CLI's resume pointer, which may point past the cut.
   *
   * @return the new session's id
   */
  public String fork(String sessionId, Predicate<TranscriptMessage> isCut) throws IOException {
    String forkId = UUID.randomUUID().toString();
    String ownId = "\"sessionId\":\"" + sessionId + "\"";
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(this.transcript(sessionId), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        JsonObject entry = Json.parseObject(line);
        String type = Json.string(entry, "type");
        if (COST_STATE.equals(type) || LAST_PROMPT.equals(type)) {
          continue;
        }
        TranscriptMessage message = mainMessage(entry);
        if (message != null && isCut.test(message)) {
          break;
        }
        lines.add(line.replace(ownId, "\"sessionId\":\"" + forkId + "\""));
      }
    }
    Files.write(this.transcript(forkId), lines, StandardCharsets.UTF_8);
    return forkId;
  }

  /** The session's cumulative usage from its last {@code cost-state} line, or null before its first one. */
  public @Nullable SessionUsage usage(String sessionId) throws IOException {
    Path file = this.transcript(sessionId);
    return Files.isRegularFile(file) ? readUsage(file) : null;
  }

  /** Usage of every session of the project, split per day. See {@link UsageScanner}. */
  public List<SessionUsage> allUsage() throws IOException {
    return UsageScanner.scan(this.directory, this.directory.getParent(), ZoneId.systemDefault());
  }

  /** How many days the CLI keeps transcripts, set by {@code cleanupPeriodDays} in its user settings, 30 by default. */
  public static int retentionDays(Map<String, String> environment) {
    Path settings = configDirectory(environment).resolve("settings.json");
    try {
      JsonObject json = Files.isRegularFile(settings)
        ? Json.parseObject(Files.readString(settings, StandardCharsets.UTF_8))
        : null;
      return Json.isNumber(json, "cleanupPeriodDays")
        ? (int) Json.number(json, "cleanupPeriodDays")
        : DEFAULT_RETENTION_DAYS;
    } catch (IOException e) {
      return DEFAULT_RETENTION_DAYS;
    }
  }

  /** Where the CLI writes a subagent's transcript while it runs. */
  public @Nullable Path subagentTranscript(String sessionId, String agentId) {
    if (!SESSION_ID.matcher(sessionId).matches() || !AGENT_ID.matcher(agentId).matches()) {
      return null;
    }
    return this.directory.resolve(sessionId).resolve("subagents").resolve("agent-" + agentId + TRANSCRIPT_EXTENSION);
  }

  public @Nullable String title(String sessionId) {
    if (!SESSION_ID.matcher(sessionId).matches()) {
      return null;
    }
    Path file = this.directory.resolve(sessionId + TRANSCRIPT_EXTENSION);
    return Files.isRegularFile(file) ? this.readTitle(file) : null;
  }

  static String sessionId(Path transcript) {
    String name = transcript.getFileName().toString();
    return name.substring(0, name.length() - TRANSCRIPT_EXTENSION.length());
  }

  private static Path configDirectory(Map<String, String> environment) {
    String configured = environment.get(CONFIG_DIR_VARIABLE);
    return configured != null && !configured.isBlank()
      ? Path.of(configured)
      : Path.of(System.getProperty("user.home"), ".claude");
  }

  private Path transcript(String sessionId) throws IOException {
    if (!SESSION_ID.matcher(sessionId).matches()) {
      throw new IOException("Invalid session id: " + sessionId);
    }
    return this.directory.resolve(sessionId + TRANSCRIPT_EXTENSION);
  }

  /** The line as a main-thread message, null for other lines. */
  private static @Nullable TranscriptMessage mainMessage(@Nullable JsonObject entry) {
    CliMessage.Type type = entry == null ? CliMessage.Type.OTHER : new CliMessage(entry).type();
    JsonObject body = Json.object(entry, "message");
    if ((type != CliMessage.Type.USER && type != CliMessage.Type.ASSISTANT) || isAside(entry) || body == null) {
      return null;
    }
    CliMessage message = CliMessage.of(type, body);
    String timestamp = Json.string(entry, "timestamp");
    if (timestamp != null) {
      message.json().addProperty("timestamp", timestamp);
    }
    return new TranscriptMessage(message, FileChange.fromToolResult(Json.object(entry, "toolUseResult")));
  }

  /** Subagent and CLI-internal lines, which the main conversation does not show. */
  private static boolean isAside(@Nullable JsonObject entry) {
    return Json.bool(entry, "isSidechain") || Json.bool(entry, "isMeta");
  }

  private static @Nullable SessionUsage readUsage(Path file) throws IOException {
    String last = null;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains("\"type\":\"" + COST_STATE + "\"")) {
          last = line;
        }
      }
    }
    return last == null ? null : SessionUsage.fromCostState(Json.parseObject(last), lastModified(file));
  }

  private @Nullable String readTitle(Path file) {
    String firstPrompt = null;
    String customTitle = null;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains("\"custom-title\"")) {
          customTitle = Json.string(Json.parseObject(line), "customTitle");
        } else if (firstPrompt == null && line.contains("\"type\":\"user\"")) {
          JsonObject entry = Json.parseObject(line);
          if (!isAside(entry)) {
            firstPrompt = promptText(Json.object(entry, "message"));
          }
        }
      }
    } catch (IOException e) {
      return null;
    }
    String title = customTitle != null ? customTitle : firstPrompt;
    return title == null ? null : AgentSessionInfo.titleOf(title);
  }

  static @Nullable String promptText(@Nullable JsonObject message) {
    if (message == null) {
      return null;
    }
    String text = Json.string(message, "content");
    if (text == null) {
      for (JsonObject block : Json.objects(message, "content")) {
        if ("text".equals(Json.string(block, "type"))) {
          text = Json.string(block, "text");
          break;
        }
      }
    }
    if (text == null || text.isBlank() || text.startsWith("<") || text.startsWith("[Request interrupted")) {
      return null;
    }
    return PromptComposer.typedPart(text).trim();
  }

  private static long lastModified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  public record SessionInfo(
    String id,
    String title,
    long lastModified
  ) {
  }

  /**
   * A main-thread message of a transcript, shaped like the stream-json line the CLI wrote for it.
   *
   * @param change what a Write or Edit in this message's tool result did, if any
   */
  public record TranscriptMessage(
    CliMessage message,
    @Nullable FileChange change
  ) {
  }
}
