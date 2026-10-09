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
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.agent.AgentSessionInfo;
import io.firebite.tako.agent.claude.transcript.SessionStore;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.chat.thread.ChatHost;
import io.firebite.tako.chat.thread.ChatView;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.project.ProjectIcons;
import io.firebite.tako.mcp.McpServer;
import io.firebite.tako.settings.ChatSettings;
import io.firebite.tako.ui.ChatPanel;
import io.firebite.tako.ui.bridge.PageMessage;
import io.firebite.tako.ui.bridge.PageOp;
import io.firebite.tako.ui.bridge.PageRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/** The project's chat threads. They share one page in the Tako tool window, with one controller per opened thread. */
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
  /** Serves each thread's {@link ThreadTools}, keyed by thread id. Guarded by {@code this}. */
  private @Nullable McpServer toolServer;
  private @Nullable ChatPanel panel;
  private @Nullable ToolWindow toolWindow;

  public ChatService(Project project) {
    this.project = project;
    this.store = ThreadStore.getInstance(project);
    this.benches = new Benches(project, this, this.store);
  }

  public static ChatService getInstance(Project project) {
    return project.getService(ChatService.class);
  }

  /** Puts the chat page into the tool window. Must run on the EDT. */
  public void attach(ToolWindow window) {
    this.toolWindow = window;
    Content content = ContentFactory.getInstance().createContent(null, "", false);
    content.setCloseable(false);
    ChatPanel chatPanel = new ChatPanel(content, this.project);
    chatPanel.setHandler(this::handle);
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
  }

  /** Must run on the EDT. */
  public void openNewChat() {
    ToolWindow window = this.toolWindow();
    if (window != null) {
      window.activate(() -> this.newThread(null, null, null, null));
    }
  }

  /** Adds context to the selected thread. Must run on the EDT. */
  public void addContext(ContextItem item, @Nullable String prefill) {
    this.withActiveController(controller -> controller.addContext(item, prefill));
  }

  /** Attaches image files to the selected thread's composer. Must run on the EDT. */
  public void addImages(List<Path> files) {
    this.withActiveController(controller -> Async.inBackground(() -> controller.attachFiles(files)));
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

  private void handle(PageRequest request) {
    if (request.op() instanceof PageOp.ThreadOp op) {
      ChatController controller = request.thread() == null ? null : this.controllers.get(request.thread());
      if (controller != null) {
        controller.handle(op);
      } else {
        LOG.warn("Chat op " + op + " for unknown thread " + request.thread());
      }
      return;
    }
    switch ((PageOp.WorkspaceOp) request.op()) {
      case PageOp.Ready ready -> Async.onEdt(this::onReady);
      case PageOp.NewThread newThread -> Async.onEdt(() -> this.newThread(null, null,
        this.threadPath(newThread.projectPath()), newThread.agent()));
      case PageOp.AddProject addProject -> Async.onEdt(this::addProject);
      case PageOp.RemoveProject removeProject -> Async.onEdt(() -> this.removeProject(removeProject.path()));
      case PageOp.CollapseProject collapse -> this.collapseProject(collapse.path(), collapse.collapsed());
      case PageOp.Select select -> Async.onEdt(() -> this.select(select.thread()));
      case PageOp.Close close -> Async.onEdt(() -> this.close(close.thread()));
      case PageOp.Archive archive -> Async.onEdt(() -> this.setArchived(archive.thread(), archive.archived()));
      case PageOp.History history -> Async.inBackground(this::postHistory);
      case PageOp.Usage usage -> Async.inBackground(
        () -> this.post(UsageReport.build(this.projects(), this.store, EnvironmentUtil.getEnvironmentMap())));
      case PageOp.Resume resume -> Async.onEdt(() -> this.resume(resume.sessionId(), resume.title(),
        this.threadPath(resume.projectPath()), resume.agent()));
      case PageOp.SetAgent setAgent -> Async.onEdt(() -> this.setAgent(setAgent.thread(), setAgent.agent()));
      case PageOp.CreateBench createBench -> Async.onEdt(() -> {
        String projectPath = this.threadPath(createBench.projectPath());
        this.benches.create(createBench, projectPath, this.folderOf(projectPath));
      });
      case PageOp.CompareBench compareBench -> Async.inBackground(() -> this.benches.compare(compareBench.bench()));
      case PageOp.ShowRunDiff showRunDiff -> Async.inBackground(
        () -> this.benches.showDiff(showRunDiff.bench(), showRunDiff.runIndex()));
      case PageOp.PickWinner pickWinner -> Async.inBackground(
        () -> this.benches.pickWinner(pickWinner.bench(), pickWinner.runIndex()));
      case PageOp.DiscardBench discardBench -> Async.onEdt(() -> this.benches.discard(discardBench.bench()));
    }
  }

  /** Tells the page what the settings let it offer. */
  public void postSettings() {
    this.post(new PageMessage.Settings(ChatSettings.state().parallelRuns));
  }

  private void onReady() {
    this.post(new PageMessage.Project(this.project.getBasePath(), this.project.getName()));
    this.postSettings();
    this.postAgents();
    this.postProjects();
    this.benches.post();
    List<PageMessage.ThreadItem> items = new ArrayList<>();
    for (ThreadStore.Record record : this.store.all()) {
      items.add(record.toItem());
    }
    this.post(new PageMessage.Threads(items));

    ThreadStore.Record active = this.store.find(this.store.activeId());
    if (active == null || active.archived) {
      active = this.firstOpenThread(null);
    }
    if (active == null) {
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
      List<PageMessage.ProjectItem> items = new ArrayList<>();
      for (int i = 0; i < folders.size(); i++) {
        ThreadStore.ProjectFolder folder = folders.get(i);
        ProjectIcons.Icons icons = ProjectIcons.find(Path.of(folder.path));
        items.add(new PageMessage.ProjectItem(folder.path, folder.name, folder.collapsed, i == 0, icons.light(),
          icons.dark()));
      }
      this.post(new PageMessage.Projects(items));
    });
  }

  private void addProject() {
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

  private void removeProject(String path) {
    if (ThreadStore.samePath(path, this.project.getBasePath())) {
      return;
    }
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && ThreadStore.samePath(record.projectPath, path)) {
        this.post(new PageMessage.Error(TakoBundle.message("error.project.remove")));
        return;
      }
    }
    this.store.removeProject(path);
    this.postProjects();
  }

  private void collapseProject(String path, boolean collapsed) {
    ThreadStore.ProjectFolder folder = this.store.findProject(path);
    if (folder != null) {
      folder.collapsed = collapsed;
      this.store.save();
    }
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
    this.post(new PageMessage.ThreadAdded(record.toItem()));
    this.select(record.id);
  }

  void select(@Nullable String id) {
    ThreadStore.Record record = this.store.find(id);
    if (record == null) {
      return;
    }
    if (record.archived) {
      record.archived = false;
      this.postUpdate(record);
    }
    this.store.setActiveId(record.id);
    this.post(new PageMessage.Select(record.id));
    ChatController existing = this.controllers.get(record.id);
    if (existing != null) {
      Async.inBackground(existing::refreshGit);
      return;
    }
    this.newController(record).open();
  }

  /** Shows a new thread in the list and starts its agent without selecting it. */
  void openThread(ThreadStore.Record record) {
    this.post(new PageMessage.ThreadAdded(record.toItem()));
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
    this.post(new PageMessage.ThreadAdded(record.toItem()));
    this.newController(record).open(spec.prompt());
    return record;
  }

  /** The open thread's controller, null for a thread not open in Tako. */
  @Nullable
  ChatController controller(String id) {
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

  void close(String id) {
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
        this.post(new PageMessage.ThreadRemoved(id));
      }
    }
    if (activeId != null && ids.contains(activeId)) {
      this.selectAnotherIfActive(activeId);
    }
  }

  private void setArchived(String id, boolean archived) {
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

  private void resume(String sessionId, @Nullable String title, @Nullable String projectPath, @Nullable String agent) {
    ThreadStore.Record existing = this.store.findBySession(sessionId);
    if (existing != null) {
      this.select(existing.id);
    } else {
      this.newThread(sessionId, title, projectPath, agent);
    }
  }

  /** Runs another agent in a thread that has not started a session yet. Replaces a running one. */
  private void setAgent(String id, String agent) {
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

  /** Every agent's sessions in every project folder that no thread holds, newest first. Starts agents to ask. */
  private void postHistory() {
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    List<CompletableFuture<List<PageMessage.SessionItem>>> lookups = new ArrayList<>();
    for (AgentBackend backend : AgentRegistry.backends(environment)) {
      for (ThreadStore.ProjectFolder folder : this.projects()) {
        lookups.add(CompletableFuture.supplyAsync(() -> {
          List<PageMessage.SessionItem> items = new ArrayList<>();
          for (AgentSessionInfo session : backend.sessions(folder.path, SESSION_LIST_LIMIT)) {
            items.add(new PageMessage.SessionItem(session.id(), session.title(), session.lastModified(), folder.path,
              folder.name, backend.id(), backend.displayName()));
          }
          return items;
        }, AppExecutorUtil.getAppExecutorService()));
      }
    }
    List<PageMessage.SessionItem> found = new ArrayList<>();
    for (CompletableFuture<List<PageMessage.SessionItem>> lookup : lookups) {
      for (PageMessage.SessionItem item : lookup.join()) {
        if (this.store.findBySession(item.id()) == null) {
          found.add(item);
        }
      }
    }
    found.sort(Comparator.comparingLong(PageMessage.SessionItem::lastModified).reversed());
    this.post(new PageMessage.Sessions(found.subList(0, Math.min(SESSION_LIST_LIMIT, found.size()))));
  }

  private void postAgents() {
    List<PageMessage.AgentOption> agents = new ArrayList<>();
    for (AgentBackend backend : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
      agents.add(new PageMessage.AgentOption(backend.id(), backend.displayName()));
    }
    this.post(new PageMessage.Agents(agents));
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
    }
  }

  private void postUpdate(ThreadStore.Record record) {
    this.store.save();
    this.post(new PageMessage.ThreadUpdated(record.toItem()));
  }

  void post(PageMessage.WorkspaceMessage message) {
    ChatPanel chatPanel = this.panel;
    if (chatPanel != null) {
      chatPanel.post(message);
    }
  }

  private @Nullable ToolWindow toolWindow() {
    return ToolWindowManager.getInstance(this.project).getToolWindow(TOOL_WINDOW_ID);
  }

  private final class ThreadView implements ChatView {
    private final String threadId;

    private ThreadView(String threadId) {
      this.threadId = threadId;
    }

    @Override
    public void post(PageMessage.ThreadMessage message) {
      ChatPanel chatPanel = ChatService.this.panel;
      if (chatPanel != null) {
        chatPanel.post(message, this.threadId);
      }
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
      notification.addAction(NotificationAction.createSimpleExpiring(TakoBundle.message("notification.show"), () -> {
        ChatService.this.select(this.record.id);
        if (window != null) {
          window.activate(null);
        }
      }));
      notification.notify(ChatService.this.project);
    }
  }
}
