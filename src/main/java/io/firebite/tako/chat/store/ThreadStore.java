package io.firebite.tako.chat.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import io.firebite.tako.agent.SessionCost;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ui.bridge.PageMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The project's chat threads. Written to its own file on every change, so a crash or a killed IDE loses nothing.
 * The IDE saves the workspace file only on close or autosave.
 */
@Service(Service.Level.PROJECT)
public final class ThreadStore {
  private static final Logger LOG = Logger.getInstance(ThreadStore.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final Path file;
  private final Data data;

  public ThreadStore(Project project) {
    this(Path.of(PathManager.getSystemPath(), "tako", "threads-" + project.getLocationHash() + ".json"));
  }

  ThreadStore(Path file) {
    this.file = file;
    this.data = load(file);
  }

  public static ThreadStore getInstance(Project project) {
    return project.getService(ThreadStore.class);
  }

  public synchronized Record create(@Nullable String sessionId, @Nullable String title, @Nullable String projectPath,
    String agent) {
    Record record = new Record();
    record.agent = agent;
    record.id = UUID.randomUUID().toString();
    record.sessionId = sessionId;
    record.projectPath = projectPath;
    if (title != null) {
      record.title = title;
    }
    record.updatedAt = System.currentTimeMillis();
    this.data.threads.add(0, record);
    this.save();
    return record;
  }

  public synchronized @Nullable Record find(@Nullable String id) {
    for (Record record : this.data.threads) {
      if (record.id.equals(id)) {
        return record;
      }
    }
    return null;
  }

  public synchronized @Nullable Record findBySession(String sessionId) {
    for (Record record : this.data.threads) {
      if (sessionId.equals(record.sessionId)) {
        return record;
      }
    }
    return null;
  }

  public synchronized void remove(String id) {
    this.data.threads.removeIf(record -> record.id.equals(id));
    this.save();
  }

  public synchronized List<Record> all() {
    return new ArrayList<>(this.data.threads);
  }

  /** The project folders, with the IDE project ({@code primaryPath}) first. Adds it when missing. */
  public synchronized List<ProjectFolder> projects(String primaryPath, String primaryName) {
    if (this.data.projects == null) {
      this.data.projects = new ArrayList<>();
    }
    ProjectFolder primary = this.findProject(primaryPath);
    if (primary == null) {
      primary = new ProjectFolder();
      primary.path = primaryPath;
      primary.name = primaryName;
      this.data.projects.addFirst(primary);
      this.save();
    } else if (this.data.projects.indexOf(primary) != 0) {
      this.data.projects.remove(primary);
      this.data.projects.addFirst(primary);
    }
    return new ArrayList<>(this.data.projects);
  }

  public synchronized @Nullable ProjectFolder findProject(@Nullable String path) {
    if (path == null || this.data.projects == null) {
      return null;
    }
    for (ProjectFolder project : this.data.projects) {
      if (samePath(project.path, path)) {
        return project;
      }
    }
    return null;
  }

  public synchronized ProjectFolder addProject(String path, String name) {
    ProjectFolder existing = this.findProject(path);
    if (existing != null) {
      return existing;
    }
    ProjectFolder project = new ProjectFolder();
    project.path = path;
    project.name = name;
    this.data.projects.add(project);
    this.save();
    return project;
  }

  public synchronized void removeProject(String path) {
    this.data.projects.removeIf(project -> samePath(project.path, path));
    this.save();
  }

  public static boolean samePath(@Nullable String first, @Nullable String second) {
    if (first == null || second == null) {
      return false;
    }
    return first.replace('\\', '/').equalsIgnoreCase(second.replace('\\', '/'));
  }

  public synchronized @Nullable String activeId() {
    return this.data.activeId;
  }

  public synchronized void setActiveId(String id) {
    this.data.activeId = id;
    this.save();
  }

  /** Writes the threads. Call after changing a record's fields. */
  public synchronized void save() {
    try {
      Files.createDirectories(this.file.getParent());
      Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
      Files.writeString(temporary, GSON.toJson(this.data), StandardCharsets.UTF_8);
      Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warn("Could not save chat threads to " + this.file, e);
    }
  }

  private static Data load(Path file) {
    if (!Files.isRegularFile(file)) {
      return new Data();
    }
    try {
      Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
      if (data == null) {
        return new Data();
      }
      if (data.threads == null) {
        data.threads = new ArrayList<>();
      }
      if (data.projects == null) {
        data.projects = new ArrayList<>();
      }
      return data;
    } catch (IOException | JsonParseException e) {
      LOG.warn("Could not read chat threads from " + file, e);
      return new Data();
    }
  }

  public static final class Record {
    public String id = "";
    public String title = "New chat";
    public @Nullable String sessionId;
    public boolean archived;
    public long updatedAt;
    public String model = "";
    public String mode = "";
    public String effort = "";
    public double costUsd;
    public long inputTokens;
    public long outputTokens;
    public long cacheReadTokens;
    public long cacheWriteTokens;
    public @Nullable String branch;
    public @Nullable String worktree;
    public @Nullable GitInfo.PullRequest pullRequest;
    /** The folder the thread works in, or null for the IDE project. */
    public @Nullable String projectPath;
    /** The {@link io.firebite.tako.agent.AgentBackend#id()} of the agent the thread runs. See {@link #agent()}. */
    public @Nullable String agent;

    public void setUsage(SessionCost cost) {
      this.inputTokens = cost.inputTokens();
      this.outputTokens = cost.outputTokens();
      this.cacheReadTokens = cost.cacheReadTokens();
      this.cacheWriteTokens = cost.cacheWriteTokens();
      this.costUsd = cost.costUsd();
    }

    /** The agent the thread runs. Threads from before there were several run Claude Code. */
    public String agent() {
      return this.agent != null ? this.agent : ClaudeBackend.ID;
    }

    public PageMessage.ThreadItem toItem() {
      return new PageMessage.ThreadItem(this.id, this.title, this.archived, this.updatedAt, this.sessionId != null,
        this.costUsd, this.inputTokens + this.outputTokens + this.cacheReadTokens + this.cacheWriteTokens, this.branch,
        this.worktree, this.projectPath, this.pullRequest, this.agent());
    }
  }

  /** A folder threads can work in. The IDE project is always the first. */
  public static final class ProjectFolder {
    public String path = "";
    public String name = "";
    public boolean collapsed;
  }

  static final class Data {
    List<Record> threads = new ArrayList<>();
    List<ProjectFolder> projects = new ArrayList<>();
    @Nullable
    String activeId;
  }
}
