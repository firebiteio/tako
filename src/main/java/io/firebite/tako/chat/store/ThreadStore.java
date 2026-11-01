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
import io.firebite.tako.chat.watch.PullRequestWatch;
import io.firebite.tako.chat.watch.PullRequestWatcher;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  public ThreadStore(Path file) {
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

  /**
   * Whether a snooze hides the thread from the list: its own or one of its parents'. A parent that is archived or
   * missing ends the walk, as the list no longer nests the thread under it. An archived thread is never hidden.
   */
  public synchronized boolean isSnoozeHidden(Record record) {
    for (Record listed : this.listedLineage(record)) {
      if (listed.snoozedUntil > 0) {
        return true;
      }
    }
    return false;
  }

  /** Ends the snooze of the thread and of its parents, so the list shows it. Returns the threads it changed. */
  public synchronized List<Record> wakeWithAncestors(Record record) {
    List<Record> woken = new ArrayList<>();
    for (Record listed : this.listedLineage(record)) {
      if (listed.snoozedUntil > 0) {
        listed.snoozedUntil = 0;
        woken.add(listed);
      }
    }
    return woken;
  }

  /** The thread and its parents up to the first archived or missing one, which the list no longer nests under. */
  private List<Record> listedLineage(Record record) {
    List<Record> lineage = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Record current = record;
    while (current != null && !current.archived && seen.add(current.id)) {
      lineage.add(current);
      current = this.find(current.parentId);
    }
    return lineage;
  }

  /** Whether the thread is a run of a parallel-run bench. */
  public synchronized boolean isRun(String id) {
    for (Bench bench : this.data.benches) {
      for (Run run : bench.runs) {
        if (run.thread.equals(id)) {
          return true;
        }
      }
    }
    return false;
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

  public synchronized void addBench(Bench bench) {
    this.data.benches.add(bench);
    this.save();
  }

  public synchronized @Nullable Bench findBench(@Nullable String id) {
    for (Bench bench : this.data.benches) {
      if (bench.id.equals(id)) {
        return bench;
      }
    }
    return null;
  }

  public synchronized void removeBench(String id) {
    this.data.benches.removeIf(bench -> bench.id.equals(id));
    this.save();
  }

  public synchronized List<Bench> benches() {
    return new ArrayList<>(this.data.benches);
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
      AtomicFiles.write(this.file, GSON.toJson(this.data));
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
      if (data.benches == null) {
        data.benches = new ArrayList<>();
      }
      return data;
    } catch (IOException | JsonParseException e) {
      LOG.warn("Could not read chat threads from " + file, e);
      return new Data();
    }
  }

  public static final class Record {
    public String id = "";
    public static final String DEFAULT_TITLE = "New chat";

    public String title = DEFAULT_TITLE;
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
    /** The folder the thread works in instead of its project folder, e.g. a git worktree made for it. */
    public @Nullable String workPath;
    /** The thread whose agent created this one. */
    public @Nullable String parentId;
    /** How long the first turn took, 0 until it ended. */
    public long firstTurnMillis;
    /** Whether the list keeps the thread above the others of its group. */
    public boolean pinned;
    /** The agent finished or asked something while the thread was not shown, or the person marked it unread. */
    public boolean unread;
    /** When the thread's snooze ends, in epoch millis; 0 when it is not snoozed. */
    public long snoozedUntil;
    /** When the person last opened, restored or woke the thread. Idle time for auto archive counts from here too. */
    public long seenAt;
    /** The pull request the thread was archived for automatically, so restoring it does not archive it again. */
    public @Nullable String settledPullRequest;
    /** The worktree setup that has to run before the thread's next prompt; null once it ran or was skipped. */
    public @Nullable PendingSetup pendingSetup;
    /** The pull request the thread watches, see {@link PullRequestWatcher}. Swapped whole, never changed in place. */
    public volatile @Nullable PullRequestWatch watch;

    /** The folder the thread works in: its {@link #workPath}, its {@link #projectPath} or else {@code basePath}. */
    public @Nullable String folder(@Nullable String basePath) {
      if (this.workPath != null) {
        return this.workPath;
      }
      return this.projectPath != null ? this.projectPath : basePath;
    }

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

    public ChatMessage.ThreadItem toItem() {
      PullRequestWatch watched = this.watch;
      return new ChatMessage.ThreadItem(this.id, this.title, this.archived, this.updatedAt, this.sessionId != null,
        this.costUsd, this.inputTokens + this.outputTokens + this.cacheReadTokens + this.cacheWriteTokens, this.branch,
        this.worktree, this.projectPath, this.pullRequest, this.agent(), this.firstTurnMillis, this.parentId,
        watched == null ? null : watched.number, this.pinned, this.unread, this.snoozedUntil);
    }
  }

  /** A folder threads can work in. The IDE project is always the first. */
  public static final class ProjectFolder {
    public String path = "";
    public String name = "";
    public boolean collapsed;
  }

  /** One prompt run several times at once, each run in its own git worktree off the same commit. */
  public static final class Bench {
    private static final int SHORT_COMMIT = 7;

    public String id = "";
    public String title = "";
    /** The project folder the runs belong to, or null for the IDE project. */
    public @Nullable String projectPath;
    /** The main checkout's top folder, which the worktrees belong to. */
    public String repository = "";
    public String baseCommit = "";
    public List<Run> runs = new ArrayList<>();

    public ChatMessage.BenchItem toItem() {
      List<String> threads = new ArrayList<>();
      for (Run run : this.runs) {
        threads.add(run.thread);
      }
      return new ChatMessage.BenchItem(this.id, this.title, this.projectPath,
        this.baseCommit.substring(0, Math.min(SHORT_COMMIT, this.baseCommit.length())), threads);
    }
  }

  /** A run's worktree and branch, kept until the bench is discarded even when its thread is closed. */
  public static final class Run {
    public String thread = "";
    public String worktree = "";
    public String branch = "";
  }

  /** A new worktree whose {@code .worktreesetup} still has to run, with the main checkout it was made from. */
  public static final class PendingSetup {
    public String repository = "";
    public String worktree = "";

    public static PendingSetup of(Path repository, Path worktree) {
      PendingSetup setup = new PendingSetup();
      setup.repository = repository.toString();
      setup.worktree = worktree.toString();
      return setup;
    }
  }

  static final class Data {
    List<Record> threads = new ArrayList<>();
    List<ProjectFolder> projects = new ArrayList<>();
    List<Bench> benches = new ArrayList<>();
    @Nullable
    String activeId;
  }
}
