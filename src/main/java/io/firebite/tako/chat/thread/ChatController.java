package io.firebite.tako.chat.thread;

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
import io.firebite.tako.chat.git.Blocker;
import io.firebite.tako.chat.git.GitActionFlow;
import io.firebite.tako.chat.git.GitDrafts;
import io.firebite.tako.chat.git.ThreadGit;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.Images;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.FileIndex;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitHubCli;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/** One chat thread. It owns its agent session and does what the chat asks of it. */
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
  private final Conversation conversation = new Conversation();
  private final ThreadGit git;
  /** The chat's turn numbers of the prompts sent that the agent has not taken yet. */
  private final Set<Integer> untakenTurns = ConcurrentHashMap.newKeySet();

  /** Guards waking {@link #awaitSettled} waiters on each change of {@link #state}. */
  private final Object stateLock = new Object();

  private volatile State state = State.STARTING;
  private volatile boolean isDisposed;
  private volatile boolean hasTools;
  private volatile @Nullable String error;
  private volatile @Nullable String workingDirectory;
  private volatile @Nullable String scratchpadPath;
  private volatile long lastPullRequestCheck;
  private volatile boolean isTitled;
  /** Set by {@link #restartSession} until the next launch, whose session's past the chat already shows. */
  private volatile boolean keepsTimeline;

  public ChatController(Project project, ChatView view, ChatHost host, ThreadStore.Record record,
    AgentBackend backend) {
    this.project = project;
    this.view = view;
    this.host = host;
    this.record = record;
    this.backend = backend;
    this.isTitled = record.sessionId != null || !ThreadStore.Record.DEFAULT_TITLE.equals(record.title);
    this.changes = new TurnChanges(project);
    this.git = new ThreadGit(() -> {
      String folder = this.gitFolder();
      return folder == null ? null : Path.of(folder);
    }, EnvironmentUtil.getEnvironmentMap(), this::busyIn, GitHubCli.GH);
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
            case AgentHistory.Loaded loaded -> this.replay(loaded.events());
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

  /** Sends {@code prompt} as if typed. The thread counts as working from now on, so a waiter never sees it idle first. */
  public void submit(String prompt) {
    this.setState(State.WORKING);
    this.error = null;
    this.view.post(new ChatMessage.Submit(prompt, List.of(), List.of()));
  }

  public void interrupt() {
    this.connection.ifLive(AgentSession::interrupt);
  }

  /**
   * Answers a {@link ChatMessage.Permission}.
   *
   * @param answers    question → answer
   * @param suggestion the index of the suggestion approved with
   */
  public void answer(String requestId, boolean allow, @Nullable String message, boolean interrupt,
    @Nullable Map<String, String> answers, @Nullable Integer suggestion) {
    this.setState(State.WORKING);
    this.connection.ifLive(session -> session.answer(requestId,
      new PermissionAnswer(allow, message, interrupt, answers != null ? answers : Map.of(), suggestion)));
  }

  public void setModel(String model) {
    this.record.model = model;
    this.recordChanged();
    this.connection.ifLive(session -> session.setModel(model));
  }

  public void setMode(String mode) {
    this.record.mode = mode;
    this.recordChanged();
    this.connection.ifLive(session -> session.setMode(mode));
  }

  public void setEffort(String effort) {
    this.record.effort = effort;
    this.recordChanged();
    this.connection.ifLive(session -> session.setEffort(effort));
  }

  /** Starts the agent again, e.g. after its process ended. */
  public void restart() {
    Async.inBackground(() -> this.launch(this.record.sessionId));
  }

  /**
   * Stops the agent so the next prompt resumes its session in a new process, which loads skills, plugins, MCP servers
   * and instructions again. Only while the agent is idle. The chat keeps its timeline and the last turn's error.
   */
  public void restartSession() {
    if (this.state != State.IDLE) {
      return;
    }
    Async.inBackground(() -> {
      if (this.state != State.IDLE) {
        return;
      }
      this.keepsTimeline = true;
      this.connection.stop();
      this.stopped(this.error);
      this.view.post(new ChatMessage.Exit(0, ""));
    });
  }

  /**
   * Re-reads branch and worktree, which is local and cheap. Asks for the pull request only when the branch changed or
   * the last lookup is older than {@link #PULL_REQUEST_INTERVAL_MILLIS}. Call off the EDT.
   */
  public void refreshGit() {
    String directory = this.gitFolder();
    if (directory == null || this.isDisposed) {
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

  /** Like {@link #refreshGit}, and asks for the pull request whenever it last did. Call off the EDT. */
  public void refreshGitNow() {
    this.lastPullRequestCheck = 0;
    this.refreshGit();
  }

  /** The folder git actions work on: where the agent says it works, else the thread's folder. */
  public @Nullable String gitFolder() {
    return this.workingDirectory != null ? this.workingDirectory : this.projectPath();
  }

  /**
   * The thread's git actions and the state they act on. Reads the branch's pull request again first when the branch
   * changed, so the menu does not judge by another branch's. Runs git and may ask GitHub, so call off the EDT.
   */
  public ThreadGit.Menu gitMenu() {
    this.refreshGit();
    return this.git.menu(this.record.pullRequest);
  }

  /**
   * Asks for the action's texts while the thread's agent writes them, then runs it. Must run on the EDT.
   *
   * @param status the state the menu showed
   * @return completes on the EDT once it ran or the person cancelled it
   */
  public CompletableFuture<Void> runGitAction(ThreadGit.Option option, GitCommands.Status status) {
    return GitActionFlow.start(this.project, this.git, option, status,
      this.conversation(GitDrafts.MAX_CONVERSATION), this.backend, this::refreshGitNow);
  }

  /** This thread's agent, or another thread's, working in the repository. */
  private @Nullable ThreadGit.Busy busyIn(String topLevel) {
    if (this.state.isInTurn()) {
      return new ThreadGit.Busy(Blocker.AGENT_BUSY, this.backend.displayName());
    }
    String other = this.host.busyThreadIn(topLevel);
    return other == null ? null : new ThreadGit.Busy(Blocker.OTHER_THREAD_BUSY, other);
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

  /** Whether the running agent has Tako's MCP tools, such as {@code get_thread}. False until it started. */
  public boolean hasTools() {
    return this.hasTools;
  }

  /** The prompts and answers of the whole thread, see {@link Conversation#text}. */
  public String conversation(int maxChars) {
    return this.conversation.text(maxChars);
  }

  /** Why the agent did not start or the last turn failed, if it did. */
  public @Nullable String error() {
    return this.error;
  }

  public void addContext(ContextItem item, @Nullable String prefill) {
    this.view.post(new ChatMessage.Context(item, prefill));
  }

  /** Reads image files in the background and attaches them to the composer. */
  public void attachFiles(List<Path> files) {
    Async.inBackground(() -> this.readImages(files));
  }

  private void readImages(List<Path> files) {
    for (Path file : files) {
      try {
        ImageAttachment image = Images.fromFile(file);
        if (image == null) {
          this.postError(TakoBundle.message("error.image.unsupported", file.getFileName()));
        } else {
          this.view.post(new ChatMessage.Image(image));
        }
      } catch (IOException e) {
        this.postError(TakoBundle.message("error.image.read", file.getFileName(), e.getMessage()));
      }
    }
  }

  /**
   * Waits until the agent is no longer {@link State#isBusy busy}, the timeout passed or the thread closed.
   *
   * @return the state then
   */
  public State awaitSettled(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    synchronized (this.stateLock) {
      long left = deadline - System.nanoTime();
      while (this.state.isBusy() && !this.isDisposed && left > 0) {
        TimeUnit.NANOSECONDS.timedWait(this.stateLock, left);
        left = deadline - System.nanoTime();
      }
      return this.state;
    }
  }

  @Override
  public void dispose() {
    synchronized (this.stateLock) {
      this.isDisposed = true;
      this.stateLock.notifyAll();
    }
    this.connection.stop();
  }

  private void setState(State state) {
    synchronized (this.stateLock) {
      this.state = state;
      this.stateLock.notifyAll();
    }
    Async.onEdt(this.host::stateChanged);
  }

  private void launch(@Nullable String resumeSessionId) {
    this.launch(resumeSessionId, false);
  }

  /**
   * Starts the agent.
   *
   * @param forPrompt a prompt follows once the agent is ready, so the thread stays busy
   * @return completes once the agent is ready or failed to start
   */
  private CompletableFuture<?> launch(@Nullable String resumeSessionId, boolean forPrompt) {
    String basePath = this.projectPath();
    Path workDirectory = Path.of(basePath != null ? basePath : System.getProperty("user.home"));
    AgentLaunch launch = new AgentLaunch(workDirectory, resumeSessionId, this.record.model, this.record.mode,
      this.record.effort, this.host.additionalDirectories(), EnvironmentUtil.getEnvironmentMap(),
      this.host.toolsUrl());
    this.setState(State.STARTING);
    AgentSession session;
    try {
      session = this.connection.replace(this.backend, launch,
        this.keepsTimeline ? new Listener(false, resumeSessionId) : new Listener(true, null));
    } catch (AgentException e) {
      this.stopped(e.getMessage());
      this.postError(e.getMessage());
      return CompletableFuture.completedFuture(null);
    }
    this.keepsTimeline = false;
    return session.initialize().whenComplete((info, error) -> {
      if (error != null) {
        String message = TakoBundle.message("error.agent.not.started", this.backend.displayName(),
          AgentException.messageOf(error));
        this.stopped(message);
        this.postError(message);
        return;
      }
      this.hasTools = launch.toolsUrl() != null && session.takesTools();
      String prompt = this.firstPrompt.getAndSet(null);
      this.view.post(new ChatMessage.Init(info, this.projectPath()));
      this.refreshContext();
      if (prompt != null) {
        this.submit(prompt);
      } else if (!forPrompt) {
        this.setState(State.IDLE);
      }
    });
  }

  /**
   * Sends a prompt, starting the agent if it is not running.
   *
   * @param turn the chat's number for this prompt's turn, which {@link #showTurnDiff} names later
   */
  public void send(String text, List<ContextItem> context, List<ImageAttachment> attached, int turn) {
    List<ImageAttachment> images = new ArrayList<>();
    for (ImageAttachment image : attached) {
      if (image.isValid()) {
        images.add(image);
      }
    }
    Async.onEdt(() -> {
      String composed = text.isBlank() && !images.isEmpty()
        ? ""
        : PromptComposer.compose(text, context);
      String folder = this.projectFolder();
      String prompt = this.record.workPath != null && folder != null
        ? PromptComposer.rebase(composed, folder, this.record.workPath)
        : composed;
      Async.inBackground(() -> this.sendPrompt(text, prompt, images, turn));
    });
  }

  private void sendPrompt(String typed, String prompt, List<ImageAttachment> images, int turn) {
    this.opened.join();
    AgentSession session = this.connection.live();
    if (session == null) {
      this.launch(this.record.sessionId, true).whenComplete((ready, error) -> this.promptStarted(typed, prompt, images,
        turn));
      return;
    }
    this.prompt(session, typed, prompt, images, turn);
  }

  /** Sends the prompt to the agent it launched, once that is ready, unless it did not start. */
  private void promptStarted(String typed, String prompt, List<ImageAttachment> images, int turn) {
    AgentSession session = this.connection.live();
    if (session != null && this.state != State.STOPPED) {
      this.prompt(session, typed, prompt, images, turn);
    }
  }

  private void prompt(AgentSession session, String typed, String prompt, List<ImageAttachment> images, int turn) {
    this.setState(State.WORKING);
    this.error = null;
    this.untakenTurns.add(turn);
    this.conversation.send(turn, typed);
    session.prompt(prompt, images, turn);
    Async.onEdt(this.host::activity);
    if (!this.isTitled && !typed.startsWith("/")) {
      this.isTitled = true;
      String title = titleOf(typed);
      Async.onEdt(() -> this.host.setTitle(title));
    }
  }

  /** The prompt's first {@link #TITLE_LENGTH} characters on one line, as a thread's title. */
  public static String titleOf(String prompt) {
    return cut(prompt.replace('\n', ' ').trim(), TITLE_LENGTH);
  }

  /** The text, or its start and an ellipsis in {@code length} characters. Does not split a surrogate pair. */
  public static String cut(String text, int length) {
    if (text.length() <= length) {
      return text;
    }
    int end = length - 1;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end) + "…";
  }

  /** Shows what the tool call a permission prompt is about would change. */
  public void showProposedEdit(String requestId) {
    Async.inBackground(() -> {
      AgentSession session = this.connection.live();
      EditPreview preview = session == null ? null : session.proposedEdit(requestId);
      if (preview == null) {
        this.postError(TakoBundle.message("error.no.diff"));
        return;
      }
      String title = TakoBundle.message("diff.title", this.backend.displayName(),
        Path.of(preview.filePath()).getFileName());
      Async.onEdt(() -> IdeFiles.showDiff(this.project, preview, title));
    });
  }

  /**
   * Shows what the turn changed.
   *
   * @param turn the chat's number for the turn's prompt
   * @param path one file of the turn, or all of them when null
   */
  public void showTurnDiff(int turn, @Nullable String path) {
    Async.inBackground(() -> {
      List<EditPreview> previews = this.changes.previews(turn, path);
      if (previews.isEmpty()) {
        this.postError(TakoBundle.message("error.no.turn.changes"));
        return;
      }
      Async.onEdt(() -> IdeFiles.showDiffs(this.project, previews));
    });
  }

  /** Attaches the clipboard's image, if it holds one. Must run on the EDT. */
  public void pasteImage() {
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
        this.view.post(new ChatMessage.Image(Images.fromImage(clipboardImage, PASTED_IMAGE_NAME)));
      } catch (IOException e) {
        this.postError(TakoBundle.message("error.clipboard.image", e.getMessage()));
      }
    });
  }

  /**
   * Writes a large paste to a file in {@link PastedText#folder()}, which every launch passes as an additional
   * directory, so the agent reads it instead of the prompt carrying it. Writes right away, so the file is attached
   * before the next key is handled.
   *
   * @return the file as context, or null after showing why it could not be written
   */
  public @Nullable ContextItem pasteText(String text) {
    try {
      return IdeContext.file(PastedText.write(PastedText.folder(), text));
    } catch (IOException e) {
      this.postError(TakoBundle.message("error.paste.text", e.getMessage()));
      return null;
    }
  }

  /** Deletes the file of a pasted text chip removed before sending. Other chips are left alone. */
  public void discardPaste(ContextItem item) {
    Path file = PastedText.fileOf(item, PastedText.folder());
    if (file == null) {
      return;
    }
    Async.inBackground(() -> {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        LOG.debug("Could not delete " + file, e);
      }
    });
  }

  /** Asks for image files to attach. The chooser opens once the current event is handled. */
  public void pickImages() {
    Async.onEdt(this::chooseImages);
  }

  private void chooseImages() {
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.multiFiles()
      .withTitle(TakoBundle.message("dialog.attach.images.title"))
      .withFileFilter(file -> Images.isImageFile(file.getName()));
    List<Path> paths = new ArrayList<>();
    for (VirtualFile file : FileChooser.chooseFiles(descriptor, this.project, null)) {
      paths.add(file.toNioPath());
    }
    this.attachFiles(paths);
  }

  /** Project files matching an {@code @} mention. Lists the folder, so call off the EDT. */
  public List<FileIndex.Match> findFiles(String query) {
    String directory = this.projectPath();
    if (directory == null) {
      return List.of();
    }
    List<String> entries = FileIndex.entries(Path.of(directory), EnvironmentUtil.getEnvironmentMap());
    return FileIndex.search(entries, query, FILE_MATCHES);
  }

  /**
   * The files in the agent's scratchpad, relative to it with {@code /} separators, newest first. Null without a
   * scratchpad. Lists the folder, so call off the EDT.
   */
  public @Nullable List<String> scratchpadFiles() {
    String directory = this.scratchpadPath;
    return directory == null ? null : IdeFiles.newestFiles(Path.of(directory), SCRATCHPAD_FILES);
  }

  /** Asks the agent how full its context is, which arrives as {@link ChatMessage.ContextUsage}. */
  public void refreshContext() {
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
        this.view.post(new ChatMessage.ContextUsage(breakdown));
      });
    });
  }

  private void replay(List<AgentEvent> events) {
    this.conversation.reset(events);
    this.view.post(new ChatMessage.Replay(events));
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
      case AgentEvent.Busy busy -> this.setState(State.WORKING);
      case AgentEvent.PermissionCancelled cancelled -> this.setState(State.WORKING);
      case AgentEvent.Text text -> {
        if (text.parentId() == null && !text.text().isBlank()) {
          synchronized (this.answer) {
            this.answer.append(this.answer.isEmpty() ? "" : "\n\n").append(text.text());
          }
        }
        this.conversation.add(text);
      }
      case AgentEvent.ToolStarted tool -> this.changes.onToolStarted(tool);
      case AgentEvent.ToolFinished result -> this.changes.onToolFinished(result);
      case AgentEvent.TurnEnded ended -> this.onTurnEnded(ended);
      case AgentEvent.PromptTaken taken -> this.onPromptTaken(taken);
      default -> {
      }
    }
    this.view.post(new ChatMessage.Event(event));
  }

  /** A prompt that joins the running turn keeps its changes and answer under that turn. */
  private void onPromptTaken(AgentEvent.PromptTaken taken) {
    this.untakenTurns.remove(taken.key());
    this.conversation.take(taken.key());
    if (taken.startsTurn()) {
      this.changes.startTurn(taken.key());
      synchronized (this.answer) {
        this.answer.setLength(0);
      }
    }
  }

  /** The thread stays working while prompts wait. */
  private void onTurnEnded(AgentEvent.TurnEnded ended) {
    if (this.untakenTurns.isEmpty()) {
      this.setState(State.IDLE);
    }
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
    this.setState(State.WAITING);
    this.view.post(new ChatMessage.Permission(requestId, prompt));
    String agent = this.backend.displayName();
    String reason = prompt.kind() == PermissionPrompt.Kind.QUESTION
      ? TakoBundle.message("notification.question", agent)
      : TakoBundle.message("notification.tool", agent, prompt.toolName());
    Async.onEdt(() -> this.host.requestAttention(reason));
  }

  private void stopped(@Nullable String error) {
    this.untakenTurns.clear();
    this.setState(State.STOPPED);
    this.error = error;
  }

  private void recordChanged() {
    Async.onEdt(this.host::recordChanged);
  }

  private void postError(String text) {
    this.view.post(new ChatMessage.Error(text));
  }

  /** What the thread's agent is doing. */
  public enum State {
    STARTING,
    WORKING,
    /** Waits for the person to answer a permission prompt or question. */
    WAITING,
    IDLE,
    /** The agent did not start or its process ended. The next prompt starts it again. */
    STOPPED;

    /** Starting or working, so a prompt or a wait for its answer is premature. */
    public boolean isBusy() {
      return this == STARTING || this == WORKING;
    }

    /** Works on a turn or waits for an answer in one, so the files may change any moment. */
    public boolean isInTurn() {
      return this == WORKING || this == WAITING;
    }
  }

  private final class Listener implements AgentListener {
    /** Whether the session's past replaces the chat's timeline. */
    private final boolean replays;
    /** The session the chat's timeline continues, until the agent names the one it started. */
    private volatile @Nullable String resumes;

    private Listener(boolean replays, @Nullable String resumes) {
      this.replays = replays;
      this.resumes = resumes;
    }

    @Override
    public void onEvent(AgentEvent event) {
      String resumed = this.resumes;
      if (resumed != null && event instanceof AgentEvent.SessionStarted started && started.sessionId() != null) {
        this.resumes = null;
        if (!resumed.equals(started.sessionId())) {
          ChatController.this.view.post(new ChatMessage.Notice(
            TakoBundle.message("error.session.not.resumed", ChatController.this.backend.displayName())));
        }
      }
      ChatController.this.onEvent(event);
    }

    @Override
    public void onHistory(List<AgentEvent> events) {
      if (this.replays) {
        ChatController.this.replay(events);
      }
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
      ChatController.this.view.post(new ChatMessage.Exit(exitCode, stderrTail));
    }
  }
}
