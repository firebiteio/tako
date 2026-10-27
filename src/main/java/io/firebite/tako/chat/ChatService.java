package io.firebite.tako.chat;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.agent.claude.transcript.SessionUsage;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.ChatHost;
import io.firebite.tako.chat.thread.ChatView;
import io.firebite.tako.chat.thread.ThreadTitles;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.project.ProjectIcons;
import io.firebite.tako.mcp.McpServer;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.ChatPanel;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/** The project's chat threads. They share one panel in the Tako tool window, with one controller per opened thread. */
@Service(Service.Level.PROJECT)
public final class ChatService implements Disposable {
  public static final String TOOL_WINDOW_ID = "Tako";
  private static final Logger LOG = Logger.getInstance(ChatService.class);
  private static final int SESSION_LIST_LIMIT = 50;
  private static final long GIT_REFRESH_SECONDS = 15;

  private final Project project;
  private final ThreadStore store;
  private final Map<String, ChatController> controllers = new ConcurrentHashMap<>();
  private final Benches benches;
  private final List<Runnable> activityListeners = new CopyOnWriteArrayList<>();
  /** Serves each thread's {@link ThreadTools}, keyed by thread id. Guarded by {@code this}. */
  private @Nullable McpServer toolServer;
  private @Nullable ChatPanel panel;
  /** New Chat opened the tool window, so its first state shows a new thread. */
  private boolean isNewChatOnShow;
  private @Nullable ToolWindow toolWindow;

  public ChatService(Project project) {
    this.project = project;
    this.store = ThreadStore.getInstance(project);
    this.benches = new Benches(project, this, this.store);
  }

  public static ChatService getInstance(Project project) {
    return project.getService(ChatService.class);
  }

  /** Puts the chat panel into the tool window. Must run on the EDT. */
  public void attach(ToolWindow window) {
    this.toolWindow = window;
    Content content = ContentFactory.getInstance().createContent(null, "", false);
    content.setCloseable(false);
    ChatPanel chatPanel = new ChatPanel(content, this.project, this);
    content.setComponent(chatPanel.getComponent());
    content.setPreferredFocusableComponent(chatPanel.getComponent());
    window.getContentManager().addContent(content);
    this.panel = chatPanel;
    Async.inBackground(this::warmUsage);
    ScheduledFuture<?> gitRefresh = AppExecutorUtil.getAppScheduledExecutorService()
      .scheduleWithFixedDelay(this::refreshActiveGit, GIT_REFRESH_SECONDS, GIT_REFRESH_SECONDS, TimeUnit.SECONDS);
    Disposer.register(content, () -> {
      gitRefresh.cancel(false);
      for (ChatController controller : this.controllers.values()) {
        Disposer.dispose(controller);
      }
      this.controllers.clear();
      this.panel = null;
    });
    this.showInitialState();
  }

  /** Must run on the EDT. */
  public void openNewChat() {
    ToolWindow window = this.toolWindow();
    if (window == null) {
      return;
    }
    if (this.panel == null) {
      this.isNewChatOnShow = true;
      window.activate(null);
    } else {
      window.activate(() -> this.newThread(null, null, null, null));
    }
  }

  /** Adds context to the selected thread. Must run on the EDT. */
  public void addContext(ContextItem item, @Nullable String prefill) {
    this.withActiveController(controller -> controller.addContext(item, prefill));
  }

  /** Attaches image files to the selected thread's composer. Must run on the EDT. */
  public void addImages(List<Path> files) {
    this.withActiveController(controller -> controller.attachFiles(files));
  }

  private void withActiveController(Consumer<ChatController> action) {
    ToolWindow window = this.toolWindow();
    if (window == null) {
      return;
    }
    window.activate(() -> {
      ChatController controller = this.activeController();
      if (controller != null) {
        action.accept(controller);
      }
    });
  }

  /** Tells the chat what the settings let it offer. */
  public void postSettings() {
    this.post(new ChatMessage.Settings(ChatSettings.state().parallelRuns));
  }

  /**
   * Tells every open project's chat what the settings now let it offer. The settings pages call this on apply, so a
   * new agent or a changed default shows without a restart. Must run on the EDT.
   */
  public static void postSettingsToOpenProjects() {
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      ChatService service = ChatService.getInstance(project);
      service.postSettings();
      service.postAgents();
    }
  }

  private void showInitialState() {
    this.post(new ChatMessage.Project(this.project.getBasePath(), this.project.getName()));
    this.postSettings();
    this.postAgents();
    this.postProjects();
    this.benches.post();
    List<ChatMessage.ThreadItem> items = new ArrayList<>();
    for (ThreadStore.Record record : this.store.all()) {
      items.add(record.toItem());
    }
    this.post(new ChatMessage.Threads(items));

    ThreadStore.Record active = this.store.find(this.store.activeId());
    if (active == null || active.archived) {
      active = this.firstOpenThread(null);
    }
    if (active == null || this.isNewChatOnShow) {
      this.isNewChatOnShow = false;
      this.newThread(null, null, null, null);
    } else {
      this.select(active.id);
    }
  }

  private List<ThreadStore.ProjectFolder> projects() {
    String basePath = this.project.getBasePath();
    return this.store.projects(basePath != null ? basePath : "", this.project.getName());
  }

  /** The project folder a thread stores, null for the IDE project itself. */
  private @Nullable String threadPath(@Nullable String projectPath) {
    return projectPath == null || ThreadStore.samePath(projectPath, this.project.getBasePath()) ? null : projectPath;
  }

  private String folderOf(ThreadStore.Record record) {
    return this.folderOf(record.projectPath);
  }

  /** @param projectPath a project folder, or null for the IDE project */
  private String folderOf(@Nullable String projectPath) {
    String basePath = this.project.getBasePath();
    return projectPath != null ? projectPath : basePath != null ? basePath : "";
  }

  /** Reads the folders' icons in the background. */
  private void postProjects() {
    List<ThreadStore.ProjectFolder> folders = this.projects();
    Async.inBackground(() -> {
      List<ChatMessage.ProjectItem> items = new ArrayList<>();
      for (int i = 0; i < folders.size(); i++) {
        ThreadStore.ProjectFolder folder = folders.get(i);
        ProjectIcons.Icons icons = ProjectIcons.find(Path.of(folder.path));
        items.add(new ChatMessage.ProjectItem(folder.path, folder.name, folder.collapsed, i == 0, icons.light(),
          icons.dark()));
      }
      this.post(new ChatMessage.Projects(items));
    });
  }

  /** Asks for a folder to add as a project. The chooser opens once the current event is handled. */
  public void addProject() {
    Async.onEdt(this::chooseProject);
  }

  private void chooseProject() {
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.singleDir()
      .withTitle(TakoBundle.message("dialog.add.project.title"))
      .withDescription(TakoBundle.message("dialog.add.project.description"));
    VirtualFile folder = FileChooser.chooseFile(descriptor, this.project, null);
    if (folder == null) {
      return;
    }
    this.store.addProject(folder.getPath(), folder.getName());
    this.postProjects();
  }

  public void removeProject(String path) {
    if (ThreadStore.samePath(path, this.project.getBasePath())) {
      return;
    }
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && ThreadStore.samePath(record.projectPath, path)) {
        this.post(new ChatMessage.Error(TakoBundle.message("error.project.remove")));
        return;
      }
    }
    this.store.removeProject(path);
    this.postProjects();
  }

  public void collapseProject(String path, boolean collapsed) {
    ThreadStore.ProjectFolder folder = this.store.findProject(path);
    if (folder != null) {
      folder.collapsed = collapsed;
      this.store.save();
    }
  }

  /**
   * Opens a new thread and selects it.
   *
   * @param projectPath its project folder, or null for the IDE project
   * @param agent       the agent to run, or the default one when null
   */
  public void newThread(@Nullable String projectPath, @Nullable String agent) {
    this.newThread(null, null, this.threadPath(projectPath), agent);
  }

  /** @param agent the agent to run, or the default one when null */
  private void newThread(@Nullable String sessionId, @Nullable String title, @Nullable String projectPath,
    @Nullable String agent) {
    if (projectPath != null && this.store.findProject(projectPath) == null) {
      this.store.addProject(projectPath, Path.of(projectPath).getFileName().toString());
      this.postProjects();
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    String agentId = agent != null ? AgentRegistry.find(agent, environment).id() : AgentRegistry.defaultId(environment);
    ThreadStore.Record record = this.store.create(sessionId, title, projectPath, agentId);
    this.post(new ChatMessage.ThreadAdded(record.toItem()));
    this.select(record.id);
  }

  public void select(@Nullable String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return;
    }
    this.unarchive(record);
    this.store.setActiveId(record.id);
    this.post(new ChatMessage.Select(record.id));
    ChatController existing = this.controllers.get(record.id);
    if (existing != null) {
      Async.inBackground(existing::refreshGit);
      return;
    }
    this.newController(record).open();
  }

  /** Shows a new thread in the list and starts its agent without selecting it. */
  void openThread(ThreadStore.Record record) {
    this.post(new ChatMessage.ThreadAdded(record.toItem()));
    this.newController(record).open();
  }

  private ChatController newController(ThreadStore.Record record) {
    ChatController controller = new ChatController(this.project, new ThreadView(record.id), new Host(record), record,
      AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap()));
    this.controllers.put(record.id, controller);
    return controller;
  }

  /** A thread another thread's agent asked for, started on its prompt without selecting it. Must run on the EDT. */
  ThreadStore.Record spawn(ThreadStore.Record parent, ThreadTools.NewThread spec) {
    ThreadStore.Record record = this.store.create(null, spec.title(), parent.projectPath, spec.agent());
    record.parentId = parent.id;
    record.model = spec.model();
    record.mode = spec.mode();
    record.effort = spec.effort();
    record.workPath = spec.workPath();
    this.store.save();
    this.post(new ChatMessage.ThreadAdded(record.toItem()));
    this.newController(record).open(spec.prompt());
    return record;
  }

  /** Sends a prompt as if typed into the thread, opening it if it is not. Must run on the EDT. */
  void prompt(ThreadStore.Record record, String prompt) {
    ChatController controller = this.controllers.get(record.id);
    if (controller != null) {
      controller.submit(prompt);
      return;
    }
    this.unarchive(record);
    this.newController(record).open(prompt);
  }

  private void unarchive(ThreadStore.Record record) {
    if (record.archived) {
      record.archived = false;
      this.postUpdate(record);
    }
  }

  /** Copies the thread's first {@code prompts} turns into a new thread, which it selects. */
  public void fork(String id, int prompts) {
    ThreadStore.Record source = this.store.find(id);
    if (source == null || source.sessionId == null) {
      return;
    }
    String sessionId = source.sessionId;
    String folder = this.workFolder(source);
    AgentBackend backend = AgentRegistry.find(source.agent(), EnvironmentUtil.getEnvironmentMap());
    Async.inBackground(() -> {
      String forkId;
      try {
        forkId = backend.fork(folder, sessionId, prompts);
      } catch (AgentException e) {
        this.post(new ChatMessage.Error(e.getMessage()));
        return;
      }
      Async.onEdt(() -> {
        ThreadStore.Record record = this.store.create(forkId, TakoBundle.message("thread.fork.title", source.title),
          source.projectPath, source.agent());
        record.model = source.model;
        record.mode = source.mode;
        record.effort = source.effort;
        record.workPath = source.workPath;
        this.store.save();
        this.post(new ChatMessage.ThreadAdded(record.toItem()));
        this.select(record.id);
      });
    });
  }

  /** Whether the agent can name a thread in a one-shot call. */
  public boolean canRegenerateTitle(String agent) {
    return AgentRegistry.find(agent, EnvironmentUtil.getEnvironmentMap()).canAsk();
  }

  /**
   * Has the thread's agent name it from its first request and latest messages, and saves the title. Takes the
   * conversation the chat holds, else reads the session's history when the agent keeps it in files. Shows failures.
   *
   * @return completes on the EDT when done
   */
  public CompletableFuture<Void> regenerateTitle(String id, List<ThreadTitles.Message> shown) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      done.complete(null);
      return done;
    }
    String folder = this.workFolder(record);
    String sessionId = record.sessionId;
    AgentBackend backend = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap());
    Async.inBackground(() -> {
      try {
        String title = askTitle(backend, folder, sessionId, shown);
        Async.onEdt(() -> {
          record.title = title;
          this.postUpdate(record);
          done.complete(null);
        });
      } catch (AgentException e) {
        Async.onEdt(() -> {
          this.post(new ChatMessage.Error(TakoBundle.message("error.title", e.getMessage())));
          done.complete(null);
        });
      }
    });
    return done;
  }

  private static String askTitle(AgentBackend backend, String folder, @Nullable String sessionId,
    List<ThreadTitles.Message> shown) throws AgentException {
    List<ThreadTitles.Message> conversation = shown;
    if (conversation.isEmpty() && sessionId != null
      && backend.history(folder, sessionId) instanceof AgentHistory.Loaded loaded) {
      conversation = ThreadTitles.fromEvents(loaded.events());
    }
    if (conversation.isEmpty()) {
      throw new AgentException(TakoBundle.message("error.title.no.conversation"));
    }
    String title = ThreadTitles.title(backend.ask(Path.of(folder), ThreadTitles.prompt(conversation)));
    if (title == null) {
      throw new AgentException(TakoBundle.message("error.title.blank"));
    }
    return title;
  }

  /** The threads whose sessions mention {@code query}, as far as their agents can tell. Reads files, so call off the EDT. */
  public Set<String> search(String query) {
    Map<String, AgentBackend> backends = new HashMap<>();
    for (AgentBackend backend : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
      backends.put(backend.id(), backend);
    }
    Set<String> found = new HashSet<>();
    for (ThreadStore.Record record : this.store.all()) {
      AgentBackend backend = backends.get(record.agent());
      String sessionId = record.sessionId;
      if (backend != null && sessionId != null && backend.sessionMentions(this.workFolder(record), sessionId, query)) {
        found.add(record.id);
      }
    }
    return found;
  }

  /** What the agents of the open threads do, in the thread list's order. */
  public List<ThreadActivity> activity() {
    List<ThreadActivity> activity = new ArrayList<>();
    for (ThreadStore.Record record : this.store.all()) {
      ChatController controller = this.controllers.get(record.id);
      if (controller != null) {
        activity.add(new ThreadActivity(record.id, record.title, controller.state()));
      }
    }
    return activity;
  }

  /** Calls {@code listener} on the EDT whenever an open thread's agent changes what it does, or a thread closes. */
  public void addActivityListener(Runnable listener) {
    this.activityListeners.add(listener);
  }

  public void removeActivityListener(Runnable listener) {
    this.activityListeners.remove(listener);
  }

  private void fireActivity() {
    for (Runnable listener : this.activityListeners) {
      listener.run();
    }
  }

  /** Selects the thread, if given, and brings up the tool window. Must run on the EDT. */
  public void show(@Nullable String id) {
    this.select(id);
    ToolWindow window = this.toolWindow();
    if (window != null) {
      window.activate(null);
    }
  }

  /** What the project folders' sessions cost today. Reads the transcripts, so call off the EDT. */
  public double todayCost() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    LocalDate today = LocalDate.now();
    double cost = 0;
    for (ThreadStore.ProjectFolder folder : this.projects()) {
      try {
        for (SessionUsage usage : SessionStore.forProject(environment, folder.path).allUsage()) {
          cost += usage.costByDay().getOrDefault(today, 0.0);
        }
      } catch (IOException e) {
        LOG.debug("Could not read the session usage of " + folder.path, e);
      }
    }
    return cost;
  }

  /** The open thread's controller, null for a thread not open in Tako. */
  public @Nullable ChatController controller(String id) {
    return this.controllers.get(id);
  }

  /** The folder the thread works in. */
  String workFolder(ThreadStore.Record record) {
    String folder = record.folder(this.project.getBasePath());
    return folder != null ? folder : "";
  }

  /** Where the thread's agent reaches its {@link ThreadTools}. Starts the server once. Null if it cannot start. */
  private synchronized @Nullable String toolsUrl(String id) {
    if (this.toolServer == null) {
      try {
        this.toolServer = McpServer.start();
      } catch (IOException e) {
        LOG.warn("Could not start the MCP server for Tako's tools", e);
        return null;
      }
    }
    return this.toolServer.publish(id, new ThreadTools(this, this.store, id));
  }

  private synchronized void withdrawTools(String id) {
    if (this.toolServer != null) {
      this.toolServer.withdraw(id);
    }
  }

  @Override
  public synchronized void dispose() {
    if (this.toolServer != null) {
      this.toolServer.close();
      this.toolServer = null;
    }
  }

  public void close(String id) {
    this.closeAll(List.of(id));
  }

  /** Selects another thread only once all are closed, so none of them starts its agent in between. */
  void closeAll(List<String> ids) {
    String activeId = this.store.activeId();
    for (String id : ids) {
      if (this.store.find(id) != null) {
        this.disposeController(id);
        this.withdrawTools(id);
        this.store.remove(id);
        this.post(new ChatMessage.ThreadRemoved(id));
      }
    }
    if (activeId != null && ids.contains(activeId)) {
      this.selectAnotherIfActive(activeId);
    }
  }

  public void setArchived(String id, boolean archived) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return;
    }
    if (!archived) {
      this.select(record.id);
      return;
    }
    record.archived = true;
    this.disposeController(record.id);
    this.postUpdate(record);
    this.selectAnotherIfActive(record.id);
  }

  private void selectAnotherIfActive(String id) {
    if (!id.equals(this.store.activeId())) {
      return;
    }
    ThreadStore.Record next = this.firstOpenThread(id);
    if (next == null) {
      this.newThread(null, null, null, null);
    } else {
      this.select(next.id);
    }
  }

  private @Nullable ThreadStore.Record firstOpenThread(@Nullable String except) {
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && !record.id.equals(except)) {
        return record;
      }
    }
    return null;
  }

  /** Selects the thread holding the session, else opens one resuming it. */
  public void resume(String sessionId, @Nullable String title, @Nullable String projectPath, @Nullable String agent) {
    ThreadStore.Record existing = this.store.findBySession(sessionId);
    if (existing != null) {
      this.select(existing.id);
    } else {
      this.newThread(sessionId, title, this.threadPath(projectPath), agent);
    }
  }

  /** Runs another agent in a thread that has not started a session yet. Replaces a running one. */
  public void setAgent(String id, String agent) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null || record.sessionId != null || agent.equals(record.agent())) {
      return;
    }
    record.agent = AgentRegistry.find(agent, EnvironmentUtil.getEnvironmentMap()).id();
    record.model = "";
    record.mode = "";
    record.effort = "";
    this.disposeController(record.id);
    this.postUpdate(record);
    this.select(record.id);
  }

  /** Must run on the EDT. */
  public void createBench(BenchRequest request) {
    String projectPath = this.threadPath(request.projectPath());
    this.benches.create(request, projectPath, this.folderOf(projectPath));
  }

  /**
   * Shows a run's changes against the commit its bench started from.
   *
   * @param runIndex the run's number, from 1
   */
  public void showRunDiff(String benchId, int runIndex) {
    Async.inBackground(() -> this.benches.showDiff(benchId, runIndex));
  }

  /**
   * Applies a run's changes to the main checkout, then discards the bench.
   *
   * @param runIndex the run's number, from 1
   */
  public void pickWinner(String benchId, int runIndex) {
    Async.inBackground(() -> this.benches.pickWinner(benchId, runIndex));
  }

  /** Closes the bench's threads and removes its worktrees and branches. */
  public void discardBench(String benchId) {
    this.benches.discard(benchId);
  }

  /** What each run of the bench changed, in run order, or null for an unknown bench. Reads git, so call off the EDT. */
  public @Nullable List<ChatMessage.RunChanges> compareBench(String benchId) {
    return this.benches.compare(benchId);
  }

  /** Cost and tokens of every session of the project folders. Reads the transcripts, so call off the EDT. */
  public ChatMessage.Usage usage() {
    return UsageReport.build(this.projects(), this.store, EnvironmentUtil.getEnvironmentMap());
  }

  /**
   * Every agent's sessions in every project folder that no thread holds, newest first. Starts agents to ask, so call
   * off the EDT.
   */
  public List<ChatMessage.SessionItem> history() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    List<CompletableFuture<List<ChatMessage.SessionItem>>> lookups = new ArrayList<>();
    for (AgentBackend backend : AgentRegistry.backends(environment)) {
      for (ThreadStore.ProjectFolder folder : this.projects()) {
        lookups.add(CompletableFuture.supplyAsync(() -> {
          List<ChatMessage.SessionItem> items = new ArrayList<>();
          for (AgentSessionInfo session : backend.sessions(folder.path, SESSION_LIST_LIMIT)) {
            items.add(new ChatMessage.SessionItem(session.id(), session.title(), session.lastModified(), folder.path,
              folder.name, backend.id(), backend.displayName()));
          }
          return items;
        }, AppExecutorUtil.getAppExecutorService()));
      }
    }
    List<ChatMessage.SessionItem> found = new ArrayList<>();
    for (CompletableFuture<List<ChatMessage.SessionItem>> lookup : lookups) {
      for (ChatMessage.SessionItem item : lookup.join()) {
        if (this.store.findBySession(item.id()) == null) {
          found.add(item);
        }
      }
    }
    found.sort(Comparator.comparingLong(ChatMessage.SessionItem::lastModified).reversed());
    return found.subList(0, Math.min(SESSION_LIST_LIMIT, found.size()));
  }

  /** Tells the chat which agents a thread can run. */
  public void postAgents() {
    List<ChatMessage.AgentOption> agents = new ArrayList<>();
    for (AgentBackend backend : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
      agents.add(new ChatMessage.AgentOption(backend.id(), backend.displayName()));
    }
    this.post(new ChatMessage.Agents(agents));
  }

  private void refreshActiveGit() {
    ToolWindow window = this.toolWindow;
    ChatController controller = this.activeController();
    if (window != null && controller != null && ApplicationManager.getApplication().isActive()) {
      Async.onEdt(() -> {
        if (window.isVisible()) {
          Async.inBackground(controller::refreshGit);
        }
      });
    }
  }

  private void warmUsage() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    for (ThreadStore.ProjectFolder folder : this.projects()) {
      try {
        SessionStore.forProject(environment, folder.path).allUsage();
      } catch (IOException e) {
        LOG.debug("Could not warm the usage cache", e);
      }
    }
  }

  private @Nullable ChatController activeController() {
    String activeId = this.store.activeId();
    return activeId == null ? null : this.controllers.get(activeId);
  }

  private void disposeController(String id) {
    ChatController controller = this.controllers.remove(id);
    if (controller != null) {
      Disposer.dispose(controller);
      this.fireActivity();
    }
  }

  private void postUpdate(ThreadStore.Record record) {
    this.store.save();
    this.post(new ChatMessage.ThreadUpdated(record.toItem()));
  }

  void post(ChatMessage.WorkspaceMessage message) {
    ChatPanel chatPanel = this.panel;
    if (chatPanel != null) {
      chatPanel.post(message);
    }
  }

  void post(ChatMessage.ThreadMessage message, String threadId) {
    ChatPanel chatPanel = this.panel;
    if (chatPanel != null) {
      chatPanel.post(message, threadId);
    }
  }

  private @Nullable ToolWindow toolWindow() {
    return ToolWindowManager.getInstance(this.project).getToolWindow(TOOL_WINDOW_ID);
  }

  /** An open thread and what its agent does. */
  public record ThreadActivity(
    String id,
    String title,
    ChatController.State state
  ) {
  }

  private final class ThreadView implements ChatView {
    private final String threadId;

    private ThreadView(String threadId) {
      this.threadId = threadId;
    }

    @Override
    public void post(ChatMessage.ThreadMessage message) {
      ChatService.this.post(message, this.threadId);
    }
  }

  private final class Host implements ChatHost {
    private final ThreadStore.Record record;

    private Host(ThreadStore.Record record) {
      this.record = record;
    }

    @Override
    public void setTitle(String title) {
      this.record.title = title;
      ChatService.this.postUpdate(this.record);
    }

    @Override
    public void recordChanged() {
      ChatService.this.postUpdate(this.record);
    }

    @Override
    public void activity() {
      this.record.updatedAt = System.currentTimeMillis();
      ChatService.this.postUpdate(this.record);
    }

    @Override
    public void stateChanged() {
      ChatService.this.fireActivity();
    }

    @Override
    public List<String> additionalDirectories() {
      String own = ChatService.this.folderOf(this.record);
      List<String> directories = new ArrayList<>();
      for (ThreadStore.ProjectFolder folder : ChatService.this.projects()) {
        if (!folder.path.isEmpty() && !ThreadStore.samePath(folder.path, own)) {
          directories.add(folder.path);
        }
      }
      return directories;
    }

    @Override
    public @Nullable String toolsUrl() {
      return ChatService.this.toolsUrl(this.record.id);
    }

    @Override
    public void requestAttention(String reason) {
      ToolWindow window = ChatService.this.toolWindow;
      boolean isShown = window != null && window.isVisible()
        && this.record.id.equals(ChatService.this.store.activeId());
      if (isShown) {
        return;
      }
      Notification notification = NotificationGroupManager.getInstance()
        .getNotificationGroup(TOOL_WINDOW_ID)
        .createNotification(reason, this.record.title, NotificationType.INFORMATION);
      notification.addAction(NotificationAction.createSimpleExpiring(TakoBundle.message("notification.show"),
        () -> ChatService.this.show(this.record.id)));
      notification.notify(ChatService.this.project);
    }
  }
}
