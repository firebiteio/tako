package io.firebite.tako.chat.thread;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentHistory;
import io.firebite.tako.agent.AgentLaunch;
import io.firebite.tako.agent.AgentListener;
import io.firebite.tako.agent.AgentSession;
import io.firebite.tako.agent.ContextUsage;
import io.firebite.tako.agent.EditPreview;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.PermissionAnswer;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.agent.SessionCost;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.Images;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.FileIndex;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ui.bridge.PageMessage;
import io.firebite.tako.ui.bridge.PageOp;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/** One chat thread. It owns its agent session and answers what the page asks for it. */
public final class ChatController implements Disposable {
  private static final Logger LOG = Logger.getInstance(ChatController.class);
  private static final int TITLE_LENGTH = 40;
  private static final long CONTEXT_TIMEOUT_SECONDS = 10;
  private static final int FILE_MATCHES = 30;
  private static final long PULL_REQUEST_INTERVAL_MILLIS = 120_000;
  private static final String PASTED_IMAGE_NAME = "pasted-image.png";
  private static final int SCRATCHPAD_FILES = 50;

  private final Project project;
  private final ChatView view;
  private final ChatHost host;
  private final ThreadStore.Record record;
  private final AgentBackend backend;
  private final AgentConnection connection = new AgentConnection();
  private final TurnChanges changes;
  /** Done once {@link #open} launched the agent, so a prompt sent meanwhile does not launch a second one. */
  private final CompletableFuture<Void> opened = new CompletableFuture<>();
  private final AtomicReference<String> firstPrompt = new AtomicReference<>();
  private final StringBuilder answer = new StringBuilder();

  private volatile State state = State.STARTING;
  private volatile @Nullable String error;
  private volatile @Nullable String workingDirectory;
  private volatile @Nullable String scratchpadPath;
  private volatile long lastPullRequestCheck;
  private volatile boolean isTitled;

  public ChatController(Project project, ChatView view, ChatHost host, ThreadStore.Record record,
    AgentBackend backend) {
    this.project = project;
    this.view = view;
    this.host = host;
    this.record = record;
    this.backend = backend;
    this.isTitled = record.sessionId != null || !ThreadStore.Record.DEFAULT_TITLE.equals(record.title);
    this.changes = new TurnChanges(project);
  }

  /** Replays the thread's past, if it has one, and starts its agent. */
  public void open() {
    this.open(null);
  }

  /** Like {@link #open()}, then sends {@code prompt} as if typed, once the agent is ready. Keeps the thread's title. */
  public void open(@Nullable String prompt) {
    this.firstPrompt.set(prompt);
    if (prompt != null) {
      this.isTitled = true;
    }
    Async.inBackground(() -> {
      try {
        String resume = this.record.sessionId;
        String projectPath = this.projectPath();
        if (resume != null && projectPath != null) {
          switch (this.backend.history(projectPath, resume)) {
            case AgentHistory.Loaded loaded -> this.view.post(new PageMessage.Replay(loaded.events()));
            case AgentHistory.Missing missing -> {
              resume = null;
              this.record.sessionId = null;
            }
            case AgentHistory.FromSession fromSession -> {
            }
          }
        }
        if (resume != null && projectPath != null && this.record.costUsd == 0) {
          SessionCost cost = this.backend.sessionCost(projectPath, resume);
          if (cost != null) {
            this.record.setUsage(cost);
            this.recordChanged();
          }
        }
        this.launch(resume);
      } finally {
        this.opened.complete(null);
      }
      this.refreshGit();
    });
  }

  public void handle(PageOp.ThreadOp op) {
    switch (op) {
      case PageOp.Send send -> this.onSend(send);
      case PageOp.Interrupt interrupt -> this.connection.ifLive(AgentSession::interrupt);
      case PageOp.Permission permission -> {
        this.state = State.WORKING;
        this.connection.ifLive(session -> session.answer(permission.requestId(),
          new PermissionAnswer(permission.allow(), permission.message(), permission.interrupt(),
            permission.answers() != null ? permission.answers() : Map.of(), permission.suggestion())));
      }
      case PageOp.Diff diff -> Async.inBackground(() -> this.showProposedEdit(diff.requestId()));
      case PageOp.TurnDiff turnDiff -> Async.inBackground(() -> this.showTurnDiff(turnDiff.turn(), turnDiff.path()));
      case PageOp.Open open -> Async.onEdt(() -> IdeFiles.open(this.project, open.path(), open.line()));
      case PageOp.Insert insert -> Async.onEdt(() -> IdeFiles.insertAtCaret(this.project, insert.text()));
      case PageOp.SetModel setModel -> {
        this.record.model = setModel.model();
        this.recordChanged();
        this.connection.ifLive(session -> session.setModel(setModel.model()));
      }
      case PageOp.SetMode setMode -> {
        this.record.mode = setMode.mode();
        this.recordChanged();
        this.connection.ifLive(session -> session.setMode(setMode.mode()));
      }
      case PageOp.SetEffort setEffort -> {
        this.record.effort = setEffort.effort();
        this.recordChanged();
        this.connection.ifLive(session -> session.setEffort(setEffort.effort()));
      }
      case PageOp.PasteImage pasteImage -> Async.onEdt(this::pasteImage);
      case PageOp.PickImage pickImage -> Async.onEdt(this::pickImages);
      case PageOp.ContextUsage contextUsage -> this.refreshContext();
      case PageOp.Files files -> Async.inBackground(() -> this.findFiles(files));
      case PageOp.Restart restart -> Async.inBackground(() -> this.launch(this.record.sessionId));
      case PageOp.Scratchpad scratchpad -> Async.inBackground(this::listScratchpad);
      case PageOp.RevealScratchpad revealScratchpad -> {
        String directory = this.scratchpadPath;
        if (directory != null) {
          RevealFileAction.openDirectory(Path.of(directory));
        }
      }
    }
  }

  /**
   * Re-reads branch and worktree, which is local and cheap. Asks for the pull request only when the branch changed or
   * the last lookup is older than {@link #PULL_REQUEST_INTERVAL_MILLIS}. Call off the EDT.
   */
  public void refreshGit() {
    String directory = this.workingDirectory != null ? this.workingDirectory : this.projectPath();
    if (directory == null) {
      return;
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    GitInfo info = GitInfo.local(Path.of(directory), environment);
    boolean branchChanged = !Objects.equals(info.branch(), this.record.branch);
    GitInfo.PullRequest pullRequest = this.record.pullRequest;
    long now = System.currentTimeMillis();
    if (info.branch() == null) {
      pullRequest = null;
    } else if (branchChanged || now - this.lastPullRequestCheck > PULL_REQUEST_INTERVAL_MILLIS) {
      this.lastPullRequestCheck = now;
      pullRequest = GitInfo.pullRequest(Path.of(directory), environment);
    }
    if (!branchChanged && Objects.equals(info.worktree(), this.record.worktree)
      && Objects.equals(pullRequest, this.record.pullRequest)) {
      return;
    }
    this.record.branch = info.branch();
    this.record.worktree = info.worktree();
    this.record.pullRequest = pullRequest;
    this.recordChanged();
  }

  /** The folder this thread works in: its worktree, its project folder or else the IDE project. */
  public @Nullable String projectPath() {
    return this.record.folder(this.project.getBasePath());
  }

  /** The project folder the thread belongs to, which its worktree is a copy of. */
  private @Nullable String projectFolder() {
    return this.record.projectPath != null ? this.record.projectPath : this.project.getBasePath();
  }

  public State state() {
    return this.state;
  }

  /** The text the agent wrote since the last prompt. */
  public String lastAnswer() {
    synchronized (this.answer) {
      return this.answer.toString();
    }
  }

  /** Why the agent did not start or the last turn failed, if it did. */
  public @Nullable String error() {
    return this.error;
  }

  public void addContext(ContextItem item, @Nullable String prefill) {
    this.view.post(new PageMessage.Context(item, prefill));
  }

  /** Reads image files and attaches them to the composer. Call off the EDT. */
  public void attachFiles(List<Path> files) {
    for (Path file : files) {
      try {
        ImageAttachment image = Images.fromFile(file);
        if (image == null) {
          this.postError(TakoBundle.message("error.image.unsupported", file.getFileName()));
        } else {
          this.view.post(new PageMessage.Image(image));
        }
      } catch (IOException e) {
        this.postError(TakoBundle.message("error.image.read", file.getFileName(), e.getMessage()));
      }
    }
  }

  @Override
  public void dispose() {
    this.connection.stop();
  }

  private void launch(@Nullable String resumeSessionId) {
    String basePath = this.projectPath();
    Path workDirectory = Path.of(basePath != null ? basePath : System.getProperty("user.home"));
    AgentLaunch launch = new AgentLaunch(workDirectory, resumeSessionId, this.record.model, this.record.mode,
      this.record.effort, this.host.additionalDirectories(), EnvironmentUtil.getEnvironmentMap(),
      this.host.toolsUrl());
    this.state = State.STARTING;
    AgentSession session;
    try {
      session = this.connection.replace(this.backend, launch, new Listener());
    } catch (AgentException e) {
      this.stopped(e.getMessage());
      this.postError(e.getMessage());
      return;
    }
    session.initialize().whenComplete((info, error) -> {
      if (error != null) {
        String message = TakoBundle.message("error.agent.not.started", this.backend.displayName(),
          AgentException.messageOf(error));
        this.stopped(message);
        this.postError(message);
        return;
      }
      this.state = State.IDLE;
      this.view.post(new PageMessage.Init(info, this.projectPath()));
      this.refreshContext();
      String prompt = this.firstPrompt.getAndSet(null);
      if (prompt != null) {
        this.view.post(new PageMessage.Submit(prompt));
      }
    });
  }

  private void onSend(PageOp.Send send) {
    this.changes.startTurn(send.turn());
    List<ImageAttachment> images = new ArrayList<>();
    for (ImageAttachment image : send.images()) {
      if (image.isValid()) {
        images.add(image);
      }
    }
    String text = send.text();
    Async.onEdt(() -> {
      String activeFile = send.includeActiveFile() ? IdeContext.activeFile(this.project) : null;
      String composed = text.isBlank() && !images.isEmpty()
        ? ""
        : PromptComposer.compose(text, send.context(), activeFile);
      String folder = this.projectFolder();
      String prompt = this.record.workPath != null && folder != null
        ? PromptComposer.rebase(composed, folder, this.record.workPath)
        : composed;
      Async.inBackground(() -> this.sendPrompt(text, prompt, images));
    });
  }

  private void sendPrompt(String typed, String prompt, List<ImageAttachment> images) {
    this.opened.join();
    AgentSession session = this.connection.live();
    if (session == null) {
      this.launch(this.record.sessionId);
      session = this.connection.live();
      if (session == null) {
        return;
      }
    }
    synchronized (this.answer) {
      this.answer.setLength(0);
    }
    this.state = State.WORKING;
    this.error = null;
    session.prompt(prompt, images);
    Async.onEdt(this.host::activity);
    if (!this.isTitled && !typed.startsWith("/")) {
      this.isTitled = true;
      String title = titleOf(typed);
      Async.onEdt(() -> this.host.setTitle(title));
    }
  }

  /** The prompt's first {@link #TITLE_LENGTH} characters on one line, as a thread's title. */
  public static String titleOf(String prompt) {
    String line = prompt.replace('\n', ' ').trim();
    return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH - 1) + "…";
  }

  /** Reads the file, so call off the EDT. */
  private void showProposedEdit(String requestId) {
    AgentSession session = this.connection.live();
    EditPreview preview = session == null ? null : session.proposedEdit(requestId);
    if (preview == null) {
      this.postError(TakoBundle.message("error.no.diff"));
      return;
    }
    String title = TakoBundle.message("diff.title", this.backend.displayName(),
      Path.of(preview.filePath()).getFileName());
    Async.onEdt(() -> IdeFiles.showDiff(this.project, preview, title));
  }

  /** Reads the files, so call off the EDT. */
  private void showTurnDiff(int turn, @Nullable String path) {
    List<EditPreview> previews = this.changes.previews(turn, path);
    if (previews.isEmpty()) {
      this.postError(TakoBundle.message("error.no.turn.changes"));
      return;
    }
    Async.onEdt(() -> IdeFiles.showDiffs(this.project, previews));
  }

  private void pasteImage() {
    Image image;
    try {
      image = CopyPasteManager.getInstance().getContents(DataFlavor.imageFlavor);
    } catch (RuntimeException e) {
      image = null;
    }
    if (image == null) {
      return;
    }
    Image clipboardImage = image;
    Async.inBackground(() -> {
      try {
        this.view.post(new PageMessage.Image(Images.fromImage(clipboardImage, PASTED_IMAGE_NAME)));
      } catch (IOException e) {
        this.postError(TakoBundle.message("error.clipboard.image", e.getMessage()));
      }
    });
  }

  private void pickImages() {
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.multiFiles()
      .withTitle(TakoBundle.message("dialog.attach.images.title"))
      .withFileFilter(file -> Images.isImageFile(file.getName()));
    List<Path> paths = new ArrayList<>();
    for (VirtualFile file : FileChooser.chooseFiles(descriptor, this.project, null)) {
      paths.add(file.toNioPath());
    }
    Async.inBackground(() -> this.attachFiles(paths));
  }

  private void findFiles(PageOp.Files request) {
    String directory = this.projectPath();
    if (directory == null) {
      return;
    }
    List<String> entries = FileIndex.entries(Path.of(directory), EnvironmentUtil.getEnvironmentMap());
    this.view.post(new PageMessage.Files(request.requestId(),
      FileIndex.search(entries, request.query(), FILE_MATCHES)));
  }

  private void listScratchpad() {
    String directory = this.scratchpadPath;
    if (directory != null) {
      this.view.post(new PageMessage.Scratchpad(directory, IdeFiles.newestFiles(Path.of(directory), SCRATCHPAD_FILES)));
    }
  }

  private void refreshContext() {
    this.connection.ifLive(session -> {
      CompletableFuture<ContextUsage> usage = session.contextUsage();
      if (usage == null) {
        return;
      }
      usage.orTimeout(CONTEXT_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((breakdown, error) -> {
        if (error != null) {
          LOG.debug("No context usage", error);
          return;
        }
        this.view.post(new PageMessage.ContextUsage(breakdown));
      });
    });
  }

  private void onEvent(AgentEvent event) {
    switch (event) {
      case AgentEvent.SessionStarted started -> {
        if (started.sessionId() != null && !started.sessionId().equals(this.record.sessionId)) {
          this.record.sessionId = started.sessionId();
          this.recordChanged();
        }
        if (started.workingDirectory() != null) {
          this.workingDirectory = started.workingDirectory();
        }
        this.scratchpadPath = started.scratchpadPath();
      }
      case AgentEvent.Busy busy -> this.state = State.WORKING;
      case AgentEvent.PermissionCancelled cancelled -> this.state = State.WORKING;
      case AgentEvent.Text text -> {
        if (text.parentId() == null && !text.text().isBlank()) {
          synchronized (this.answer) {
            this.answer.append(this.answer.isEmpty() ? "" : "\n\n").append(text.text());
          }
        }
      }
      case AgentEvent.ToolStarted tool -> this.changes.onToolStarted(tool);
      case AgentEvent.ToolFinished result -> this.changes.onToolFinished(result);
      case AgentEvent.TurnEnded ended -> this.onTurnEnded(ended);
      default -> {
      }
    }
    this.view.post(new PageMessage.Event(event));
  }

  private void onTurnEnded(AgentEvent.TurnEnded ended) {
    this.state = State.IDLE;
    this.error = ended.error();
    this.refreshContext();
    if (ended.cost() != null) {
      this.record.setUsage(ended.cost());
      this.recordChanged();
    }
    if (this.record.firstTurnMillis == 0 && ended.durationMs() != null) {
      this.record.firstTurnMillis = ended.durationMs();
      this.recordChanged();
    }
    this.lastPullRequestCheck = 0;
    Async.inBackground(this::refreshGit);
  }

  private void onPermission(String requestId, PermissionPrompt prompt) {
    this.state = State.WAITING;
    this.view.post(new PageMessage.Permission(requestId, prompt));
    String agent = this.backend.displayName();
    String reason = prompt.kind() == PermissionPrompt.Kind.QUESTION
      ? TakoBundle.message("notification.question", agent)
      : TakoBundle.message("notification.tool", agent, prompt.toolName());
    Async.onEdt(() -> this.host.requestAttention(reason));
  }

  private void stopped(@Nullable String error) {
    this.state = State.STOPPED;
    this.error = error;
  }

  private void recordChanged() {
    Async.onEdt(this.host::recordChanged);
  }

  private void postError(String text) {
    this.view.post(new PageMessage.Error(text));
  }

  /** What the thread's agent is doing. */
  public enum State {
    STARTING,
    WORKING,
    /** Waits for the person to answer a permission prompt or question. */
    WAITING,
    IDLE,
    /** The agent did not start or its process ended. The next prompt starts it again. */
    STOPPED
  }

  private final class Listener implements AgentListener {
    @Override
    public void onEvent(AgentEvent event) {
      ChatController.this.onEvent(event);
    }

    @Override
    public void onHistory(List<AgentEvent> events) {
      ChatController.this.view.post(new PageMessage.Replay(events));
    }

    @Override
    public void onPermission(String requestId, PermissionPrompt prompt) {
      ChatController.this.onPermission(requestId, prompt);
    }

    @Override
    public void onFileEdited(String path, String originalContent) {
      ChatController.this.changes.onFileEdited(path, originalContent);
    }

    @Override
    public void onExit(int exitCode, String stderrTail) {
      ChatController.this.stopped(exitCode == 0 ? null : stderrTail);
      ChatController.this.view.post(new PageMessage.Exit(exitCode, stderrTail));
    }
  }
}
