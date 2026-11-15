package io.firebite.tako.chat.thread;

import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.chat.git.GitActionFlow;
import io.firebite.tako.chat.git.ThreadGit;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.project.ForgeCli;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitInfo;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/** The thread's branch, worktree and pull request, which its record keeps, and the git actions on its checkout. */
public final class ChatGit {
  private static final long PULL_REQUEST_INTERVAL_MILLIS = 120_000;

  private final ThreadStore.Record record;
  private final Supplier<@Nullable String> folder;
  private final BooleanSupplier isDisposed;
  private final Runnable recordChanged;
  private final ThreadGit git;
  private volatile long lastPullRequestCheck;

  /** @param busyIn who works in the repository of a top level, see {@link ThreadGit} */
  ChatGit(ThreadStore.Record record, Supplier<@Nullable String> folder, BooleanSupplier isDisposed,
    Runnable recordChanged, Function<String, ThreadGit.@Nullable Busy> busyIn) {
    this.record = record;
    this.folder = folder;
    this.isDisposed = isDisposed;
    this.recordChanged = recordChanged;
    this.git = new ThreadGit(() -> {
      String directory = folder.get();
      return directory == null ? null : Path.of(directory);
    }, EnvironmentUtil.getEnvironmentMap(), busyIn, ForgeCli::of);
  }

  /**
   * Re-reads branch and worktree, which is local and cheap. Asks for the pull request only when the branch changed or
   * the last lookup is older than {@link #PULL_REQUEST_INTERVAL_MILLIS}. A thread in a folder of its own keeps its pull
   * request when none is found and it is not on another branch, e.g. once the merge deleted the branch, so auto archive
   * still sees how it ended. Call off the EDT.
   */
  void refresh() {
    String directory = this.folder.get();
    if (directory == null || this.isDisposed.getAsBoolean()) {
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
    boolean isOtherBranch = info.branch() != null && branchChanged;
    if (pullRequest == null && this.record.workPath != null && !isOtherBranch) {
      pullRequest = this.record.pullRequest;
    }
    if (!branchChanged && Objects.equals(info.worktree(), this.record.worktree)
      && Objects.equals(pullRequest, this.record.pullRequest)) {
      return;
    }
    this.record.branch = info.branch();
    this.record.worktree = info.worktree();
    this.record.pullRequest = pullRequest;
    this.recordChanged.run();
  }

  /** Like {@link #refresh}, and asks for the pull request whenever it last did. Call off the EDT. */
  void refreshNow() {
    this.lastPullRequestCheck = 0;
    this.refresh();
  }

  /**
   * The thread's git actions and the state they act on. Asks the forge nothing, except for the pull request of a
   * branch checked out since the last {@link #refresh}. Runs git, so call off the EDT.
   *
   * @see #checkForge
   */
  public ThreadGit.Menu menu() {
    ThreadGit.Menu menu = this.git.menu(this.record.pullRequest, this.record.branch);
    String branch = menu.status().branch();
    if (branch == null || branch.equals(this.record.branch)) {
      return menu;
    }
    this.refresh();
    return this.git.menu(this.record.pullRequest, this.record.branch);
  }

  /** Learns whether gh or glab can create the menu's pull request. Asks the forge, so call off the EDT. */
  public void checkForge(ThreadGit.Menu menu) {
    this.git.checkForge(menu);
  }

  /**
   * Asks for the action's texts while {@code backend} writes them from {@code conversation}, then runs it. Must run
   * on the EDT.
   *
   * @return completes on the EDT once it ran or the person cancelled it
   */
  CompletableFuture<Void> runAction(Project project, ThreadGit.Option option, GitCommands.Status status,
    String conversation, AgentBackend backend) {
    return GitActionFlow.start(project, this.git, option, status, conversation, backend, this::refreshNow);
  }
}
