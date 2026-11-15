package io.firebite.tako.chat;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.ExecutableLocator;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.agent.claude.transcript.SessionUsage;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.ChatHost;
import io.firebite.tako.chat.thread.ChatView;
import io.firebite.tako.chat.thread.ThreadTitles;
import io.firebite.tako.chat.watch.ForgeCall;
import io.firebite.tako.chat.watch.PullRequestWatch;
import io.firebite.tako.chat.watch.PullRequestWatcher;
import io.firebite.tako.chat.watch.WatchException;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.Checkpoints;
import io.firebite.tako.ide.project.Forge;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ide.project.GitWorktree;
import io.firebite.tako.ide.project.ProjectIcons;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.mcp.McpServer;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.ChatPanel;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** The project's chat threads. They share one panel in the Tako tool window, with one controller per opened thread. */
@Service(Service.Level.PROJECT)
public final class ChatService implements Disposable {
  public static final String TOOL_WINDOW_ID = "Tako";
  private static final Logger LOG = Logger.getInstance(ChatService.class);
  private static final int SESSION_LIST_LIMIT = 50;
  private static final long GIT_REFRESH_SECONDS = 15;
  private static final long UPKEEP_SECONDS = 60;
  private static final long PULL_REQUEST_REFRESH_MINUTES = 10;
  /** How often due pull request watches are polled; a thread that settles hears its news within this. */
  private static final long WATCH_TICK_SECONDS = 30;
  private static final long RESUME_CHECK_SECONDS = 20;
  /** When each agent last resumed a thread after its limit reset, across projects, so they do not resume at once. */
  private static final Map<String, Long> LAST_RESUMES = new ConcurrentHashMap<>();
  /** Across projects, as an agent offers the same models in each. */
  private static final AgentCatalog AGENTS = new AgentCatalog();

  private final Project project;
  private final ThreadStore store;
  private final Map<String, ChatController> controllers = new ConcurrentHashMap<>();
  private final Benches benches;
  private final ThreadUpkeep upkeep;
  private final PullRequestWatcher watcher;
  private final LimitResumes limits = new LimitResumes(System::currentTimeMillis, LAST_RESUMES);
  private final ChildWakes wakes = new ChildWakes();
  private final List<Runnable> activityListeners = new CopyOnWriteArrayList<>();
  /** The side chat each main thread's pane shows, by main thread id. */
  private final Map<String, String> shownSideChats = new ConcurrentHashMap<>();
  private volatile boolean isSidePaneCovering;
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
    this.upkeep = new ThreadUpkeep(this, this.store);
    this.watcher = new PullRequestWatcher(this.store, ChatService::runForgeCli, new WatchHost(),
      System::currentTimeMillis);
  }

  /** Runs gh or glab, found anew each time, so installing it while the IDE runs is enough. */
  private static ForgeCall.Result runForgeCli(Forge forge, Path directory, List<String> args) {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    String cli = ExecutableLocator.locate("", forge.cli(), List.of(), environment, SystemInfo.isWindows);
    return ForgeCall.run(cli != null ? cli : forge.cli(), environment, directory, args);
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
    Async.inBackground(ChatService::deleteOldPastes);
    ScheduledExecutorService scheduler = AppExecutorUtil.getAppScheduledExecutorService();
    ScheduledFuture<?> gitRefresh = scheduler.scheduleWithFixedDelay(this::refreshActiveGit, GIT_REFRESH_SECONDS,
      GIT_REFRESH_SECONDS, TimeUnit.SECONDS);
    ScheduledFuture<?> watchTicks = scheduler.scheduleWithFixedDelay(this.watcher::tick, WATCH_TICK_SECONDS,
      WATCH_TICK_SECONDS, TimeUnit.SECONDS);
    ScheduledFuture<?> upkeepTick = scheduler.scheduleWithFixedDelay(() -> Async.onEdt(this::tickUpkeep),
      UPKEEP_SECONDS, UPKEEP_SECONDS, TimeUnit.SECONDS);
    ScheduledFuture<?> pullRequestRefresh = scheduler.scheduleWithFixedDelay(
      () -> Async.onEdt(this::refreshPullRequests), 1, PULL_REQUEST_REFRESH_MINUTES, TimeUnit.MINUTES);
    ScheduledFuture<?> resumeCheck = scheduler.scheduleWithFixedDelay(() -> Async.onEdt(this::resumeDue),
      RESUME_CHECK_SECONDS, RESUME_CHECK_SECONDS, TimeUnit.SECONDS);
    this.project.getMessageBus().connect(content).subscribe(ToolWindowManagerListener.TOPIC,
      new ToolWindowManagerListener() {
        @Override
        public void toolWindowShown(ToolWindow shown) {
          if (TOOL_WINDOW_ID.equals(shown.getId())) {
            ChatService.this.markActiveRead();
          }
        }
      });
    Disposer.register(content, () -> {
      gitRefresh.cancel(false);
      watchTicks.cancel(false);
      upkeepTick.cancel(false);
      pullRequestRefresh.cancel(false);
      resumeCheck.cancel(false);
      for (ChatController controller : this.controllers.values()) {
        Disposer.dispose(controller);
      }
      this.controllers.clear();
      this.panel = null;
    });
    this.showInitialState();
    this.tickUpkeep();
  }

  /** Wakes due snoozes and archives done threads, while the chat is attached. Must run on the EDT. */
  private void tickUpkeep() {
    if (this.panel != null) {
      this.upkeep.tick(System.currentTimeMillis());
    }
  }

  private void refreshPullRequests() {
    if (this.panel != null) {
      this.upkeep.refreshPullRequests();
    }
  }

  ThreadUpkeep upkeep() {
    return this.upkeep;
  }

  private void markActiveRead() {
    ThreadStore.Record active = this.store.find(this.store.activeId());
    if (active != null && active.unread) {
      active.unread = false;
      active.seenAt = System.currentTimeMillis();
      this.postUpdate(active);
    }
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

  /**
   * Adds a review comment to the thread whose changes it is on, selecting it and taking it out of the archive. Goes to
   * the selected thread when there is no such thread, or it was closed. Must run on the EDT.
   */
  public void addReview(@Nullable String threadId, ContextItem item) {
    ToolWindow window = this.toolWindow();
    if (threadId == null || this.store.find(threadId) == null || window == null) {
      this.addContext(item, null);
      return;
    }
    window.activate(() -> {
      if (this.store.find(threadId) == null) {
        this.addContext(item, null);
        return;
      }
      this.select(threadId);
      this.post(new ChatMessage.Context(item, null), threadId);
    });
  }

  /** Attaches image files to the selected thread's composer. Must run on the EDT. */
  public void addImages(List<Path> files) {
    this.withActiveController(controller -> controller.attachFiles(files));
  }

  /** Runs on the controller of the thread on screen: the active one, or the side chat that covers it. */
  private void withActiveController(Consumer<ChatController> action) {
    ToolWindow window = this.toolWindow();
    if (window == null) {
      return;
    }
    window.activate(() -> {
      String activeId = this.store.activeId();
      String covering = activeId != null && this.isSidePaneCovering ? this.shownSideChats.get(activeId) : null;
      ChatController controller = covering != null && this.controllers.containsKey(covering)
        ? this.controllers.get(covering)
        : this.activeController();
      if (controller != null) {
        action.accept(controller);
      }
    });
  }

  /** Tells the chat what the settings let it offer. */
  public void postSettings() {
    ChatSettings.Data settings = ChatSettings.state();
    this.post(new ChatMessage.Settings(settings.parallelRuns, settings.steerWhileWorking));
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

  /** Starts again every open thread whose agent failed to start, after the settings that may have broken it changed. */
  public static void retryFailedStartsInOpenProjects() {
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      for (ChatController controller : ChatService.getInstance(project).controllers.values()) {
        controller.retryFailedStart();
      }
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
    if (active == null || active.archived || this.store.isSnoozeHidden(active)) {
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
  String folderOf(@Nullable String projectPath) {
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
    this.newThread(projectPath, agent, null);
  }

  /**
   * Like {@link #newThread(String, String)}, then sends {@code prompt} there as if typed.
   *
   * @param prompt the text, context and images to send, or null to send nothing
   */
  public void newThread(@Nullable String projectPath, @Nullable String agent, @Nullable ChatMessage.Submit prompt) {
    ThreadStore.Record record = this.newThread(null, null, this.threadPath(projectPath), agent);
    if (prompt != null) {
      this.post(prompt, record.id);
    }
  }

  /** @param agent the agent to run, or the default one when null */
  private ThreadStore.Record newThread(@Nullable String sessionId, @Nullable String title, @Nullable String projectPath,
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
    return record;
  }

  /** Selects the thread; for a side chat, its main thread with the side chat in the pane. */
  public void select(@Nullable String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return;
    }
    ThreadStore.Record main = SideChats.mainOf(this.store, record);
    if (main != record) {
      this.select(main.id);
      this.post(new ChatMessage.ShowSideChat(record.id, false));
      return;
    }
    this.store.setActiveId(record.id);
    List<ThreadStore.Record> changed = this.unarchived(record);
    changed.addAll(this.seen(record));
    this.postUpdates(changed);
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
    record.pendingSetup = spec.setup();
    this.store.save();
    this.post(new ChatMessage.ThreadAdded(record.toItem()));
    this.newController(record).open(spec.prompt());
    return record;
  }

  /**
   * Sends a prompt as if typed into the thread, opening it if it is not. A side chat lists its main thread again, so
   * its pane can show it. Must run on the EDT.
   */
  void prompt(ThreadStore.Record record, String prompt) {
    ChatController controller = this.controllers.get(record.id);
    if (controller != null) {
      controller.submit(prompt);
      return;
    }
    this.unarchive(SideChats.mainOf(this.store, record));
    this.unarchive(record);
    this.newController(record).open(prompt);
  }

  /** Resumes the threads whose limit reset, if the person asked for it. Must run on the EDT. */
  private void resumeDue() {
    if (this.panel == null) {
      return;
    }
    LimitResumes.Due due = this.limits.due(this.store.all(), record -> {
      ChatController controller = this.controllers.get(record.id);
      return controller != null && controller.state() != ChatController.State.IDLE
        && controller.state() != ChatController.State.STOPPED;
    });
    this.postUpdates(due.changed());
    for (LimitResumes.Resume resume : due.fire()) {
      String prompt = resume.prompt();
      this.prompt(resume.record(), prompt != null ? prompt : TakoBundle.message("limit.prompt.default"));
    }
  }

  /** Sends the thread's continue prompt once the usage limit it stopped on reset. */
  public void scheduleResume(String id) {
    this.changeLimit(id, this.limits::schedule);
  }

  public void cancelResume(String id) {
    this.changeLimit(id, this.limits::cancel);
  }

  /** @param prompt what the resume sends; blank for the default */
  public void setResumePrompt(String id, String prompt) {
    this.changeLimit(id, record -> this.limits.setPrompt(record, prompt));
  }

  /** Forgets the usage limit the thread stopped on, and its resume. */
  public void dismissLimit(String id) {
    this.changeLimit(id, this.limits::dismiss);
  }

  private void changeLimit(String id, Predicate<ThreadStore.Record> change) {
    ThreadStore.Record record = this.store.find(id);
    if (record != null && change.test(record)) {
      this.postUpdate(record);
    }
  }

  private void unarchive(ThreadStore.Record record) {
    this.postUpdates(this.unarchived(record));
  }

  /**
   * Lists an archived thread again, with its idle time starting anew. A snooze that ended while it was archived ends
   * now, as unread. A snoozed parent restored this way would hide the active thread, so that one's parents wake. The
   * side chats its archiving stopped come back too; their agents start once shown.
   *
   * @return the threads it changed, to post
   */
  private List<ThreadStore.Record> unarchived(ThreadStore.Record record) {
    List<ThreadStore.Record> changed = new ArrayList<>();
    if (!record.archived) {
      return changed;
    }
    long now = System.currentTimeMillis();
    record.archived = false;
    record.seenAt = now;
    if (ThreadUpkeep.isDue(record, now)) {
      record.snoozedUntil = 0;
      record.unread = true;
    }
    changed.add(record);
    if (!record.sideChat) {
      for (ThreadStore.Record sideChat : SideChats.of(this.store, record.id)) {
        changed.addAll(this.unarchived(sideChat));
      }
    }
    ThreadStore.Record active = this.store.find(this.store.activeId());
    if (active != null && this.store.isSnoozeHidden(active)) {
      changed.addAll(this.store.wakeWithAncestors(active));
    }
    return changed;
  }

  /**
   * The person opened the thread: it is read, a snooze over it ends and its idle time starts again.
   *
   * @return the threads it changed, to post
   */
  private List<ThreadStore.Record> seen(ThreadStore.Record record) {
    record.unread = false;
    record.seenAt = System.currentTimeMillis();
    List<ThreadStore.Record> changed = new ArrayList<>(this.store.wakeWithAncestors(record));
    changed.add(record);
    return changed;
  }

  /** Saves the threads once and tells the chat about each of them once. */
  void postUpdates(List<ThreadStore.Record> records) {
    if (records.isEmpty()) {
      return;
    }
    this.store.save();
    for (ThreadStore.Record record : new LinkedHashSet<>(records)) {
      this.post(new ChatMessage.ThreadUpdated(record.toItem()));
    }
  }

  /** Archives threads that are not the active one, with their side chats, and stops their agents, saving once. */
  void archiveAll(List<ThreadStore.Record> records) {
    List<ThreadStore.Record> archived = new ArrayList<>();
    for (ThreadStore.Record record : records) {
      archived.add(record);
      archived.addAll(SideChats.of(this.store, record.id));
    }
    for (ThreadStore.Record record : archived) {
      record.archived = true;
      this.limits.dismiss(record);
      this.disposeController(record.id);
    }
    this.postUpdates(archived);
  }

  public void setPinned(String id, boolean pinned) {
    ThreadStore.Record record = this.store.find(id);
    if (record != null && record.pinned != pinned && !this.store.isRun(id)) {
      record.pinned = pinned;
      this.postUpdate(record);
    }
  }

  /** Whether a shown side chat pane covers its main thread, as the chat is too narrow for both. */
  public void setSidePaneCovering(boolean covering) {
    this.isSidePaneCovering = covering;
  }

  /** The pane of the main thread shows the side chat, or none when null; a side chat on screen needs no balloon. */
  public void showsSideChat(String mainId, @Nullable String sideChatId) {
    if (sideChatId == null) {
      this.shownSideChats.remove(mainId);
      return;
    }
    this.shownSideChats.put(mainId, sideChatId);
    ThreadStore.Record sideChat = this.store.find(sideChatId);
    if (sideChat != null && sideChat.unread) {
      sideChat.unread = false;
      this.postUpdate(sideChat);
    }
  }

  /**
   * Starts a side chat of the thread, or of its main thread for a side chat, and shows it in the pane. Copies the main
   * thread's session when {@code forkPrompts} is above 0 and it has one, else attaches an excerpt of its conversation.
   * Must run on the EDT.
   *
   * @param forkPrompts how many prompts of the main thread's session to copy, 0 for a new session
   * @param first       the side chat's first prompt, sent unless its text is blank, else put in its composer
   * @return false when there is no such thread, so no side chat starts
   */
  public boolean startSideChat(String threadId, int forkPrompts, ChatMessage.Submit first) {
    ThreadStore.Record found = this.store.find(threadId);
    if (found == null) {
      return false;
    }
    ThreadStore.Record main = SideChats.mainOf(this.store, found);
    ChatController controller = this.controllers.get(main.id);
    String sessionId = main.sessionId;
    if (forkPrompts <= 0 || sessionId == null) {
      this.createOrRelease(main, null, 0, first, excerpt(main, controller));
      return true;
    }
    String folder = this.workFolder(main);
    AgentBackend backend = AgentRegistry.find(main.agent(), EnvironmentUtil.getEnvironmentMap());
    Async.inBackground(() -> {
      String forkId = null;
      try {
        forkId = backend.fork(folder, sessionId, forkPrompts);
      } catch (AgentException | RuntimeException e) {
        LOG.warn("Could not fork thread " + main.id + " for a side chat, attaching an excerpt instead", e);
      }
      int inherited = forkPrompts;
      if (forkId != null) {
        try {
          inherited = promptCount(backend.history(folder, forkId));
        } catch (RuntimeException e) {
          LOG.warn("Could not count the prompts of side chat session " + forkId, e);
        }
      }
      String session = forkId;
      int prompts = session == null ? 0 : inherited;
      ContextItem excerpt = session == null ? excerpt(main, controller) : null;
      Async.onEdt(() -> this.createOrRelease(main, session, prompts, first, excerpt));
    });
    return true;
  }

  /** Makes the side chat, else tells the main thread that it may start another. */
  private void createOrRelease(ThreadStore.Record main, @Nullable String sessionId, int inheritedPrompts,
    ChatMessage.Submit first, @Nullable ContextItem excerpt) {
    boolean isCreated = false;
    try {
      isCreated = this.createSideChat(main, sessionId, inheritedPrompts, first, excerpt);
    } finally {
      if (!isCreated) {
        this.post(new ChatMessage.ShowSideChat(main.id, true));
      }
    }
  }

  private static @Nullable ContextItem excerpt(ThreadStore.Record main, @Nullable ChatController controller) {
    return SideChats.excerpt(main.title, controller == null ? "" : controller.conversation(SideChats.EXCERPT_CHARS));
  }

  private static int promptCount(AgentHistory history) {
    int count = 0;
    if (history instanceof AgentHistory.Loaded loaded) {
      for (AgentEvent event : loaded.events()) {
        if (event instanceof AgentEvent.Prompt) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Makes the side chat unless the main thread was closed or archived meanwhile, then starts it.
   *
   * @return whether it made one
   */
  private boolean createSideChat(ThreadStore.Record main, @Nullable String sessionId, int inheritedPrompts,
    ChatMessage.Submit first, @Nullable ContextItem excerpt) {
    if (this.store.find(main.id) != main || main.archived) {
      return false;
    }
    ThreadStore.Record record = this.store.create(sessionId, SideChats.title(first.text()), main.projectPath,
      main.agent());
    record.parentId = main.id;
    record.sideChat = true;
    record.inheritedPrompts = inheritedPrompts;
    record.model = main.model;
    record.mode = main.mode;
    record.effort = main.effort;
    record.workPath = main.workPath;
    this.store.save();
    this.post(new ChatMessage.ThreadAdded(record.toItem()));
    List<ContextItem> context = new ArrayList<>(first.context());
    if (excerpt != null) {
      context.add(excerpt);
    }
    ChatController controller = this.newController(record);
    if (first.text().isBlank()) {
      controller.open();
      for (ContextItem item : context) {
        this.post(new ChatMessage.Context(item, null), record.id);
      }
      for (ImageAttachment image : first.images()) {
        this.post(new ChatMessage.Image(image), record.id);
      }
    } else {
      controller.open(new ChatMessage.Submit(first.text().strip(), context, first.images()));
    }
    this.post(new ChatMessage.ShowSideChat(record.id, true));
    return true;
  }

  /** Starts the agent of a side chat the pane shows, unless it runs. Doesn't select it. Must run on the EDT. */
  public void openSideChat(String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null || !record.sideChat || this.controllers.containsKey(id)) {
      return;
    }
    this.unarchive(record);
    this.newController(record).open();
  }

  /** Gives the thread the title the person typed. Ignores a blank one. */
  public void rename(String id, String typed) {
    ThreadStore.Record record = this.store.find(id);
    String title = ThreadTitles.manual(typed);
    if (record != null && title != null && !title.equals(record.title)) {
      record.title = title;
      this.postUpdate(record);
    }
  }

  public void markUnread(String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record != null && !record.archived && !record.unread) {
      record.unread = true;
      this.postUpdate(record);
    }
  }

  /**
   * Hides the thread and its sub threads until {@code until}, in epoch millis; its agent keeps working. Selects another
   * thread if that hides the active one. Not for archived threads or runs.
   */
  public void snooze(String id, long until) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null || record.archived || this.store.isRun(id) || until <= System.currentTimeMillis()) {
      return;
    }
    record.snoozedUntil = until;
    this.postUpdate(record);
    ThreadStore.Record active = this.store.find(this.store.activeId());
    if (active != null && this.store.isSnoozeHidden(active)) {
      this.selectAnotherIfActive(active.id);
    }
  }

  /** Ends the snooze that hides the thread, its own or its parents'. */
  public void wake(String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return;
    }
    List<ThreadStore.Record> woken = this.store.wakeWithAncestors(record);
    if (!woken.isEmpty()) {
      record.seenAt = System.currentTimeMillis();
      List<ThreadStore.Record> changed = new ArrayList<>(woken);
      changed.add(record);
      this.postUpdates(changed);
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

  /**
   * Starts a thread that implements the plan the thread's agent proposed, and selects it. It runs the same agent, model
   * and effort, not in plan mode. Shows failures.
   *
   * @param worktree work in a new git worktree on a new branch, else in the planning thread's folder
   * @return completes on the EDT with the new thread's id, or null when none started
   */
  public CompletableFuture<@Nullable String> implementPlan(String id, String plan, boolean worktree) {
    CompletableFuture<@Nullable String> done = new CompletableFuture<>();
    ThreadStore.Record source = this.store.find(id);
    if (source == null) {
      this.failPlan(done, TakoBundle.message("error.plan.thread.gone"));
      return done;
    }
    String folder = this.workFolder(source);
    Async.inBackground(() -> {
      try {
        if (folder.isEmpty() || !Files.isDirectory(Path.of(folder))) {
          Async.onEdt(() -> this.failPlan(done, TakoBundle.message("error.plan.folder", folder)));
          return;
        }
        GitWorktree.Worktree created = null;
        if (worktree) {
          String title = Plans.title(plan);
          created = GitWorktree.create(Path.of(folder), title != null ? title : Plans.UNTITLED,
            EnvironmentUtil.getEnvironmentMap());
          if (created == null) {
            Async.onEdt(() -> this.failPlan(done, TakoBundle.message("error.plan.worktree", folder)));
            return;
          }
        }
        GitWorktree.Worktree made = created;
        Async.onEdt(() -> this.startPlanThread(done, source, plan, folder, made));
      } catch (RuntimeException e) {
        LOG.warn("Could not start a thread implementing the plan of thread " + id, e);
        Async.onEdt(() -> this.failPlan(done, Objects.toString(e.getMessage(), e.toString())));
      }
    });
    return done;
  }

  /**
   * Completes with null when the thread did not start, even if it was stored, so the plan's approval stays.
   *
   * @param folder   the planning thread's folder
   * @param worktree the worktree made for the new thread, removed again when no thread got it
   */
  private void startPlanThread(CompletableFuture<@Nullable String> done, ThreadStore.Record source, String plan,
    String folder, @Nullable GitWorktree.Worktree worktree) {
    if (this.store.find(source.id) != source) {
      this.dropWorktree(folder, worktree);
      this.failPlan(done, TakoBundle.message("error.plan.thread.gone"));
      return;
    }
    ThreadStore.Record record;
    try {
      record = this.planThread(source, plan, worktree);
    } catch (RuntimeException e) {
      LOG.warn("Could not store a thread implementing the plan of thread " + source.id, e);
      this.dropWorktree(folder, worktree);
      this.failPlan(done, Objects.toString(e.getMessage(), e.toString()));
      return;
    }
    try {
      this.post(new ChatMessage.ThreadAdded(record.toItem()));
      this.store.setActiveId(record.id);
      this.post(new ChatMessage.Select(record.id));
      this.newController(record).open(Plans.implementPrompt(plan));
    } catch (RuntimeException e) {
      LOG.warn("Could not open the thread implementing the plan of thread " + source.id, e);
      this.failPlan(done, Objects.toString(e.getMessage(), e.toString()));
      return;
    }
    done.complete(record.id);
  }

  private void dropWorktree(String repository, @Nullable GitWorktree.Worktree worktree) {
    if (worktree != null) {
      Async.inBackground(() -> removeWorktree(Path.of(repository), worktree));
    }
  }

  private static void removeWorktree(Path repository, GitWorktree.Worktree worktree) {
    try {
      Worktrees.remove(repository, worktree.folder(), worktree.branch(), EnvironmentUtil.getEnvironmentMap());
    } catch (Worktrees.GitException e) {
      LOG.warn("Could not remove the worktree " + worktree.folder(), e);
    }
  }

  private void failPlan(CompletableFuture<@Nullable String> done, String error) {
    this.post(new ChatMessage.Error(error));
    done.complete(null);
  }

  /** Stores a thread for implementing the plan, beside the planning thread and like it but not in plan mode. */
  /** @param worktree the worktree made for it, whose {@code .worktreesetup} runs first; null to work where the source does */
  ThreadStore.Record planThread(ThreadStore.Record source, String plan, @Nullable GitWorktree.Worktree worktree) {
    String projectPath = source.projectPath;
    if (projectPath != null && this.store.findProject(projectPath) == null) {
      this.store.addProject(projectPath, Path.of(projectPath).getFileName().toString());
      this.postProjects();
    }
    String agent = source.agent();
    String title = Plans.title(plan);
    ThreadStore.Record record = this.store.create(null, ChatController.titleOf(title != null
      ? TakoBundle.message("thread.implement.title", title)
      : TakoBundle.message("thread.implement.title.default")), projectPath, agent);
    String defaultMode = ClaudeBackend.ID.equals(agent) ? ChatSettings.state().claudeDefaults().mode() : "";
    record.model = source.model;
    record.mode = Plans.implementMode(agent, source.mode, defaultMode);
    record.effort = source.effort;
    record.workPath = worktree != null ? worktree.folder().toString() : source.workPath;
    record.pendingSetup = worktree != null
      ? ThreadStore.PendingSetup.of(worktree.repository(), worktree.folder())
      : null;
    this.store.save();
    return record;
  }

  /** Asks where to save the plan as Markdown, in the thread's folder by default, then opens the file. */
  public void savePlan(String id, String plan) {
    Async.onEdt(() -> this.choosePlanFile(id, plan));
  }

  private void choosePlanFile(String id, String plan) {
    ThreadStore.Record record = this.store.find(id);
    String folder = record != null ? this.workFolder(record) : this.folderOf((String) null);
    Path directory = folder.isEmpty() || !Files.isDirectory(Path.of(folder)) ? null : Path.of(folder);
    FileSaverDescriptor descriptor = new FileSaverDescriptor(TakoBundle.message("dialog.save.plan.title"),
      TakoBundle.message("dialog.save.plan.description"));
    descriptor.withExtensionFilter("md");
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, this.project)
      .save(directory, Plans.fileName(plan));
    if (target == null) {
      return;
    }
    try {
      VirtualFile file = IdeFiles.writeText(target.getFile().toPath(), Plans.fileText(plan));
      FileEditorManager.getInstance(this.project).openFile(file, true);
    } catch (IOException e) {
      this.post(new ChatMessage.Error(TakoBundle.message("error.plan.save", Objects.toString(e.getMessage(), ""))));
    }
  }

  /**
   * Whether the thread's agent can name it now: it has a conversation to read and a one-shot call. Looks for the
   * agent's CLI, so call off the EDT.
   *
   * @param shown whether the chat holds messages of the thread
   */
  public boolean canRegenerateTitle(String id, boolean shown) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return false;
    }
    AgentBackend backend = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap());
    return (shown || record.sessionId != null && backend.hasStoredHistory()) && backend.canAsk();
  }

  /**
   * Has the thread's agent name it from its first request and latest messages, and saves the title. Takes the
   * conversation the chat holds, else reads the session's history when the agent keeps it in files. Shows failures.
   * Keeps the thread as it is if it was removed or got another title meanwhile.
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
    String oldTitle = record.title;
    AgentBackend backend = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap());
    Async.inBackground(() -> {
      try {
        String title = askTitle(backend, folder, sessionId, shown);
        Async.onEdt(() -> {
          if (this.store.find(id) == record && oldTitle.equals(record.title)) {
            record.title = title;
            this.postUpdate(record);
          }
          done.complete(null);
        });
      } catch (AgentException | RuntimeException e) {
        if (e instanceof RuntimeException) {
          LOG.warn("Could not regenerate the title of thread " + id, e);
        }
        Async.onEdt(() -> {
          this.post(
            new ChatMessage.Error(TakoBundle.message("error.title", Objects.toString(e.getMessage(), e.toString()))));
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
    String prompt = ThreadTitles.prompt(conversation);
    if (prompt == null) {
      throw new AgentException(TakoBundle.message("error.title.no.conversation"));
    }
    String title = ThreadTitles.title(backend.ask(Path.of(folder), prompt));
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

  /** Puts every open thread's queued prompts in the prompt stash, as the project closes. */
  void stashQueued() {
    for (ChatController controller : this.controllers.values()) {
      controller.stashQueued();
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
  void closeAll(List<String> closed) {
    String activeId = this.store.activeId();
    List<String> ids = new ArrayList<>(closed);
    for (String id : closed) {
      for (ThreadStore.Record sideChat : SideChats.of(this.store, id)) {
        if (!ids.contains(sideChat.id)) {
          ids.add(sideChat.id);
        }
      }
    }
    for (String id : ids) {
      ThreadStore.Record record = this.store.find(id);
      if (record != null) {
        // Checkpoint refs are shared with the thread's worktree, so its project folder still reaches them once a bench
        // removed the worktree.
        String folder = this.folderOf(record);
        if (!folder.isEmpty()) {
          Async.inBackground(() -> Checkpoints.forget(Path.of(folder), id, 0, EnvironmentUtil.getEnvironmentMap()));
        }
        this.disposeController(id);
        this.withdrawTools(id);
        this.shownSideChats.remove(id);
        this.store.remove(id);
        this.post(new ChatMessage.ThreadRemoved(id));
      }
    }
    if (activeId != null && ids.contains(activeId)) {
      this.selectAnotherIfActive(activeId);
    }
  }

  public void setArchived(String id, boolean archived) {
    if (archived) {
      this.archive(id);
    } else {
      this.select(id);
    }
  }

  /**
   * Archives the thread and its side chats and stops their agents. Selects the first open thread in place of the
   * active one, or a new thread when none is open.
   * @return what undoing it needs, or null when there is no such thread
   */
  public @Nullable Archived archive(String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return null;
    }
    this.stopArchived(record);
    for (ThreadStore.Record sideChat : SideChats.of(this.store, record.id)) {
      if (!sideChat.archived) {
        this.stopArchived(sideChat);
      }
    }
    if (!record.id.equals(this.store.activeId())) {
      return new Archived(record.id, null, false);
    }
    ThreadStore.Record next = this.firstOpenThread(record.id);
    if (next != null) {
      this.select(next.id);
      return new Archived(record.id, next.id, false);
    }
    this.newThread(null, null, null, null);
    return new Archived(record.id, this.store.activeId(), true);
  }

  private void stopArchived(ThreadStore.Record record) {
    record.archived = true;
    this.watcher.stop(record.id);
    this.limits.dismiss(record);
    this.disposeController(record.id);
    this.postUpdate(record);
  }

  /**
   * Watches the pull request of the thread's branch, as the header shows it. Shows why when it can't.
   *
   * @return completes on the EDT once the watch started or failed
   */
  public CompletableFuture<Void> watchPullRequest(String id) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    Async.inBackground(() -> {
      String failure = null;
      try {
        this.watcher.start(id, null, false);
      } catch (WatchException e) {
        failure = watchFailure(e);
      } catch (RuntimeException e) {
        LOG.warn("Could not watch the pull request of thread " + id, e);
        failure = TakoBundle.message("watch.failed.other", Objects.toString(e.getMessage(), e.toString()));
      }
      String text = failure;
      Async.onEdt(() -> {
        if (text != null) {
          this.post(new ChatMessage.Notice(text), id);
        }
        done.complete(null);
      });
    });
    return done;
  }

  public void unwatchPullRequest(String id) {
    this.watcher.stop(id);
  }

  PullRequestWatcher watcher() {
    return this.watcher;
  }

  ChildWakes wakes() {
    return this.wakes;
  }

  /**
   * Wakes the threads that handed this one a prompt with wake set, once it is done. Like the pull request watch, skips
   * a thread that is gone or archived and opens one that is closed. Must run on the EDT.
   */
  private void wakeParents(ThreadStore.Record record) {
    ChatController controller = this.controllers.get(record.id);
    if (controller == null || this.store.find(record.id) != record) {
      return;
    }
    ChatController.State state = controller.state();
    List<String> parents = this.wakes.changed(record.id, state);
    if (parents.isEmpty()) {
      return;
    }
    String prompt = ThreadDonePrompt.compose(record.id, record.title, record.agent(), state, controller.lastAnswer(),
      controller.error());
    for (String id : parents) {
      ThreadStore.Record parent = this.store.find(id);
      if (parent != null && !parent.archived && this.panel != null && !this.project.isDisposed()) {
        this.prompt(parent, prompt);
      }
    }
  }

  static AgentCatalog agents() {
    return AGENTS;
  }

  /** What the chat says when a watch did not start, or null when nothing needs saying. */
  private static @Nullable String watchFailure(WatchException e) {
    return switch (e.reason()) {
      case NO_THREAD, ARCHIVED, STARTING -> null;
      case NO_PULL_REQUEST -> TakoBundle.message("watch.failed.no.pull.request");
      case BAD_URL -> TakoBundle.message("watch.failed.bad.url", e.detail());
      case NOT_OPEN -> TakoBundle.message("watch.failed.not.open", e.detail());
      case MISSING -> TakoBundle.message("watch.failed.missing", e.detail());
      case LOGGED_OUT -> TakoBundle.message("watch.failed.logged.out", e.detail());
      case RATE_LIMITED -> TakoBundle.message("watch.failed.rate.limited", e.detail());
      case FAILED -> TakoBundle.message("watch.failed.other", e.detail());
    };
  }

  /** Lists an archived thread again without selecting it. */
  public void restore(String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record != null) {
      this.unarchive(record);
    }
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
      if (!record.archived && !record.id.equals(except) && !this.store.isSnoozeHidden(record)
        && SideChats.mainOf(this.store, record) == record) {
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

  /**
   * Runs another agent in the thread. A thread with a conversation switches to it, see
   * {@link ChatController#switchAgent}; one without simply starts over with it. Bench runs keep their agent, and side
   * chats their main thread's. Must run on the EDT.
   */
  public void setAgent(String id, String agent) {
    ThreadStore.Record record = this.store.find(id);
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    AgentBackend backend = AgentRegistry.find(agent, environment);
    if (record == null || record.sideChat || backend.id().equals(record.agent()) || this.store.isRun(id)) {
      return;
    }
    ChatController controller = this.controllers.get(id);
    boolean hasPast = record.agentSwitches > 0;
    if (controller == null) {
      if (record.sessionId != null || hasPast) {
        return;
      }
    } else if (controller.canSwitch() && (hasPast || controller.hasConversation())) {
      if (!controller.switchAgent(backend)) {
        this.postBusy(record);
      }
      return;
    } else if (!controller.canSwitch() && (hasPast || record.sessionId != null || controller.hasPromptInFlight())) {
      this.postBusy(record);
      return;
    }
    record.useAgent(backend.id());
    if (record.costUsd > 0) {
      record.endSession();
    } else {
      record.sessionId = null;
    }
    record.handoffFrom = null;
    this.disposeController(record.id);
    this.postUpdate(record);
    this.select(record.id);
  }

  /** Tells the thread its agent cannot change now. */
  private void postBusy(ThreadStore.Record record) {
    String name = AgentRegistry.find(record.agent(), EnvironmentUtil.getEnvironmentMap()).displayName();
    this.post(new ChatMessage.Notice(TakoBundle.message("error.switch.busy", name)), record.id);
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

  private static void deleteOldPastes() {
    try {
      PastedText.deleteOlderThan(PastedText.folder(), Instant.now().minus(PastedText.MAX_AGE));
    } catch (IOException e) {
      LOG.debug("Could not delete old pasted text files", e);
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
    this.wakes.closed(id);
    if (controller != null) {
      Disposer.dispose(controller);
      this.fireActivity();
    }
  }

  /**
   * Whether the person sees the thread: the tool window shows it as the active one, unless a side chat pane covers it,
   * or as the side chat beside the active one.
   */
  boolean isShown(String id) {
    ToolWindow window = this.toolWindow;
    ThreadStore.Record record = this.store.find(id);
    if (window == null || !window.isVisible() || record == null) {
      return false;
    }
    ThreadStore.Record main = SideChats.mainOf(this.store, record);
    boolean isOnScreen = main == record
      ? !this.isSidePaneCovering || !this.shownSideChats.containsKey(main.id)
      : record.id.equals(this.shownSideChats.get(main.id));
    return main.id.equals(this.store.activeId()) && isOnScreen;
  }

  /** What the thread's controller tells the service, for tests; null for an unknown thread. */
  @Nullable
  ChatHost host(String id) {
    ThreadStore.Record record = this.store.find(id);
    return record == null ? null : new Host(record);
  }

  void postUpdate(ThreadStore.Record record) {
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

  /** Shows a balloon about the thread unless the person sees it already. Must run on the EDT. */
  private void requestAttention(ThreadStore.Record record, String reason) {
    if (this.isShown(record.id)) {
      return;
    }
    Notification notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(TOOL_WINDOW_ID)
      .createNotification(reason, record.title, NotificationType.INFORMATION);
    notification.addAction(NotificationAction.createSimpleExpiring(TakoBundle.message("notification.show"),
      () -> this.show(record.id)));
    notification.notify(this.project);
  }

  /** An open thread and what its agent does. */
  public record ThreadActivity(
    String id,
    String title,
    ChatController.State state
  ) {
  }

  /**
   * A thread just archived.
   * @param replacement      the thread selected in its place, or null when it was not the active one
   * @param isNewReplacement whether the replacement is a new thread, made because no other was open
   */
  public record Archived(
    String thread,
    @Nullable String replacement,
    boolean isNewReplacement
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

    /** The first prompt's title, unless the thread is gone or already has a name, e.g. one the person gave it. */
    @Override
    public void setTitle(String title) {
      if (this.isListed() && ThreadStore.Record.DEFAULT_TITLE.equals(this.record.title)) {
        this.record.title = title;
        ChatService.this.postUpdate(this.record);
      }
    }

    /** Leaves an archived thread alone: its agent was stopped, a report still queued for the EDT comes too late. */
    @Override
    public void needsLook(boolean asks) {
      if (!this.isListed() || this.record.archived) {
        return;
      }
      List<ThreadStore.Record> changed = new ArrayList<>();
      if (asks) {
        changed.addAll(ChatService.this.store.wakeWithAncestors(this.record));
      }
      if (!this.record.unread && !ChatService.this.isShown(this.record.id)) {
        this.record.unread = true;
        changed.add(this.record);
      }
      ChatService.this.postUpdates(changed);
    }

    /** Whether the record is still the thread's, so an update does not bring a closed thread back to the list. */
    private boolean isListed() {
      return ChatService.this.store.find(this.record.id) == this.record;
    }

    /** Not for a thread closed meanwhile, which the chat would list again. */
    @Override
    public void recordChanged() {
      if (ChatService.this.store.find(this.record.id) == this.record) {
        ChatService.this.postUpdate(this.record);
      }
    }

    @Override
    public void activity() {
      this.record.updatedAt = System.currentTimeMillis();
      ChatService.this.postUpdate(this.record);
    }

    @Override
    public void stateChanged() {
      ChatService.this.fireActivity();
      ChatService.this.wakeParents(this.record);
    }

    @Override
    public void prompted(String typed) {
      if (LimitResumes.movesOn(typed)) {
        ChatService.this.dismissLimit(this.record.id);
      }
    }

    /** Ignored for a thread closed or archived meanwhile, so a late call from its agent does not bring it back. */
    @Override
    public void limitReached(@Nullable String label, @Nullable Long resetsAt, boolean namesReset) {
      if (ChatService.this.store.find(this.record.id) != this.record || this.record.archived) {
        return;
      }
      long reset = resetsAt != null ? resetsAt : 0;
      List<ThreadStore.Record> all = ChatService.this.store.all();
      ChatService.this.postUpdates(namesReset
        ? ChatService.this.limits.onReset(this.record, label, reset, all)
        : ChatService.this.limits.onLimit(this.record, label, reset, all));
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
      try {
        directories.add(PastedText.createFolder(PastedText.folder()).toString());
      } catch (IOException e) {
        LOG.warn("Could not create the folder for pasted text", e);
      }
      return directories;
    }

    @Override
    public @Nullable String toolsUrl() {
      return ChatService.this.toolsUrl(this.record.id);
    }

    @Override
    public void started(AgentInfo info) {
      AGENTS.remember(info);
    }

    @Override
    public Path pastFile() {
      return ChatService.this.store.pastFile(this.record.id);
    }

    @Override
    public @Nullable String busyThreadIn(String topLevel) {
      Path top = Path.of(topLevel).toAbsolutePath().normalize();
      Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
      for (Map.Entry<String, ChatController> entry : ChatService.this.controllers.entrySet()) {
        ChatController other = entry.getValue();
        String folder = other.gitFolder();
        if (entry.getKey().equals(this.record.id) || !other.state().isInTurn() || folder == null) {
          continue;
        }
        Path otherFolder = Path.of(folder).toAbsolutePath().normalize();
        String otherTop = GitCommands.topLevel(otherFolder, environment);
        boolean sharesFiles = top.startsWith(otherFolder) || otherTop != null && top.equals(Path.of(otherTop));
        if (sharesFiles) {
          ThreadStore.Record record = ChatService.this.store.find(entry.getKey());
          return record != null ? record.title : "";
        }
      }
      return null;
    }

    @Override
    public void requestAttention(String reason) {
      ChatService.this.requestAttention(this.record, reason);
    }
  }

  /** What the pull request watcher needs of the open threads and the chat. */
  private final class WatchHost implements PullRequestWatcher.Host {

    @Override
    public @Nullable ChatController.State state(String threadId) {
      ChatController controller = ChatService.this.controllers.get(threadId);
      return controller == null ? null : controller.state();
    }

    @Override
    public Path folder(ThreadStore.Record record) {
      String folder = record.folder(ChatService.this.project.getBasePath());
      if (folder != null && Files.isDirectory(Path.of(folder))) {
        return Path.of(folder);
      }
      String basePath = ChatService.this.project.getBasePath();
      return basePath != null && Files.isDirectory(Path.of(basePath))
        ? Path.of(basePath)
        : Path.of(System.getProperty("user.home"));
    }

    @Override
    public @Nullable GitInfo.PullRequest lookup(ThreadStore.Record record) {
      String folder = record.folder(ChatService.this.project.getBasePath());
      if (folder == null || !Files.isDirectory(Path.of(folder))) {
        return null;
      }
      return GitInfo.pullRequest(Path.of(folder), EnvironmentUtil.getEnvironmentMap());
    }

    @Override
    public boolean deliver(ThreadStore.Record record, PullRequestWatch watch, String prompt) {
      AtomicBoolean isDelivered = new AtomicBoolean();
      ApplicationManager.getApplication().invokeAndWait(() -> {
        ChatController controller = ChatService.this.controllers.get(record.id);
        ChatController.State state = controller == null ? null : controller.state();
        if (ChatService.this.panel == null || ChatService.this.project.isDisposed()
          || ChatService.this.store.find(record.id) != record || record.archived || record.watch != watch
          || !PullRequestWatcher.canWake(state)) {
          return;
        }
        ChatService.this.prompt(record, prompt);
        isDelivered.set(true);
      }, ModalityState.any());
      return isDelivered.get();
    }

    @Override
    public void stopped(ThreadStore.Record record, String number, PullRequestWatcher.Stop reason, String message) {
      Forge forge = Forge.ofNumber(number);
      String cli = (forge != null ? forge : Forge.GITHUB).cli();
      String text = switch (reason) {
        case MISSING -> TakoBundle.message("watch.stopped.missing", number, cli);
        case LOGGED_OUT -> TakoBundle.message("watch.stopped.logged.out", number, cli);
        case FAILURES -> TakoBundle.message("watch.stopped.failures", number, PullRequestWatcher.MAX_FAILURES, message);
      };
      ChatService.this.post(new ChatMessage.Notice(text), record.id);
      Async.onEdt(() -> ChatService.this.requestAttention(record, text));
    }

    @Override
    public void changed(ThreadStore.Record record) {
      Async.onEdt(() -> {
        if (ChatService.this.store.find(record.id) == record) {
          ChatService.this.post(new ChatMessage.ThreadUpdated(record.toItem()));
        }
      });
    }
  }
}
