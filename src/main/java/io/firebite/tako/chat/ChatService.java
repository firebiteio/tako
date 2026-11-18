package io.firebite.tako.chat;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.ChatHost;
import io.firebite.tako.chat.thread.ChatView;
import io.firebite.tako.chat.thread.ThreadTitles;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.project.Checkpoints;
import io.firebite.tako.mcp.McpServer;
import io.firebite.tako.settings.ApiKeyStore;
import io.firebite.tako.settings.AuthMode;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.ChatPanel;
import io.firebite.tako.ui.bridge.message.ChatError;
import io.firebite.tako.ui.bridge.message.ThreadMessage;
import io.firebite.tako.ui.bridge.message.WorkspaceMessage;
import io.firebite.tako.ui.bridge.message.thread.Context;
import io.firebite.tako.ui.bridge.message.thread.Notice;
import io.firebite.tako.ui.bridge.message.thread.Submit;
import io.firebite.tako.ui.bridge.message.workspace.AgentOption;
import io.firebite.tako.ui.bridge.message.workspace.Agents;
import io.firebite.tako.ui.bridge.message.workspace.ChatProject;
import io.firebite.tako.ui.bridge.message.workspace.RunChanges;
import io.firebite.tako.ui.bridge.message.workspace.Select;
import io.firebite.tako.ui.bridge.message.workspace.Settings;
import io.firebite.tako.ui.bridge.message.workspace.ShowSideChat;
import io.firebite.tako.ui.bridge.message.workspace.ThreadAdded;
import io.firebite.tako.ui.bridge.message.workspace.ThreadItem;
import io.firebite.tako.ui.bridge.message.workspace.ThreadRemoved;
import io.firebite.tako.ui.bridge.message.workspace.ThreadUpdated;
import io.firebite.tako.ui.bridge.message.workspace.Threads;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** The project's chat threads. They share one panel in the Tako tool window, with one controller per opened thread. */
@Service(Service.Level.PROJECT)
public final class ChatService implements Disposable {
  public static final String TOOL_WINDOW_ID = "Tako";
  private static final Logger LOG = Logger.getInstance(ChatService.class);
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
  /** One at a time, so the chat gets each agent list in the order the checks began. */
  private static final Executor AGENT_CHECKS = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tako agent checks",
    1);

  private final Project project;
  private final ThreadStore store;
  private final Map<String, ChatController> controllers = new ConcurrentHashMap<>();
  private final ProjectFolders folders;
  private final SessionQueries sessions;
  private final ThreadTitler titles;
  private final PlanThreads plans;
  private final SideChatStarter sideChats;
  private final PullRequestWatches watches;
  private final Benches benches;
  private final ThreadUpkeep upkeep;
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
    this.folders = new ProjectFolders(project, this, this.store);
    this.sessions = new SessionQueries(this, this.store);
    this.titles = new ThreadTitler(this, this.store);
    this.plans = new PlanThreads(project, this, this.store);
    this.sideChats = new SideChatStarter(this, this.store);
    this.watches = new PullRequestWatches(project, this, this.store);
    this.benches = new Benches(project, this, this.store);
    this.upkeep = new ThreadUpkeep(this, this.store);
  }

  public static ChatService getInstance(Project project) {
    return project.getService(ChatService.class);
  }

  public ProjectFolders folders() {
    return this.folders;
  }

  public SessionQueries sessions() {
    return this.sessions;
  }

  public ThreadTitler titles() {
    return this.titles;
  }

  public PlanThreads plans() {
    return this.plans;
  }

  public SideChatStarter sideChats() {
    return this.sideChats;
  }

  public PullRequestWatches watches() {
    return this.watches;
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
    Async.inBackground(this.sessions::warmUsage);
    Async.inBackground(ChatService::deleteOldPastes);
    ScheduledExecutorService scheduler = AppExecutorUtil.getAppScheduledExecutorService();
    ScheduledFuture<?> gitRefresh = scheduler.scheduleWithFixedDelay(this::refreshActiveGit, GIT_REFRESH_SECONDS,
      GIT_REFRESH_SECONDS, TimeUnit.SECONDS);
    ScheduledFuture<?> watchTicks = scheduler.scheduleWithFixedDelay(this.watches.watcher()::tick,
      WATCH_TICK_SECONDS, WATCH_TICK_SECONDS, TimeUnit.SECONDS);
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

  /** Whether the chat panel is in the tool window. */
  boolean isAttached() {
    return this.panel != null;
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
      this.post(new Context(item, null), threadId);
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
    this.post(new Settings(settings.parallelRuns, settings.steerWhileWorking));
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

  /**
   * Checks each agent's setup again and starts again every open thread whose agent failed to start, after the settings
   * or programs that may have broken it changed. Must run on the EDT.
   */
  public static void retryFailedStartsInOpenProjects() {
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      ChatService service = ChatService.getInstance(project);
      service.postAgents();
      for (ChatController controller : service.controllers.values()) {
        controller.retryFailedStart();
      }
    }
  }

  /** Signs Claude Code in with an API key, kept in the password safe, then retries failed starts. Must run on the EDT. */
  public static void useClaudeApiKey(String apiKey) {
    ChatSettings.state().authMode = AuthMode.API_KEY;
    Async.inBackground(() -> {
      ApiKeyStore.save(apiKey);
      Async.onEdt(ChatService::retryFailedStartsInOpenProjects);
    });
  }

  /** Lets Claude Code use the login of its own CLI, then retries failed starts. Must run on the EDT. */
  public static void useClaudeCliLogin() {
    ChatSettings.state().authMode = AuthMode.CLI_LOGIN;
    retryFailedStartsInOpenProjects();
  }

  private void showInitialState() {
    this.post(new ChatProject(this.project.getBasePath(), this.project.getName()));
    this.postSettings();
    this.postAgents();
    this.folders.post();
    this.benches.post();
    List<ThreadItem> items = new ArrayList<>();
    for (ThreadStore.Record record : this.store.all()) {
      items.add(record.toItem());
    }
    this.post(new Threads(items));

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
  public void newThread(@Nullable String projectPath, @Nullable String agent, @Nullable Submit prompt) {
    ThreadStore.Record record = this.newThread(null, null, this.folders.threadPath(projectPath), agent);
    if (prompt != null) {
      this.post(prompt, record.id);
    }
  }

  /** @param agent the agent to run, or the default one when null */
  private ThreadStore.Record newThread(@Nullable String sessionId, @Nullable String title, @Nullable String projectPath,
    @Nullable String agent) {
    this.folders.ensure(projectPath);
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    String agentId = agent != null ? AgentRegistry.find(agent, environment).id() : AgentRegistry.defaultId(environment);
    ThreadStore.Record record = this.store.create(sessionId, title, projectPath, agentId);
    this.post(new ThreadAdded(record.toItem()));
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
      this.post(new ShowSideChat(record.id, false));
      return;
    }
    this.store.setActiveId(record.id);
    List<ThreadStore.Record> changed = this.unarchived(record);
    changed.addAll(this.seen(record));
    this.postUpdates(changed);
    this.post(new Select(record.id));
    ChatController existing = this.controllers.get(record.id);
    if (existing != null) {
      Async.inBackground(existing::refreshGit);
      return;
    }
    this.newController(record).open();
  }

  /** Shows a new thread in the list and starts its agent without selecting it. */
  void openThread(ThreadStore.Record record) {
    this.post(new ThreadAdded(record.toItem()));
    this.newController(record).open();
  }

  ChatController newController(ThreadStore.Record record) {
    ChatController controller = new ChatController(this.project, new ThreadView(record.id),
      new ThreadHost(this, this.store, record), record,
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
    this.post(new ThreadAdded(record.toItem()));
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

  LimitResumes limits() {
    return this.limits;
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

  void unarchive(ThreadStore.Record record) {
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
      this.post(new ThreadUpdated(record.toItem()));
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
        this.post(new ChatError(e.getMessage()));
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
        this.post(new ThreadAdded(record.toItem()));
        this.select(record.id);
      });
    });
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

  void fireActivity() {
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

  /** The open thread's controller, null for a thread not open in Tako. */
  public @Nullable ChatController controller(String id) {
    return this.controllers.get(id);
  }

  /** The open threads' controllers, by thread id. */
  Map<String, ChatController> openControllers() {
    return Collections.unmodifiableMap(this.controllers);
  }

  /** The folder the thread works in. */
  String workFolder(ThreadStore.Record record) {
    String folder = record.folder(this.project.getBasePath());
    return folder != null ? folder : "";
  }

  /** Where the thread's agent reaches its {@link ThreadTools}. Starts the server once. Null if it cannot start. */
  synchronized @Nullable String toolsUrl(String id) {
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
        String folder = this.folders.folderOf(record.projectPath);
        if (!folder.isEmpty()) {
          Async.inBackground(() -> Checkpoints.forget(Path.of(folder), id, 0, EnvironmentUtil.getEnvironmentMap()));
        }
        this.disposeController(id);
        this.withdrawTools(id);
        this.shownSideChats.remove(id);
        this.store.remove(id);
        this.post(new ThreadRemoved(id));
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
    this.watches.stop(record.id);
    this.limits.dismiss(record);
    this.disposeController(record.id);
    this.postUpdate(record);
  }

  ChildWakes wakes() {
    return this.wakes;
  }

  /**
   * Wakes the threads that handed this one a prompt with wake set, once it is done. Like the pull request watch, skips
   * a thread that is gone or archived and opens one that is closed. Must run on the EDT.
   */
  void wakeParents(ThreadStore.Record record) {
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
      this.newThread(sessionId, title, this.folders.threadPath(projectPath), agent);
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
    this.post(new Notice(TakoBundle.message("error.switch.busy", name)), record.id);
  }

  /** Must run on the EDT. */
  public void createBench(BenchRequest request) {
    String projectPath = this.folders.threadPath(request.projectPath());
    this.benches.create(request, projectPath, this.folders.folderOf(projectPath));
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
  public @Nullable List<RunChanges> compareBench(String benchId) {
    return this.benches.compare(benchId);
  }

  /** Tells the chat which agents a thread can run and what keeps each from starting. Checks in the background. */
  public void postAgents() {
    AGENT_CHECKS.execute(() -> {
      List<AgentOption> agents = new ArrayList<>();
      for (AgentBackend backend : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
        agents.add(new AgentOption(backend.id(), backend.displayName(), backend.setupProblem()));
      }
      this.post(new Agents(agents));
    });
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
    return record == null ? null : new ThreadHost(this, this.store, record);
  }

  void postUpdate(ThreadStore.Record record) {
    this.store.save();
    this.post(new ThreadUpdated(record.toItem()));
  }

  void post(WorkspaceMessage message) {
    ChatPanel chatPanel = this.panel;
    if (chatPanel != null) {
      chatPanel.post(message);
    }
  }

  void post(ThreadMessage message, String threadId) {
    ChatPanel chatPanel = this.panel;
    if (chatPanel != null) {
      chatPanel.post(message, threadId);
    }
  }

  private @Nullable ToolWindow toolWindow() {
    return ToolWindowManager.getInstance(this.project).getToolWindow(TOOL_WINDOW_ID);
  }

  /** Shows a balloon about the thread unless the person sees it already. Must run on the EDT. */
  void requestAttention(ThreadStore.Record record, String reason) {
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
    public void post(ThreadMessage message) {
      ChatService.this.post(message, this.threadId);
    }
  }
}
