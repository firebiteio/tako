package io.firebite.tako.chat.thread;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
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
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.agent.PermissionAnswer;
import io.firebite.tako.agent.PermissionPrompt;
import io.firebite.tako.agent.SessionCost;
import io.firebite.tako.chat.git.Blocker;
import io.firebite.tako.chat.git.GitDrafts;
import io.firebite.tako.chat.git.ThreadGit;
import io.firebite.tako.chat.store.PromptStash;
import io.firebite.tako.chat.store.ThreadPast;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.PromptComposer;
import io.firebite.tako.ide.editor.IdeFiles;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.Worktrees;
import io.firebite.tako.ui.bridge.message.ChatError;
import io.firebite.tako.ui.bridge.message.thread.ChatContextUsage;
import io.firebite.tako.ui.bridge.message.thread.Context;
import io.firebite.tako.ui.bridge.message.thread.Event;
import io.firebite.tako.ui.bridge.message.thread.Exit;
import io.firebite.tako.ui.bridge.message.thread.Init;
import io.firebite.tako.ui.bridge.message.thread.Notice;
import io.firebite.tako.ui.bridge.message.thread.Permission;
import io.firebite.tako.ui.bridge.message.thread.Queue;
import io.firebite.tako.ui.bridge.message.thread.QueueStashed;
import io.firebite.tako.ui.bridge.message.thread.Replay;
import io.firebite.tako.ui.bridge.message.thread.Rewound;
import io.firebite.tako.ui.bridge.message.thread.Setup;
import io.firebite.tako.ui.bridge.message.thread.SetupPhase;
import io.firebite.tako.ui.bridge.message.thread.Submit;
import java.io.IOException;
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
  private static final long CONTEXT_TIMEOUT_SECONDS = 10;
  private static final int SCRATCHPAD_FILES = 50;
  /** How long a switch waits for the new agent to start. */
  private static final long SWITCH_TIMEOUT_SECONDS = 120;
  /** How long a stopped agent's start may take to fail after the switch gave up on it. */
  private static final long STOP_TIMEOUT_SECONDS = 5;

  private final Project project;
  private final ChatView view;
  private final ChatHost host;
  private final ThreadStore.Record record;
  /** The agent the thread runs. Changes when the person switches agents, see {@link #switchAgent}. */
  private volatile AgentBackend backend;
  /** Done unless {@link #switchAgent} is switching, so a prompt sent meanwhile goes to the new agent. */
  private volatile CompletableFuture<Void> switching = CompletableFuture.completedFuture(null);
  /** The person stopped the running turn, whose end then counts as one without an error for the handoff. */
  private volatile boolean isInterrupted;
  /** The last launch failed, so {@link #retryFailedStart} starts the agent again. */
  private volatile boolean startFailed;
  private final AgentConnection connection = new AgentConnection();
  private final TurnChanges changes;
  /** Done once {@link #open} launched the agent, so a prompt sent meanwhile does not launch a second one. */
  private final CompletableFuture<Void> opened = new CompletableFuture<>();
  private final AtomicReference<Submit> firstPrompt = new AtomicReference<>();
  private final StringBuilder answer = new StringBuilder();
  private final Conversation conversation = new Conversation();
  private final HandoffTracker handoff;
  private final ChatGit git;
  private final ChatAttachments attachments;
  private final ChatDiffs diffs;
  private final FileCheckpoints checkpoints;
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

  /** The thread's state. Its monitor also guards a switch against {@link #dispose}. */
  private final StateCell status;
  private volatile boolean isDisposed;
  private volatile boolean hasTools;
  /** Whether the running agent takes prompts into its running turn. */
  private volatile boolean canSteer;
  private volatile @Nullable String error;
  private volatile @Nullable String workingDirectory;
  private volatile @Nullable String scratchpadPath;
  private volatile boolean isTitled;
  /** Set by {@link #restartSession} until the next launch, whose session's past the chat already shows. */
  private volatile boolean keepsTimeline;
  /** The setup of the thread's new worktree, which its prompts wait for, while it has not run yet. */
  private final @Nullable WorktreeSetup setup;
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
    this.isTitled = record.sessionId != null && !record.sideChat
      || !ThreadStore.Record.DEFAULT_TITLE.equals(record.title);
    this.changes = new TurnChanges(project);
    this.handoff = new HandoffTracker(record, this.conversation);
    this.status = new StateCell(host::stateChanged);
    ThreadStore.PendingSetup pending = record.pendingSetup;
    this.setup = pending == null
      ? null
      : new WorktreeSetup(pending, new SetupListener(), this::isTrusted, WorktreeSetup.TIMEOUT_MILLIS);
    this.git = new ChatGit(record, this::gitFolder, () -> this.isDisposed, this::recordChanged, this::busyIn);
    this.attachments = new ChatAttachments(project, view, this::projectPath);
    this.diffs = new ChatDiffs(project, view, record, this.changes, this.connection::live,
      () -> this.backend.displayName(), this::gitFolder);
    this.checkpoints = new FileCheckpoints(record, host);
  }

  /** Whether the IDE trusts the thread's project, and its project folder when that is another one than the IDE's. */
  private boolean isTrusted() {
    String folder = this.record.projectPath;
    boolean isOwnFolder = folder == null || ThreadStore.samePath(folder, this.project.getBasePath());
    return TrustedProjects.isProjectTrusted(this.project)
      && (isOwnFolder || TrustedProjects.isProjectTrusted(Path.of(folder)));
  }

  /**
   * Replays the thread's past, earlier agents' included, and starts its agent. In a new worktree the agent starts once
   * the worktree's setup is through, so it loads what the setup installed.
   */
  public void open() {
    this.open((Submit) null);
  }

  /** Like {@link #open()}, then sends {@code prompt} as if typed, once the agent is ready. Keeps the thread's title. */
  public void open(@Nullable String prompt) {
    this.open(prompt == null ? null : new Submit(prompt, List.of(), List.of()));
  }

  /** Like {@link #open(String)}, with the prompt's context and images. */
  public void open(@Nullable Submit prompt) {
    this.firstPrompt.set(prompt);
    if (prompt != null) {
      this.isTitled = true;
    }
    Async.inBackground(() -> {
      try {
        String resume = this.record.sessionId;
        String projectPath = this.projectPath();
        this.conversation.restore(ThreadPast.readSaved(this.host.pastFile(), this.record.agentSwitches));
        boolean replayed = false;
        if (resume != null && projectPath != null) {
          switch (this.backend.history(projectPath, resume)) {
            case AgentHistory.Loaded loaded -> {
              this.replay(loaded.events());
              replayed = true;
            }
            case AgentHistory.Missing missing -> {
              resume = null;
              this.record.sessionId = null;
            }
            case AgentHistory.FromSession fromSession -> {
            }
          }
        }
        if (!replayed && !this.conversation.past().isEmpty()) {
          this.replay(List.of());
        }
        if (resume != null && projectPath != null && this.record.costUsd == 0) {
          SessionCost cost = this.backend.sessionCost(projectPath, resume);
          if (cost != null) {
            this.record.setUsage(cost);
            this.recordChanged();
          }
        }
        if (this.setup != null) {
          String resumed = resume;
          this.setup.start();
          this.setup.startWhenReady(() -> this.launch(resumed));
        } else {
          this.launch(resume);
        }
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
    this.submit(new Submit(prompt, List.of(), List.of()));
  }

  private void submit(Submit prompt) {
    if (this.status.get() != State.WAITING) {
      this.status.set(State.WORKING);
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
    this.view.post(prompt);
  }

  /**
   * Stops the running turn. The queued prompts wait until a prompt is sent. When nothing is left running at once, e.g.
   * a prompt that had not reached the agent, the chat ends its turn as stopped, since no turn's end will. While prompts
   * wait for the worktree setup it cancels the setup instead, and while they wait after it failed it does nothing: the
   * setup's card offers the way on.
   */
  public void interrupt() {
    if (this.setup != null && (this.setup.holdsFailed() || this.setup.cancelHeld())) {
      return;
    }
    this.isInterrupted = true;
    if (this.queue.stop()) {
      this.view.post(new Event(new AgentEvent.Interrupted()));
      this.view.post(new Event(new AgentEvent.TurnEnded(null, null, null)));
    }
    this.connection.ifLive(AgentSession::interrupt);
  }

  public void retrySetup() {
    if (this.setup != null) {
      this.setup.retry();
    }
  }

  /** Sends the prompts that wait for the worktree setup without it. */
  public void skipSetup() {
    if (this.setup != null) {
      this.setup.skip();
    }
  }

  public void cancelSetup() {
    if (this.setup != null) {
      this.setup.cancel();
    }
  }

  /**
   * Answers a {@link Permission}.
   *
   * @param answers    question → answer
   * @param suggestion the index of the suggestion approved with
   */
  public void answer(String requestId, boolean allow, @Nullable String message, boolean interrupt,
    @Nullable Map<String, String> answers, @Nullable Integer suggestion) {
    this.status.set(State.WORKING);
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

  /** Starts the agent again, e.g. after its process ended. Not while the thread switches agents. */
  public void restart() {
    if (!this.switching.isDone()) {
      return;
    }
    Async.inBackground(() -> {
      this.rewinding.join();
      this.launch(this.record.sessionId);
    });
  }

  /** Starts the agent again if its last start failed, e.g. once the settings that broke it changed. */
  public void retryFailedStart() {
    if (this.startFailed && this.status.get() == State.STOPPED) {
      this.restart();
    }
  }

  /**
   * Moves the thread to another agent, which starts a new session now. The next prompt tells it what happened so far,
   * see {@link Handoff}. Only while the agent is idle or stopped and the thread is not loading, switching or rewinding.
   * Does nothing when {@code target} cannot start, e.g. its CLI is missing, and says so.
   *
   * @return whether the switch began
   */
  public boolean switchAgent(AgentBackend target) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    State previous;
    synchronized (this.status) {
      previous = this.status.get();
      if (!this.canSwitch() || target.id().equals(this.backend.id())) {
        return false;
      }
      this.switching = done;
      this.status.set(State.STARTING);
    }
    Async.inBackground(() -> {
      try {
        this.switchTo(target, previous);
      } finally {
        done.complete(null);
      }
    });
    return true;
  }

  private void switchTo(AgentBackend target, State previous) {
    AgentBackend from = this.backend;
    if (!target.canStart()) {
      this.status.set(this.stateAfterRejectedSwitch(previous));
      this.view.post(new Notice(TakoBundle.message("error.switch.unavailable", target.displayName())));
      return;
    }
    ThreadPast past = this.conversation.past(from.displayName(), target.displayName());
    synchronized (this.status) {
      if (this.isDisposed) {
        return;
      }
      try {
        past.write(this.host.pastFile());
      } catch (IOException e) {
        LOG.warn("Could not save the conversation before switching agents", e);
        this.status.set(this.stateAfterRejectedSwitch(previous));
        this.view.post(new Notice(TakoBundle.message("error.switch.save", e.getMessage())));
        return;
      }
    }
    this.connection.stop();
    synchronized (this.status) {
      if (this.isDisposed) {
        return;
      }
      if (this.record.handoffFrom == null || !past.segments().getLast().turns().isEmpty()) {
        this.record.handoffFrom = from.displayName();
      }
      this.record.endSession();
      this.record.useAgent(target.id());
      this.record.agentSwitches = past.segments().size();
      this.backend = target;
      this.scratchpadPath = null;
      this.workingDirectory = null;
      this.error = null;
      synchronized (this.answer) {
        this.answer.setLength(0);
      }
      this.promptsSent = 0;
      this.turnRunning = false;
      this.handoff.reset();
      this.keepsTimeline = true;
      this.conversation.restore(past);
    }
    this.recordChanged();
    this.view.post(new Event(new AgentEvent.AgentSwitched(from.displayName(), target.displayName())));
    if (this.isDisposed) {
      return;
    }
    CompletableFuture<?> launched = this.launch(null, false);
    if (!this.awaitLaunch(launched, SWITCH_TIMEOUT_SECONDS)) {
      this.connection.stop();
      if (!this.awaitLaunch(launched, STOP_TIMEOUT_SECONDS)) {
        String message = TakoBundle.message("error.switch.timeout", target.displayName(), SWITCH_TIMEOUT_SECONDS);
        this.stopped(message);
        this.postError(message);
      }
    }
    if (this.isDisposed || this.status.get() == State.STOPPED) {
      this.connection.stop();
    }
  }

  /**
   * Waits for a launch, which reports its own failure.
   *
   * @return false when it is still running after {@code seconds}
   */
  private boolean awaitLaunch(CompletableFuture<?> launched, long seconds) {
    try {
      launched.get(seconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      return false;
    } catch (ExecutionException e) {
      LOG.debug("The agent switched to did not start", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  /** The state before a switch that did not happen, unless the agent stopped meanwhile. */
  private State stateAfterRejectedSwitch(State previous) {
    return this.connection.live() == null ? State.STOPPED : previous;
  }

  /**
   * Whether the thread holds prompts or answers, its earlier agents' included. A stopped agent's session counts too:
   * agents that give its history only while running may hold some the thread has not seen.
   */
  public boolean hasConversation() {
    return !this.conversation.isEmpty() || (this.record.sessionId != null && this.status.get() == State.STOPPED);
  }

  /**
   * Whether the thread's agent may change now: it is idle or stopped, and the thread neither loads, switches, rewinds
   * nor has a prompt on its way to the agent.
   */
  public boolean canSwitch() {
    State current = this.status.get();
    return !this.isDisposed && this.opened.isDone() && this.switching.isDone() && this.rewinding.isDone()
      && !this.queue.isRunning() && (current == State.IDLE || current == State.STOPPED);
  }

  /** Whether a prompt is on its way to the agent, or the agent works on one or waits for an answer. */
  public boolean hasPromptInFlight() {
    return this.queue.isRunning() || this.status.get().isInTurn();
  }

  /**
   * Stops the agent so the next prompt resumes its session in a new process, which loads skills, plugins, MCP servers
   * and instructions again. Only while the agent is idle. The chat keeps its timeline and the last turn's error.
   */
  public void restartSession() {
    if (this.status.get() != State.IDLE || !this.switching.isDone()) {
      return;
    }
    Async.inBackground(() -> {
      this.rewinding.join();
      if (this.status.get() != State.IDLE) {
        return;
      }
      this.keepsTimeline = true;
      this.connection.stop();
      this.stopped(this.error);
      this.view.post(new Exit(0, ""));
    });
  }

  /** Re-reads the thread's branch, worktree and maybe pull request, see {@link ChatGit#refresh}. Call off the EDT. */
  public void refreshGit() {
    this.git.refresh();
  }

  /** The folder git actions work on: where the agent says it works, else the thread's folder. */
  public @Nullable String gitFolder() {
    return this.workingDirectory != null ? this.workingDirectory : this.projectPath();
  }

  public ChatGit git() {
    return this.git;
  }

  public ChatAttachments attachments() {
    return this.attachments;
  }

  public ChatDiffs diffs() {
    return this.diffs;
  }

  /**
   * Asks for the action's texts while the thread's agent writes them, then runs it. Must run on the EDT.
   *
   * @param status the state the menu showed
   * @return completes on the EDT once it ran or the person cancelled it
   */
  public CompletableFuture<Void> runGitAction(ThreadGit.Option option, GitCommands.Status status) {
    return this.git.runAction(this.project, option, status, this.conversation(GitDrafts.MAX_CONVERSATION),
      this.backend);
  }

  /** This thread's agent, or another thread's, working in the repository. */
  private @Nullable ThreadGit.Busy busyIn(String topLevel) {
    if (this.status.get().isInTurn()) {
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
    return this.status.get();
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
    this.view.post(new Context(item, prefill));
  }

  /** See {@link ChatAttachments#attachFiles}. */
  public void attachFiles(List<Path> files) {
    this.attachments.attachFiles(files);
  }

  /**
   * Waits until the agent is no longer {@link State#isBusy busy}, the timeout passed or the thread closed.
   *
   * @return the state then
   */
  public State awaitSettled(long timeoutMillis) throws InterruptedException {
    return this.status.awaitSettled(timeoutMillis);
  }

  /**
   * Like {@link #awaitSettled} without blocking: completes with the state once the agent is no longer busy or the
   * thread closed.
   */
  public CompletableFuture<State> settled() {
    return this.status.settled();
  }

  @Override
  public void dispose() {
    synchronized (this.status) {
      this.isDisposed = true;
      this.status.close();
    }
    this.stash(false);
    if (this.setup != null) {
      this.disposeSetup(this.setup);
    }
    this.connection.stop();
  }

  /** Puts the queued prompts in the project's prompt stash now and closes the queue, e.g. as the project closes. */
  public void stashQueued() {
    this.stash(true);
  }

  /** @param now write on this thread, else on a pooled one */
  private void stash(boolean now) {
    List<Submit> prompts = this.queue.drain();
    if (prompts.isEmpty() || this.project.isDisposed()) {
      return;
    }
    Runnable write = () -> {
      if (this.project.isDisposed()) {
        LOG.warn("The project closed before the queued prompts of thread " + this.record.id + " were stashed");
        return;
      }
      try {
        int kept = PromptStash.getInstance(this.project).pushAll(prompts);
        this.view.post(new QueueStashed(kept));
      } catch (IOException | IllegalStateException e) {
        LOG.warn("Could not stash the queued prompts of thread " + this.record.id, e);
      }
    };
    if (now) {
      write.run();
    } else {
      Async.inBackground(write);
    }
  }

  /** Stops the setup and clears what the chat shows of it: its card, and the turn of a prompt that waited for it. */
  private void disposeSetup(WorktreeSetup worktreeSetup) {
    boolean holds = worktreeSetup.holdsPrompts();
    boolean showsCard = worktreeSetup.showsCard();
    worktreeSetup.dispose();
    if (showsCard) {
      this.view.post(new Setup(SetupPhase.DONE, 0, 0, "", "", null, 0, 0));
    }
    if (holds) {
      this.view.post(new Exit(0, ""));
    }
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
    this.status.set(State.STARTING);
    this.startFailed = false;
    AgentSession session;
    try {
      session = this.connection.replace(this.backend, launch,
        this.keepsTimeline ? new Listener(false, resumeSessionId) : new Listener(true, null));
    } catch (AgentException e) {
      this.startFailed(e.getMessage());
      return CompletableFuture.completedFuture(null);
    }
    this.keepsTimeline = false;
    return session.initialize().whenComplete((info, error) -> {
      if (error != null) {
        this.startFailed(TakoBundle.message("error.agent.not.started", this.backend.displayName(),
          AgentException.messageOf(error)));
        return;
      }
      this.host.started(info);
      this.hasTools = launch.toolsUrl() != null && session.takesTools();
      this.canRewind = info.capabilities().rewind() && !this.record.sideChat;
      this.canSteer = info.capabilities().steer();
      Submit prompt = this.firstPrompt.getAndSet(null);
      this.view.post(new Init(info, this.projectPath()));
      this.refreshContext();
      if (prompt != null) {
        this.submit(prompt);
      } else if (!forPrompt && this.deferredSubmits.get() == 0 && this.setup != null) {
        this.setup.settle(this.status::idleIfStarting);
      } else if (!forPrompt && this.deferredSubmits.get() == 0) {
        this.status.idleIfStarting();
      }
    });
  }

  /**
   * Sends a prompt, starting the agent if it is not running. While a turn runs, the prompt waits in the queue or, if
   * asked and the agent can, steers that turn. Call on the EDT.
   *
   * @param turn    the chat's number for this prompt's turn, which {@link ChatDiffs#showTurnDiff} names later
   * @param prompts how many prompts the session got before this one, which names its file checkpoint
   * @return what became of it: sent, queued or steering
   */
  public QueuedPrompts.Action send(String text, List<ContextItem> context, List<ImageAttachment> images, int turn,
    int prompts, QueuedPrompts.Delivery delivery) {
    Submit prompt = new Submit(text, context, images);
    QueuedPrompts.Action action = this.queue.offer(turn, prompt, delivery, this.canSteer);
    if (action != QueuedPrompts.Action.HOLD && !this.queue.isClosed()) {
      this.deliver(turn, prompt, action == QueuedPrompts.Action.STEER,
        action == QueuedPrompts.Action.SEND ? prompts : null);
    }
    return action;
  }

  /** Takes a queued prompt out of the queue, e.g. to edit it. Null once it is on its way. */
  public @Nullable Submit unqueue(int turn) {
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
    Submit prompt = this.queue.promptOf(turn);
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
    Submit prompt = this.queue.promptOf(turn);
    if (prompt != null && this.queue.steer(turn, this.canSteer)) {
      this.deliver(turn, prompt, true, null);
    }
  }

  /**
   * Composes the prompt and hands it to the agent after the prompts handed before it.
   *
   * @param prompts how many prompts the session got before this one, or null to count on from the last one sent
   */
  private void deliver(int turn, Submit prompt, boolean steer, @Nullable Integer prompts) {
    List<ImageAttachment> images = new ArrayList<>();
    for (ImageAttachment image : prompt.images()) {
      if (image.isValid()) {
        images.add(image);
      }
    }
    String text = prompt.text();
    Async.onEdt(() -> this.host.prompted(text));
    String composed = text.isBlank() && !images.isEmpty()
      ? ""
      : PromptComposer.compose(text, prompt.context());
    String folder = this.projectFolder();
    String rebased = this.record.workPath != null && folder != null
      ? PromptComposer.rebase(composed, folder, this.record.workPath)
      : composed;
    this.sender.execute(() -> this.sendPrompt(text, rebased, images, turn, steer, prompts));
  }

  /** Runs on {@link #sender}, so a prompt waits for the one before it, for the worktree setup and for a rewind. */
  private void sendPrompt(String typed, String prompt, List<ImageAttachment> images, int turn, boolean steer,
    @Nullable Integer prompts) {
    this.opened.join();
    if (this.setup != null) {
      this.setup.whenReady(() -> this.sendReady(typed, prompt, images, turn, steer, prompts));
    } else {
      this.sendReady(typed, prompt, images, turn, steer, prompts);
    }
  }

  /**
   * Runs on {@link #sender}, or in order on the setup's thread for prompts that waited for it. Starts the agent if
   * needed, then hands it the prompt, unless the queue took it back meanwhile.
   */
  private void sendReady(String typed, String prompt, List<ImageAttachment> images, int turn, boolean steer,
    @Nullable Integer prompts) {
    if (this.isDisposed) {
      return;
    }
    this.rewinding.join();
    this.switching.join();
    if (this.isDisposed) {
      return;
    }
    AgentSession session = this.connection.live();
    if (session == null) {
      this.launch(this.record.sessionId, true).handle((ready, error) -> null).join();
      session = this.connection.live();
      if (session == null || this.status.get() == State.STOPPED || this.isDisposed) {
        return;
      }
    }
    if (!this.queue.dispatch(turn, steer)) {
      if (this.status.get() == State.STARTING && !this.queue.isRunning()) {
        this.status.set(State.IDLE);
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
    String sent = this.handoff.withHandoff(typed, prompt, turn, this.projectPath(), this.projectFolder());
    this.conversation.send(turn, typed, images.size());
    Async.onEdt(this.host::activity);
    if (!this.isTitled && !typed.startsWith("/")) {
      this.isTitled = true;
      String title = ThreadTitles.fromPrompt(typed);
      Async.onEdt(() -> this.host.setTitle(title));
    }
    if (steer) {
      session.steer(sent, images, turn).thenAccept(isTaken -> {
        if (isTaken || !session.isAlive()) {
          return;
        }
        if (this.queue.steerRefused(turn)) {
          session.prompt(sent, images, turn);
          return;
        }
        this.handoff.refused(turn);
        QueuedPrompts.Entry next = this.queue.settle();
        if (next != null) {
          this.deliver(next.turn(), next.prompt(), false, null);
        }
      });
    } else {
      session.prompt(sent, images, turn);
    }
    this.queue.refresh();
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
    if (idle) {
      this.checkpoints.capture(folder, prompts, typed);
    } else {
      this.checkpoints.forget(folder, prompts);
    }
  }

  /** Whether the files can go back to when the prompt after the first {@code prompts} was sent. Runs git. */
  public boolean hasCheckpoint(int prompts, String prompt) {
    String folder = this.projectPath();
    return folder != null && this.checkpoints.find(folder, prompts, prompt) != null;
  }

  /**
   * Makes the agent forget the last of {@code prompts} and everything after it, and with {@code files} puts the
   * files back to when that prompt was sent. Only while the agent is idle or stopped. Posts one
   * {@link Rewound} when done; the chat's timeline is replayed from the rewound session before.
   *
   * @param prompts the typed texts of the session's prompts up to and including the first one to forget
   */
  public void rewind(List<String> prompts, boolean files) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    synchronized (this.rewindLock) {
      boolean idle = this.status.get() == State.IDLE || this.status.get() == State.STOPPED;
      if (!this.rewinding.isDone() || !this.switching.isDone() || !idle || prompts.isEmpty() || this.isDisposed) {
        this.view.post(new Rewound(false, this.isDisposed || prompts.isEmpty()
          ? null
          : TakoBundle.message("error.rewind.busy")));
        return;
      }
      this.rewinding = done;
    }
    Async.inBackground(() -> {
      Rewound result = new Rewound(false, null);
      try {
        result = this.rewindNow(prompts, files);
      } catch (RuntimeException e) {
        LOG.warn("Could not rewind thread " + this.record.id, e);
        result = new Rewound(false, Objects.toString(e.getMessage(), e.toString()));
      } finally {
        this.view.post(result);
        done.complete(null);
      }
    });
  }

  private Rewound rewindNow(List<String> prompts, boolean files) {
    int kept = prompts.size() - 1;
    String folder = this.projectPath();
    String commit = null;
    if (files) {
      commit = folder == null ? null : this.checkpoints.find(folder, kept, prompts.getLast());
      if (commit == null) {
        return new Rewound(false, TakoBundle.message("error.rewind.no.checkpoint"));
      }
      String blocker = this.checkpoints.blocker(folder);
      if (blocker != null) {
        return new Rewound(false, blocker);
      }
    }
    this.status.set(State.STARTING);
    this.connection.stop();
    if (this.isDisposed) {
      return new Rewound(false, null);
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
        this.view.post(new Exit(0, ""));
        return new Rewound(false, e.getMessage());
      }
    }
    if (this.isDisposed) {
      return new Rewound(false, null);
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
        this.status.set(State.WORKING);
        String blocker = this.checkpoints.blocker(folder);
        if (blocker != null) {
          throw new Worktrees.GitException(blocker);
        }
        this.checkpoints.restore(folder, commit);
      } catch (Worktrees.GitException | RuntimeException e) {
        filesError = TakoBundle.message("error.rewind.files", Objects.toString(e.getMessage(), e.toString()));
      }
    }
    if (folder != null) {
      this.checkpoints.forget(folder, kept + 1);
    }
    rewound = this.replayRewound(rewound, folder);
    if (this.isDisposed) {
      return new Rewound(true, filesError);
    }
    this.launch(rewound, this.deferredSubmits.get() > 0).handle((ready, error) -> null).join();
    if (this.isDisposed) {
      this.connection.stop();
    }
    return new Rewound(true, filesError);
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
   * The files in the agent's scratchpad, relative to it with {@code /} separators, newest first. Null without a
   * scratchpad. Lists the folder, so call off the EDT.
   */
  public @Nullable List<String> scratchpadFiles() {
    String directory = this.scratchpadPath;
    return directory == null ? null : IdeFiles.newestFiles(Path.of(directory), SCRATCHPAD_FILES);
  }

  /** Asks the agent how full its context is, which arrives as {@link ChatContextUsage}. */
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
        this.view.post(new ChatContextUsage(breakdown));
      });
    });
  }

  /** Shows the earlier agents' past and then the session's. */
  private void replay(List<AgentEvent> events) {
    this.conversation.reset(events);
    List<AgentEvent> all = new ArrayList<>(this.conversation.past().events());
    all.addAll(events);
    this.view.post(new Replay(all));
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
        this.status.set(State.WORKING);
      }
      case AgentEvent.Interrupted interrupted -> this.queue.interrupted();
      case AgentEvent.PermissionCancelled cancelled -> this.status.set(State.WORKING);
      case AgentEvent.Text text -> {
        if (text.parentId() == null && !text.text().isBlank()) {
          synchronized (this.answer) {
            this.answer.append(this.answer.isEmpty() ? "" : "\n\n").append(text.text());
          }
        }
        this.conversation.add(text);
      }
      case AgentEvent.ToolStarted tool -> {
        this.changes.onToolStarted(tool);
        this.conversation.add(tool);
      }
      case AgentEvent.ToolFinished result -> {
        this.changes.onToolFinished(result);
        this.conversation.add(result);
      }
      case AgentEvent.LimitReached limit -> Async.onEdt(() -> this.host.limitReached(limit.label(), limit.resetsAt(),
        limit.namesReset()));
      case AgentEvent.TurnEnded ended -> next = this.onTurnEnded(ended);
      case AgentEvent.PromptTaken taken -> next = this.onPromptTaken(taken);
      default -> {
      }
    }
    this.view.post(new Event(event));
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
    this.handoff.taken(taken.key());
    if (taken.startsTurn()) {
      this.changes.startTurn(taken.key());
      synchronized (this.answer) {
        this.answer.setLength(0);
      }
    }
    return isLate ? this.queue.settle() : null;
  }

  /**
   * The thread stays working while prompts wait, unless the turn was stopped or failed. The handoff arrived once its
   * turn ended without an error or was stopped by the person, since the session holds the prompt then.
   *
   * @return the queued prompt to send next
   */
  private QueuedPrompts.@Nullable Entry onTurnEnded(AgentEvent.TurnEnded ended) {
    this.turnRunning = false;
    boolean wasInterrupted = this.isInterrupted;
    this.isInterrupted = false;
    if (this.handoff.turnEnded(ended.error() == null || wasInterrupted)) {
      this.record.handoffFrom = null;
      this.recordChanged();
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
    Async.inBackground(this.git::refreshNow);
    return this.queue.turnEnded(ended.error() != null);
  }

  /**
   * Under the queue's lock: the state follows whether a turn runs or a prompt is on its way. A thread done working
   * needs a look.
   */
  private void queueChanged(Queue snapshot) {
    State current = this.status.get();
    if (snapshot.running() && (current == State.IDLE || current == State.STOPPED || current == State.STARTING)) {
      this.status.set(State.WORKING);
    } else if (!snapshot.running() && (current == State.WORKING || current == State.WAITING)) {
      this.status.set(State.IDLE);
      Async.onEdt(() -> this.host.needsLook(false));
    }
    this.view.post(snapshot);
  }

  private void onPermission(String requestId, PermissionPrompt prompt) {
    this.status.set(State.WAITING);
    this.view.post(new Permission(requestId, prompt));
    String agent = this.backend.displayName();
    String reason = prompt.kind() == PermissionPrompt.Kind.QUESTION
      ? TakoBundle.message("notification.question", agent)
      : TakoBundle.message("notification.tool", agent, prompt.toolName());
    Async.onEdt(() -> {
      this.host.needsLook(true);
      this.host.requestAttention(reason);
    });
  }

  /** The queued prompts wait for the next prompt sent, which starts the agent again. */
  private void stopped(@Nullable String error) {
    this.turnRunning = false;
    this.handoff.reset();
    if (this.setup != null) {
      this.setup.dropHeld();
    }
    this.error = error;
    this.status.set(State.STOPPED);
    this.queue.stopped();
  }

  private void recordChanged() {
    Async.onEdt(this.host::recordChanged);
  }

  private void startFailed(String message) {
    this.stopped(message);
    this.startFailed = true;
    this.view.post(new ChatError(message, true));
  }

  private void postError(String text) {
    this.view.post(new ChatError(text));
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

  /** Shows the setup in the chat and keeps the thread working, or waiting for the person, while prompts wait for it. */
  private final class SetupListener implements WorktreeSetup.Listener {
    /** The thread's error this setup set, which goes once the setup is through. */
    private @Nullable String error;

    @Override
    public void changed(Setup setup) {
      ChatController.this.view.post(setup);
    }

    @Override
    public void held(@Nullable String failure) {
      this.error = failure != null ? TakoBundle.message("setup.failed", failure) : null;
      ChatController.this.error = this.error;
      ChatController.this.status.set(failure != null ? State.WAITING : State.WORKING);
    }

    @Override
    public void finished() {
      if (this.error != null && this.error.equals(ChatController.this.error)) {
        ChatController.this.error = null;
      }
      this.error = null;
      ChatController.this.record.pendingSetup = null;
      ChatController.this.recordChanged();
    }

    @Override
    public void failed(String reason) {
      String text = TakoBundle.message("setup.failed", reason);
      Async.onEdt(() -> ChatController.this.host.requestAttention(text));
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
          ChatController.this.view.post(new Notice(
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
      ChatController.this.view.post(new Exit(exitCode, stderrTail));
    }
  }
}
