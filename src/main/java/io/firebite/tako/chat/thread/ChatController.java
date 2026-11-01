package io.firebite.tako.chat.thread;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
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
import io.firebite.tako.chat.store.PromptStash;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.Images;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.ide.context.ReviewTarget;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.Checkpoints;
import io.firebite.tako.ide.project.FileIndex;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitHubCli;
import io.firebite.tako.ide.project.GitInfo;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
  /** How long a snapshot or restore waits for the editors to be saved, which a modal dialog holds up. */
  private static final long SAVE_TIMEOUT_SECONDS = 2;

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
  private final Object heldLock = new Object();
  /** Done once the prompts held for a git action went out. Guarded by {@link #heldLock}. */
  private CompletableFuture<Void> held = CompletableFuture.completedFuture(null);
  /** The prompts the agent has not taken yet, which the state follows. */
  private final QueuedPrompts queue = new QueuedPrompts(this::queueChanged);
  /** Sends prompts one at a time, in the order the chat and the queue decided. */
  private final ExecutorService sender = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tako Send", 1);
  /** How many prompts the session got before the next queued one, which names its file checkpoint. */
  private volatile int promptsSent;
  /** Guards starting a rewind. */
  private final Object rewindLock = new Object();
  /** Prompts {@link #submit} holds back until the running rewind is done. */
  private final AtomicInteger deferredSubmits = new AtomicInteger();

  /** Guards waking {@link #awaitSettled} waiters on each change of {@link #state}. */
  private final Object stateLock = new Object();

  private volatile State state = State.STARTING;
  private volatile boolean isDisposed;
  private volatile boolean hasTools;
  /** Whether the running agent takes prompts into its running turn. */
  private volatile boolean canSteer;
  private volatile @Nullable String error;
  private volatile @Nullable String workingDirectory;
  private volatile @Nullable String scratchpadPath;
  private volatile long lastPullRequestCheck;
  private volatile boolean isTitled;
  /** Set by {@link #restartSession} until the next launch, whose session's past the chat already shows. */
  private volatile boolean keepsTimeline;
  /** Done unless a {@link #rewind} runs. */
  private volatile CompletableFuture<Void> rewinding = CompletableFuture.completedFuture(null);
  /** Whether the running agent's sessions can be rewound, so prompts get file checkpoints. */
  private volatile boolean canRewind;
  /** The agent works on a turn, so the files may be half changed. */
  private volatile boolean turnRunning;

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

  /**
   * Sends {@code prompt} as if typed. The thread counts as working from now on, so a waiter never sees it idle first.
   * During a rewind it is sent once the rewind is done.
   */
  public void submit(String prompt) {
    if (this.state != State.WAITING) {
      this.setState(State.WORKING);
    }
    this.error = null;
    CompletableFuture<Void> rewind;
    boolean isDeferred;
    synchronized (this.rewindLock) {
      rewind = this.rewinding;
      isDeferred = !rewind.isDone();
      if (isDeferred) {
        this.deferredSubmits.incrementAndGet();
      }
    }
    if (isDeferred) {
      rewind.whenComplete((done, failure) -> {
        this.deferredSubmits.decrementAndGet();
        this.submit(prompt);
      });
      return;
    }
    this.view.post(new ChatMessage.Submit(prompt, List.of(), List.of()));
  }

  /**
   * Stops the running turn. The queued prompts wait until a prompt is sent. When nothing is left running at once, e.g.
   * a prompt that had not reached the agent, the chat ends its turn as stopped, since no turn's end will.
   */
  public void interrupt() {
    if (this.queue.stop()) {
      this.view.post(new ChatMessage.Event(new AgentEvent.Interrupted()));
      this.view.post(new ChatMessage.Event(new AgentEvent.TurnEnded(null, null, null)));
    }
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
    Async.inBackground(() -> {
      this.rewinding.join();
      this.launch(this.record.sessionId);
    });
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
      this.rewinding.join();
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
   * The thread's git actions and the state they act on. Asks GitHub nothing, except for the pull request of a branch
   * checked out since the last {@link #refreshGit}. Runs git, so call off the EDT.
   *
   * @see #checkGitHub
   */
  public ThreadGit.Menu gitMenu() {
    ThreadGit.Menu menu = this.git.menu(this.record.pullRequest, this.record.branch);
    String branch = menu.status().branch();
    if (branch == null || branch.equals(this.record.branch)) {
      return menu;
    }
    this.refreshGit();
    return this.git.menu(this.record.pullRequest, this.record.branch);
  }

  /** Learns whether the GitHub CLI can create the menu's pull request. Asks GitHub, so call off the EDT. */
  public void checkGitHub(ThreadGit.Menu menu) {
    this.git.checkGitHub(menu);
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
    this.stash(false);
    this.connection.stop();
  }

  /** Puts the queued prompts in the project's prompt stash now and closes the queue, e.g. as the project closes. */
  public void stashQueued() {
    this.stash(true);
  }

  /** @param now write on this thread, else on a pooled one */
  private void stash(boolean now) {
    List<ChatMessage.Submit> prompts = this.queue.drain();
    if (prompts.isEmpty() || this.project.isDisposed()) {
      return;
    }
    Runnable write = () -> {
      try {
        int kept = PromptStash.getInstance(this.project).pushAll(prompts);
        this.view.post(new ChatMessage.QueueStashed(kept));
      } catch (IOException e) {
        LOG.warn("Could not stash the queued prompts of thread " + this.record.id, e);
      }
    };
    if (now) {
      write.run();
    } else {
      Async.inBackground(write);
    }
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
      this.canRewind = info.capabilities().rewind();
      this.canSteer = info.capabilities().steer();
      String prompt = this.firstPrompt.getAndSet(null);
      this.view.post(new ChatMessage.Init(info, this.projectPath()));
      this.refreshContext();
      if (prompt != null) {
        this.submit(prompt);
      } else if (!forPrompt && this.deferredSubmits.get() == 0 && !this.queue.isRunning()) {
        this.setState(State.IDLE);
      }
    });
  }

  /**
   * Sends a prompt, starting the agent if it is not running. While a turn runs, the prompt waits in the queue or, if
   * asked and the agent can, steers that turn. Call on the EDT.
   *
   * @param turn    the chat's number for this prompt's turn, which {@link #showTurnDiff} names later
   * @param prompts how many prompts the session got before this one, which names its file checkpoint
   * @return what became of it: sent, queued or steering
   */
  public QueuedPrompts.Action send(String text, List<ContextItem> context, List<ImageAttachment> images, int turn,
    int prompts, QueuedPrompts.Delivery delivery) {
    ChatMessage.Submit prompt = new ChatMessage.Submit(text, context, images);
    QueuedPrompts.Action action = this.queue.offer(turn, prompt, delivery, this.canSteer);
    if (action != QueuedPrompts.Action.HOLD && !this.queue.isClosed()) {
      this.deliver(turn, prompt, action == QueuedPrompts.Action.STEER,
        action == QueuedPrompts.Action.SEND ? prompts : null);
    }
    return action;
  }

  /** Takes a queued prompt out of the queue, e.g. to edit it. Null once it is on its way. */
  public ChatMessage.@Nullable Submit unqueue(int turn) {
    return this.queue.take(turn);
  }

  /** Swaps a queued prompt with the one queued before it. */
  public void moveQueuedUp(int turn) {
    this.queue.moveUp(turn);
  }

  /**
   * Sends a queued prompt now: next, after stopping the running turn, or at once when none runs.
   *
   * @return what became of it
   */
  public QueuedPrompts.SendNow sendQueuedNow(int turn) {
    ChatMessage.Submit prompt = this.queue.promptOf(turn);
    QueuedPrompts.SendNow result = this.queue.sendNow(turn);
    switch (result) {
      case INTERRUPT -> this.connection.ifLive(AgentSession::interrupt);
      case SEND -> this.deliver(turn, Objects.requireNonNull(prompt), false, null);
      case QUEUED, NONE -> {
      }
    }
    return result;
  }

  /** Hands a queued prompt to the running turn. */
  public void steerQueued(int turn) {
    ChatMessage.Submit prompt = this.queue.promptOf(turn);
    if (prompt != null && this.queue.steer(turn, this.canSteer)) {
      this.deliver(turn, prompt, true, null);
    }
  }

  /**
   * Composes the prompt and hands it to the agent after the prompts handed before it.
   *
   * @param prompts how many prompts the session got before this one, or null to count on from the last one sent
   */
  private void deliver(int turn, ChatMessage.Submit prompt, boolean steer, @Nullable Integer prompts) {
    List<ImageAttachment> images = new ArrayList<>();
    for (ImageAttachment image : prompt.images()) {
      if (image.isValid()) {
        images.add(image);
      }
    }
    String text = prompt.text();
    String composed = text.isBlank() && !images.isEmpty()
      ? ""
      : PromptComposer.compose(text, prompt.context());
    String folder = this.projectFolder();
    String rebased = this.record.workPath != null && folder != null
      ? PromptComposer.rebase(composed, folder, this.record.workPath)
      : composed;
    this.sender.execute(() -> this.sendPrompt(text, rebased, images, turn, steer, prompts));
  }

  /**
   * Runs on {@link #sender}, so a prompt waits for the one before it, and for a rewind. Starts the agent if needed,
   * then hands it the prompt, unless the queue took it back meanwhile.
   */
  private void sendPrompt(String typed, String prompt, List<ImageAttachment> images, int turn, boolean steer,
    @Nullable Integer prompts) {
    this.opened.join();
    this.rewinding.join();
    if (this.isDisposed) {
      return;
    }
    AgentSession session = this.connection.live();
    if (session == null) {
      this.launch(this.record.sessionId, true).handle((ready, error) -> null).join();
      session = this.connection.live();
      if (session == null || this.state == State.STOPPED || this.isDisposed) {
        return;
      }
    }
    if (!this.queue.dispatch(turn, steer)) {
      if (this.state == State.STARTING && !this.queue.isRunning()) {
        this.setState(State.IDLE);
      }
      return;
    }
    int before = prompts != null ? prompts : this.promptsSent;
    this.promptsSent = before + 1;
    this.prompt(session, typed, prompt, images, turn, steer, before);
  }

  /**
   * Sends the prompt, or holds it while a git action runs in the thread's repository, so the agent does not change
   * files while they are committed. The thread counts as working first, so an action that has not checked yet stops.
   * Held prompts go out in order, off the calling thread, which may be the agent's own.
   */
  private void prompt(AgentSession session, String typed, String prompt, List<ImageAttachment> images, int turn,
    boolean steer, int prompts) {
    boolean holdsNone;
    synchronized (this.heldLock) {
      holdsNone = this.held.isDone();
    }
    this.checkpoint(prompts, typed, holdsNone && !steer && !this.turnRunning && this.queue.owesOnly(turn));
    this.queue.refresh();
    String folder = this.gitFolder();
    synchronized (this.heldLock) {
      boolean holds = !this.held.isDone() || folder != null && ThreadGit.hasActions(Path.of(folder));
      if (holds) {
        this.held = this.held.thenRunAsync(() -> {
          if (folder != null) {
            ThreadGit.awaitActions(Path.of(folder));
          }
          if (!this.isDisposed) {
            this.promptNow(session, typed, prompt, images, turn, steer);
          }
        }, AppExecutorUtil.getAppExecutorService());
        return;
      }
    }
    this.promptNow(session, typed, prompt, images, turn, steer);
  }

  private void promptNow(AgentSession session, String typed, String prompt, List<ImageAttachment> images, int turn,
    boolean steer) {
    this.error = null;
    this.conversation.send(turn, typed);
    Async.onEdt(this.host::activity);
    if (!this.isTitled && !typed.startsWith("/")) {
      this.isTitled = true;
      String title = titleOf(typed);
      Async.onEdt(() -> this.host.setTitle(title));
    }
    if (steer) {
      session.steer(prompt, images, turn).thenAccept(isTaken -> {
        if (!isTaken && session.isAlive() && this.queue.isSteering(turn)) {
          session.prompt(prompt, images, turn);
        }
      });
    } else {
      session.prompt(prompt, images, turn);
    }
    this.queue.refresh();
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
      ReviewTarget target = ReviewTarget.proposed(this.record.id, IdeFiles.absolute(this.project, preview.filePath()));
      Async.onEdt(() -> IdeFiles.showDiff(this.project, preview, title, target));
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
      Async.onEdt(() -> IdeFiles.showDiffs(this.project, previews,
        preview -> ReviewTarget.turn(this.record.id, IdeFiles.absolute(this.project, preview.filePath()))));
    });
  }

  /**
   * Snapshots the files for the prompt about to be sent, when the agent is idle. A prompt sent during a turn gets no
   * snapshot, since the turn may have half changed the files, and older ones of its number and later go.
   */
  private void checkpoint(int prompts, String typed, boolean idle) {
    String folder = this.projectPath();
    if (!this.canRewind || folder == null || prompts < 0 || this.isDisposed) {
      return;
    }
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    if (!idle) {
      Checkpoints.forget(Path.of(folder), this.record.id, prompts, environment);
      return;
    }
    try {
      this.saveDocuments();
      Checkpoints.capture(Path.of(folder), this.record.id, prompts, typed, environment);
    } catch (Worktrees.GitException e) {
      LOG.debug("No file checkpoint for thread " + this.record.id, e);
    }
  }

  /** Whether the files can go back to when the prompt after the first {@code prompts} was sent. Runs git. */
  public boolean hasCheckpoint(int prompts, String prompt) {
    String folder = this.projectPath();
    return folder != null
      && Checkpoints.find(Path.of(folder), this.record.id, prompts, prompt,
        EnvironmentUtil.getEnvironmentMap()) != null;
  }

  /**
   * Makes the agent forget the last of {@code prompts} and everything after it, and with {@code files} puts the
   * files back to when that prompt was sent. Only while the agent is idle or stopped. Posts one
   * {@link ChatMessage.Rewound} when done; the chat's timeline is replayed from the rewound session before.
   *
   * @param prompts the typed texts of the session's prompts up to and including the first one to forget
   */
  public void rewind(List<String> prompts, boolean files) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    synchronized (this.rewindLock) {
      boolean idle = this.state == State.IDLE || this.state == State.STOPPED;
      if (!this.rewinding.isDone() || !idle || prompts.isEmpty() || this.isDisposed) {
        this.view.post(new ChatMessage.Rewound(false, this.isDisposed || prompts.isEmpty()
          ? null
          : TakoBundle.message("error.rewind.busy")));
        return;
      }
      this.rewinding = done;
    }
    Async.inBackground(() -> {
      ChatMessage.Rewound result = new ChatMessage.Rewound(false, null);
      try {
        result = this.rewindNow(prompts, files);
      } catch (RuntimeException e) {
        LOG.warn("Could not rewind thread " + this.record.id, e);
        result = new ChatMessage.Rewound(false, Objects.toString(e.getMessage(), e.toString()));
      } finally {
        this.view.post(result);
        done.complete(null);
      }
    });
  }

  /**
   * Why the files in the folder must not be put back now: a git action or another thread's agent works in its
   * repository. Null when nothing does. Runs git.
   */
  private @Nullable String filesBlocker(String folder, Map<String, String> environment) {
    if (ThreadGit.hasActions(Path.of(folder))) {
      return TakoBundle.message("git.failed.running");
    }
    String topLevel = GitCommands.topLevel(Path.of(folder), environment);
    String other = topLevel == null ? null : this.host.busyThreadIn(topLevel);
    return other == null ? null : TakoBundle.message("error.rewind.folder.busy", other);
  }

  private ChatMessage.Rewound rewindNow(List<String> prompts, boolean files) {
    int kept = prompts.size() - 1;
    String folder = this.projectPath();
    Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
    String commit = null;
    if (files) {
      commit = folder == null
        ? null
        : Checkpoints.find(Path.of(folder), this.record.id, kept, prompts.getLast(), environment);
      if (commit == null) {
        return new ChatMessage.Rewound(false, TakoBundle.message("error.rewind.no.checkpoint"));
      }
      String blocker = this.filesBlocker(folder, environment);
      if (blocker != null) {
        return new ChatMessage.Rewound(false, blocker);
      }
    }
    this.setState(State.STARTING);
    this.connection.stop();
    if (this.isDisposed) {
      return new ChatMessage.Rewound(false, null);
    }
    String sessionId = this.record.sessionId;
    String rewound = null;
    if (kept > 0) {
      try {
        if (folder == null || sessionId == null) {
          throw new AgentException(TakoBundle.message("error.rewind.mismatch"));
        }
        rewound = this.backend.rewind(folder, sessionId, prompts);
      } catch (AgentException e) {
        this.keepsTimeline = true;
        this.stopped(null);
        this.view.post(new ChatMessage.Exit(0, ""));
        return new ChatMessage.Rewound(false, e.getMessage());
      }
    }
    if (this.isDisposed) {
      return new ChatMessage.Rewound(false, null);
    }
    this.record.sessionId = rewound;
    this.recordChanged();
    this.changes.clear();
    synchronized (this.answer) {
      this.answer.setLength(0);
    }
    this.keepsTimeline = false;
    this.turnRunning = false;
    String filesError = null;
    if (commit != null) {
      try {
        this.setState(State.WORKING);
        String blocker = this.filesBlocker(folder, environment);
        if (blocker != null) {
          throw new Worktrees.GitException(blocker);
        }
        this.restoreFiles(folder, commit, environment);
      } catch (Worktrees.GitException | RuntimeException e) {
        filesError = TakoBundle.message("error.rewind.files", Objects.toString(e.getMessage(), e.toString()));
      }
    }
    if (folder != null) {
      Checkpoints.forget(Path.of(folder), this.record.id, kept + 1, environment);
    }
    rewound = this.replayRewound(rewound, folder);
    if (this.isDisposed) {
      return new ChatMessage.Rewound(true, filesError);
    }
    this.launch(rewound, this.deferredSubmits.get() > 0).handle((ready, error) -> null).join();
    if (this.isDisposed) {
      this.connection.stop();
    }
    return new ChatMessage.Rewound(true, filesError);
  }

  /**
   * Shows the rewound session's past, or none for a new session. An agent that sends its history after it started
   * fills the emptied timeline then.
   *
   * @return the session to resume, null when it is gone
   */
  private @Nullable String replayRewound(@Nullable String sessionId, @Nullable String folder) {
    if (sessionId == null || folder == null) {
      this.replay(List.of());
      return null;
    }
    switch (this.backend.history(folder, sessionId)) {
      case AgentHistory.Loaded loaded -> this.replay(loaded.events());
      case AgentHistory.FromSession fromSession -> this.replay(List.of());
      case AgentHistory.Missing missing -> {
        this.replay(List.of());
        this.record.sessionId = null;
        this.recordChanged();
        return null;
      }
    }
    return sessionId;
  }

  /**
   * Writes unsaved editor changes to disk, so a snapshot or restore sees what the person sees. Waits for the EDT at
   * most {@link #SAVE_TIMEOUT_SECONDS}, then goes on with what is on disk.
   */
  private void saveDocuments() {
    CompletableFuture<Void> saved = new CompletableFuture<>();
    ApplicationManager.getApplication().invokeLater(() -> {
      FileDocumentManager.getInstance().saveAllDocuments();
      saved.complete(null);
    }, ModalityState.defaultModalityState());
    try {
      saved.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException | ExecutionException e) {
      LOG.debug("Editors not saved, e.g. while a dialog is open", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Saves the editors, puts the files back and lets the IDE see the changed ones. */
  private void restoreFiles(String folder, String commit, Map<String, String> environment)
    throws Worktrees.GitException {
    this.saveDocuments();
    List<File> refreshed = new ArrayList<>();
    for (Path path : Checkpoints.restore(Path.of(folder), this.record.id, commit, environment)) {
      Path existing = path;
      while (existing != null && !Files.exists(existing)) {
        existing = existing.getParent();
      }
      if (existing != null) {
        refreshed.add(existing.toFile());
      }
    }
    if (!refreshed.isEmpty()) {
      VfsUtil.markDirtyAndRefresh(true, false, true, refreshed.toArray(new File[0]));
    }
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

  /** Sends the next queued prompt only after the event was posted, so the chat hears of its turn after this one. */
  private void onEvent(AgentEvent event) {
    QueuedPrompts.Entry next = null;
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
      case AgentEvent.Busy busy -> {
        this.turnRunning = true;
        this.queue.busy();
        this.setState(State.WORKING);
      }
      case AgentEvent.Interrupted interrupted -> this.queue.interrupted();
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
      case AgentEvent.TurnEnded ended -> next = this.onTurnEnded(ended);
      case AgentEvent.PromptTaken taken -> next = this.onPromptTaken(taken);
      default -> {
      }
    }
    this.view.post(new ChatMessage.Event(event));
    if (next != null) {
      this.deliver(next.turn(), next.prompt(), false, null);
    }
  }

  /**
   * A prompt that joins the running turn keeps its changes and answer under that turn.
   *
   * @return the queued prompt to send next, when a steered prompt joined a turn that had already ended
   */
  private QueuedPrompts.@Nullable Entry onPromptTaken(AgentEvent.PromptTaken taken) {
    if (taken.startsTurn()) {
      this.turnRunning = true;
    }
    boolean isLate = this.queue.taken(taken.key(), taken.startsTurn());
    this.conversation.take(taken.key());
    if (taken.startsTurn()) {
      this.changes.startTurn(taken.key());
      synchronized (this.answer) {
        this.answer.setLength(0);
      }
    }
    return isLate ? this.queue.settle() : null;
  }

  /**
   * The thread stays working while prompts wait, unless the turn was stopped or failed.
   *
   * @return the queued prompt to send next
   */
  private QueuedPrompts.@Nullable Entry onTurnEnded(AgentEvent.TurnEnded ended) {
    this.turnRunning = false;
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
    return this.queue.turnEnded(ended.error() != null);
  }

  /** Under the queue's lock: the state follows whether a turn runs or a prompt is on its way. */
  private void queueChanged(ChatMessage.Queue snapshot) {
    State current = this.state;
    if (snapshot.running() && (current == State.IDLE || current == State.STOPPED || current == State.STARTING)) {
      this.setState(State.WORKING);
    } else if (!snapshot.running() && (current == State.WORKING || current == State.WAITING)) {
      this.setState(State.IDLE);
    }
    this.view.post(snapshot);
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

  /** The queued prompts wait for the next prompt sent, which starts the agent again. */
  private void stopped(@Nullable String error) {
    this.turnRunning = false;
    this.error = error;
    this.setState(State.STOPPED);
    this.queue.stopped();
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
