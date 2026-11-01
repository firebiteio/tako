package io.firebite.tako.chat.thread;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.project.SetupOutput;
import io.firebite.tako.ide.project.SetupScript;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the {@code .worktreesetup} commands in a thread's new worktree and holds the thread's prompts until they ran,
 * or the person starts the agent without them. A failed setup keeps the prompts waiting for a retry. The listener is
 * always called with this object's lock held, so what it decides from one call cannot interleave with the next.
 */
public final class WorktreeSetup {
  public static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(20);
  private static final long OUTPUT_INTERVAL_MILLIS = 250;
  /** How long output may still arrive after a command's shell exited, from children that outlive it. */
  private static final long DRAIN_MILLIS = 2000;
  private static final int BUFFER_SIZE = 8192;
  /**
   * Reads the commands' output. Its own threads, as a child that outlives its command can keep the pipe open and its
   * reader blocked until the child ends.
   */
  private static final ExecutorService READERS = Executors.newCachedThreadPool(
    Thread.ofPlatform().daemon().name("tako-setup-output-", 0).factory());

  private final Path worktree;
  private final Path repository;
  private final Listener listener;
  private final BooleanSupplier isTrusted;
  private final long timeoutMillis;
  private final ArrayDeque<Held> held = new ArrayDeque<>();

  /** Null until the first start. */
  private ChatMessage.@Nullable SetupPhase phase;
  /** Counts the starts; what an older one still reports is ignored. */
  private int run;
  private boolean isCancelled;
  private boolean isDisposed;
  /** The prompts may go to the agent: the setup is through and no held prompt is left. */
  private boolean isReady;
  private boolean isReleasing;
  /** Whether the chat was told of this setup, so it shows a card. */
  private boolean isShown;
  private @Nullable Process process;
  private int step;
  private int steps;
  private String command = "";
  private SetupOutput output = new SetupOutput();
  private @Nullable String error;
  private long startedAt;
  private long endedAt;
  private long lastPost;
  private @Nullable ScheduledFuture<?> flush;

  public WorktreeSetup(ThreadStore.PendingSetup target, Listener listener, BooleanSupplier isTrusted,
    long timeoutMillis) {
    this.worktree = Path.of(target.worktree);
    this.repository = Path.of(target.repository);
    this.listener = listener;
    this.isTrusted = isTrusted;
    this.timeoutMillis = timeoutMillis;
  }

  /** Runs the setup in the background. Does nothing while it runs, once it is through or after {@link #dispose}. */
  public synchronized void start() {
    if (this.isDisposed || (this.phase != null && this.phase != ChatMessage.SetupPhase.FAILED)) {
      return;
    }
    this.phase = ChatMessage.SetupPhase.RUNNING;
    int token = ++this.run;
    this.isCancelled = false;
    this.step = 0;
    this.steps = 0;
    this.command = "";
    this.output = new SetupOutput();
    this.error = null;
    this.startedAt = System.currentTimeMillis();
    this.endedAt = 0;
    if (this.isShown) {
      this.post();
    }
    if (!this.held.isEmpty()) {
      this.listener.held(null);
    }
    Async.inBackground(() -> this.runAll(token));
  }

  /** Runs the setup again after it failed; otherwise does nothing. */
  public synchronized void retry() {
    if (this.phase == ChatMessage.SetupPhase.FAILED) {
      this.start();
    }
  }

  /** Stops a running setup, which then counts as failed. */
  public boolean cancel() {
    return this.cancel(false);
  }

  /** Like {@link #cancel()}, but only while prompts wait for the setup. */
  public boolean cancelHeld() {
    return this.cancel(true);
  }

  private boolean cancel(boolean onlyHeld) {
    Process running;
    synchronized (this) {
      if (this.phase != ChatMessage.SetupPhase.RUNNING || this.isCancelled || (onlyHeld && this.held.isEmpty())) {
        return false;
      }
      this.isCancelled = true;
      running = this.process;
    }
    kill(running);
    return true;
  }

  /** Lets the prompts go to the agent without the setup, stopping it if it runs. */
  public void skip() {
    Process running;
    synchronized (this) {
      if (this.phase != ChatMessage.SetupPhase.RUNNING && this.phase != ChatMessage.SetupPhase.FAILED
        || this.isDisposed) {
        return;
      }
      this.run++;
      running = this.process;
      this.process = null;
      this.end(ChatMessage.SetupPhase.SKIPPED);
    }
    kill(running);
  }

  /** Runs {@code prompt} at once when the setup is through, else once it is, after what was held before it. */
  public void whenReady(Runnable prompt) {
    this.hold(new Held(prompt, true));
  }

  /** Like {@link #whenReady}, for starting the agent, which is no prompt the chat shows. */
  public void startWhenReady(Runnable start) {
    this.hold(new Held(start, false));
  }

  private void hold(Held task) {
    synchronized (this) {
      if (this.isDisposed) {
        return;
      }
      if (!this.isReady) {
        this.held.add(task);
        this.listener.held(this.failure());
        return;
      }
    }
    task.task().run();
  }

  /** Whether a prompt the chat shows waits for the setup. */
  public synchronized boolean holdsPrompts() {
    for (Held task : this.held) {
      if (task.isPrompt()) {
        return true;
      }
    }
    return false;
  }

  /** Tells the listener about the held prompts, or runs {@code idle} when there are none. */
  public synchronized void settle(Runnable idle) {
    if (this.held.isEmpty()) {
      idle.run();
    } else {
      this.listener.held(this.failure());
    }
  }

  private @Nullable String failure() {
    return this.phase == ChatMessage.SetupPhase.FAILED ? this.error : null;
  }

  public synchronized boolean holds() {
    return !this.held.isEmpty();
  }

  /** Whether prompts wait for a setup that failed. */
  public synchronized boolean holdsFailed() {
    return this.phase == ChatMessage.SetupPhase.FAILED && !this.held.isEmpty();
  }

  /** Whether the chat shows the setup's card. */
  public synchronized boolean showsCard() {
    return this.isShown
      && (this.phase == ChatMessage.SetupPhase.RUNNING || this.phase == ChatMessage.SetupPhase.FAILED);
  }

  /** Forgets the held prompts, e.g. when the agent's process ended, which ends the chat's turn. */
  public synchronized void dropHeld() {
    this.held.clear();
  }

  /**
   * Kills what runs and forgets the held prompts. The listener hears nothing more. The processes get their kill at once,
   * as the worktree may be removed next, which their open files would block on Windows.
   */
  public void dispose() {
    Process running;
    synchronized (this) {
      if (this.isDisposed) {
        return;
      }
      this.isDisposed = true;
      this.run++;
      this.held.clear();
      this.cancelFlush();
      running = this.process;
      this.process = null;
    }
    kill(running);
  }

  /** A worktree deleted outside the IDE has nothing left to set up, so its setup counts as through. */
  private void runAll(int token) {
    if (!Files.isDirectory(this.worktree)) {
      this.done(token);
      return;
    }
    List<String> commands;
    try {
      commands = SetupScript.read(this.worktree, this.repository);
    } catch (IOException | InvalidPathException e) {
      this.fail(token, TakoBundle.message("setup.error.read", e.getMessage()), true);
      return;
    }
    if (commands.isEmpty()) {
      this.done(token);
      return;
    }
    if (!this.isTrusted.getAsBoolean()) {
      this.fail(token, TakoBundle.message("setup.error.untrusted"), true);
      return;
    }
    Map<String, String> environment = SetupScript.environment(EnvironmentUtil.getEnvironmentMap(), this.worktree,
      this.repository);
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.timeoutMillis);
    for (int i = 0; i < commands.size(); i++) {
      String next = commands.get(i);
      synchronized (this) {
        if (token != this.run) {
          return;
        }
        if (this.isCancelled) {
          this.failLocked(TakoBundle.message("setup.error.cancelled"), false);
          return;
        }
        this.steps = commands.size();
        this.step = i + 1;
        this.command = next;
        this.output.finish();
        this.post();
      }
      String failure = this.runCommand(token, next, environment, deadline);
      if (failure != null) {
        this.fail(token, failure, true);
        return;
      }
    }
    this.done(token);
  }

  /** @return why the command failed, or null when it exited with 0 */
  private @Nullable String runCommand(int token, String line, Map<String, String> environment, long deadline) {
    ProcessBuilder builder = new ProcessBuilder(SetupScript.shell(line, SystemInfo.isWindows, environment));
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.redirectErrorStream(true);
    Process started;
    try {
      builder.directory(this.worktree.toFile());
      started = builder.start();
    } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
      return TakoBundle.message("setup.error.start", line, e.getMessage());
    }
    synchronized (this) {
      if (token != this.run || this.isCancelled) {
        kill(started);
        return TakoBundle.message("setup.error.cancelled");
      }
      this.process = started;
    }
    try {
      started.getOutputStream().close();
    } catch (IOException ignored) {
    }
    Future<?> reader = READERS.submit(() -> this.read(token, started));
    boolean isTimedOut;
    try {
      long left = deadline - System.nanoTime();
      isTimedOut = left <= 0 || !started.waitFor(left, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      kill(started);
      reader.cancel(true);
      this.forget(started);
      return TakoBundle.message("setup.error.cancelled");
    }
    if (isTimedOut) {
      kill(started);
    }
    drain(reader);
    if (this.forget(started)) {
      return TakoBundle.message("setup.error.cancelled");
    }
    if (isTimedOut) {
      long minutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(this.timeoutMillis));
      return TakoBundle.message("setup.error.timeout", String.valueOf(minutes));
    }
    int code = started.exitValue();
    return code == 0 ? null : TakoBundle.message("setup.error.exit", line, String.valueOf(code));
  }

  /**
   * Stops listening to the ended process, so output still read from it, e.g. from a child that outlived it, shows no
   * more.
   *
   * @return whether the setup was cancelled meanwhile
   */
  private synchronized boolean forget(Process ended) {
    if (this.process == ended) {
      this.process = null;
    }
    return this.isCancelled;
  }

  /**
   * Waits a little for the last output. A child that keeps the pipe open leaves its reader blocked until the child
   * ends; what it reads is ignored.
   */
  private static void drain(Future<?> reader) {
    try {
      reader.get(DRAIN_MILLIS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      reader.cancel(true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Reads the process's output as UTF-8, also when a character is split between two reads. */
  private void read(int token, Process started) {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE);
    ByteBuffer bytes = ByteBuffer.allocate(BUFFER_SIZE);
    CharBuffer chars = CharBuffer.allocate(BUFFER_SIZE);
    try (InputStream stream = started.getInputStream()) {
      while (true) {
        int count = stream.read(bytes.array(), bytes.position(), bytes.remaining());
        if (count < 0) {
          break;
        }
        bytes.position(bytes.position() + count);
        bytes.flip();
        decoder.decode(bytes, chars, false);
        bytes.compact();
        this.appendOutput(token, started, chars);
      }
      bytes.flip();
      decoder.decode(bytes, chars, true);
      decoder.flush(chars);
      this.appendOutput(token, started, chars);
    } catch (IOException ignored) {
    }
    synchronized (this) {
      if (this.isCurrent(token, started)) {
        this.output.finish();
      }
    }
  }

  /** Whether output of {@code started} still counts: it is the running command of the running setup. */
  private boolean isCurrent(int token, Process started) {
    return token == this.run && this.process == started && this.phase == ChatMessage.SetupPhase.RUNNING;
  }

  private synchronized void appendOutput(int token, Process started, CharBuffer chars) {
    chars.flip();
    String text = chars.toString();
    chars.clear();
    if (text.isEmpty() || !this.isCurrent(token, started)) {
      return;
    }
    this.output.append(text);
    long wait = this.lastPost + OUTPUT_INTERVAL_MILLIS - System.currentTimeMillis();
    if (wait <= 0) {
      this.post();
    } else if (this.flush == null) {
      this.flush = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> this.flushOutput(token), wait,
        TimeUnit.MILLISECONDS);
    }
  }

  private synchronized void flushOutput(int token) {
    this.flush = null;
    if (!this.isDisposed && token == this.run && this.phase == ChatMessage.SetupPhase.RUNNING) {
      this.post();
    }
  }

  private synchronized void fail(int token, String reason, boolean notifies) {
    if (token == this.run && !this.isDisposed) {
      this.failLocked(reason, notifies);
    }
  }

  /** A cancelled setup fails as cancelled, whatever its command did, and tells no one. */
  private void failLocked(String reason, boolean notifies) {
    this.phase = ChatMessage.SetupPhase.FAILED;
    this.error = this.isCancelled ? TakoBundle.message("setup.error.cancelled") : reason;
    boolean tells = notifies && !this.isCancelled;
    this.endedAt = System.currentTimeMillis();
    this.post();
    if (!this.held.isEmpty()) {
      this.listener.held(this.error);
    }
    if (tells) {
      this.listener.failed(reason);
    }
  }

  /** A cancel that came as the last command ended still counts, so the prompts keep waiting as asked. */
  private synchronized void done(int token) {
    if (token != this.run || this.isDisposed) {
      return;
    }
    if (this.isCancelled) {
      this.failLocked(TakoBundle.message("setup.error.cancelled"), false);
    } else {
      this.end(ChatMessage.SetupPhase.DONE);
    }
  }

  /** Ends the setup for good and lets the held prompts go. */
  private void end(ChatMessage.SetupPhase result) {
    this.phase = result;
    this.endedAt = System.currentTimeMillis();
    if (this.isShown) {
      this.post();
    }
    this.listener.finished();
    if (this.held.isEmpty()) {
      this.isReady = true;
    } else if (!this.isReleasing) {
      this.isReleasing = true;
      Async.inBackground(this::release);
    }
  }

  /** Runs the held prompts in order, one at a time, then lets new prompts through at once. */
  private void release() {
    while (true) {
      Held next;
      synchronized (this) {
        next = this.isDisposed ? null : this.held.poll();
        if (next == null) {
          this.isReleasing = false;
          this.isReady = !this.isDisposed;
          return;
        }
      }
      next.task().run();
    }
  }

  private void post() {
    this.cancelFlush();
    this.lastPost = System.currentTimeMillis();
    this.isShown = true;
    this.listener.changed(new ChatMessage.Setup(this.phase, this.step, this.steps, this.command, this.output.text(),
      this.error, this.startedAt, this.endedAt));
  }

  private void cancelFlush() {
    if (this.flush != null) {
      this.flush.cancel(false);
      this.flush = null;
    }
  }

  /**
   * Kills the process and everything it started, all at once, so none of them can start more. The children go first,
   * while the process still links them. Does not wait, so it may run on the EDT.
   */
  private static void kill(@Nullable Process target) {
    if (target == null) {
      return;
    }
    Iterator<ProcessHandle> descendants = target.descendants().iterator();
    while (descendants.hasNext()) {
      descendants.next().destroyForcibly();
    }
    target.destroyForcibly();
  }

  /** What the setup tells its thread. Called with the setup's lock held, so keep it short and non blocking. */
  public interface Listener {
    void changed(ChatMessage.Setup setup);

    /** Prompts wait for the setup that runs, or with {@code failure} for the person, because it failed. */
    void held(@Nullable String failure);

    /** The setup is through: it ran, there was nothing to run, or the person skipped it. */
    void finished();

    /** The setup failed, other than by cancelling it. */
    void failed(String reason);
  }

  /** @param isPrompt whether it sends a prompt the chat shows, rather than starting the agent */
  private record Held(
    Runnable task,
    boolean isPrompt
  ) {
  }
}
