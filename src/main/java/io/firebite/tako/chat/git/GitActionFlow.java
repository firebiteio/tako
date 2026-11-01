package io.firebite.tako.chat.git;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentBackend;
import io.firebite.tako.agent.AgentException;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.project.GitCommands;
import io.firebite.tako.ide.project.GitInfo;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a git action the person picked in a thread's header: asks for its texts while an agent writes them, runs it
 * with the IDE's progress and reports how it went in a notification.
 */
public final class GitActionFlow {
  private static final String NOTIFICATION_GROUP = "Tako";
  private static final int DETAIL_LENGTH = 2000;

  private GitActionFlow() {
  }

  /**
   * Must run on the EDT.
   *
   * @param backend      the thread's agent, which writes the texts if it can
   * @param conversation the thread's conversation, for the texts
   * @param refresh      reads the thread's git state again, called off the EDT after the action
   * @return completes on the EDT once the action ran or the person cancelled it
   */
  public static CompletableFuture<Void> start(Project project, ThreadGit git, ThreadGit.Option option,
    GitCommands.Status status, String conversation, AgentBackend backend, Runnable refresh) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    Draft draft = Draft.EMPTY;
    if (option.commits() || option.action().createsPullRequest()) {
      GitActionDialog dialog = new GitActionDialog(project, option, label(option), summary(option, status));
      dialog.showNote(TakoBundle.message("dialog.git.writing"));
      Async.inBackground(() -> write(dialog, git, option, status, conversation, backend));
      if (!dialog.showAndGet()) {
        done.complete(null);
        return done;
      }
      draft = dialog.draft();
    }
    run(project, git, option, status, draft, refresh, done);
    return done;
  }

  /** Has an agent write the texts and puts them into the dialog, unless it closed meanwhile. */
  private static void write(GitActionDialog dialog, ThreadGit git, ThreadGit.Option option,
    GitCommands.Status status, String conversation, AgentBackend backend) {
    AgentBackend writer = writer(backend);
    if (writer == null) {
      onDialog(dialog, () -> dialog.showNote(TakoBundle.message("dialog.git.no.agent")));
      return;
    }
    try {
      Draft draft = git.draft(option, status, conversation, writer::ask);
      onDialog(dialog, () -> dialog.fill(draft));
    } catch (AgentException | RuntimeException e) {
      String reason = e.getMessage() != null ? e.getMessage() : e.toString();
      onDialog(dialog, () -> dialog.showNote(TakoBundle.message("dialog.git.write.failed", reason)));
    }
  }

  /** The thread's agent if it answers one-shot prompts, else the first installed agent that does. */
  private static @Nullable AgentBackend writer(AgentBackend backend) {
    if (backend.canAsk()) {
      return backend;
    }
    for (AgentBackend other : AgentRegistry.backends(EnvironmentUtil.getEnvironmentMap())) {
      if (other.canAsk()) {
        return other;
      }
    }
    return null;
  }

  /** Runs on the EDT while the modal dialog shows, and not after it closed. */
  private static void onDialog(GitActionDialog dialog, Runnable update) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!dialog.isDisposed()) {
        update.run();
      }
    }, ModalityState.any());
  }

  private static void run(Project project, ThreadGit git, ThreadGit.Option option, GitCommands.Status status,
    Draft draft, Runnable refresh, CompletableFuture<Void> done) {
    new Task.Backgroundable(project, label(option), true) {
      @Override
      public void run(ProgressIndicator indicator) {
        try {
          ThreadGit.Result result = git.run(option, status, draft, step -> indicator.setText(stepText(step, status)),
            indicator::isCanceled);
          notifyDone(project, result);
        } catch (ThreadGit.ActionException e) {
          notifyFailed(project, e);
        }
      }

      @Override
      public void onFinished() {
        Async.inBackground(refresh);
        done.complete(null);
      }
    }.queue();
  }

  private static String label(ThreadGit.Option option) {
    return switch (option.action()) {
      case COMMIT -> TakoBundle.message("git.action.commit");
      case COMMIT_AND_PUSH -> option.commits()
        ? TakoBundle.message("git.action.commit.push")
        : TakoBundle.message("git.action.push");
      case COMMIT_AND_PULL_REQUEST -> option.commits()
        ? TakoBundle.message("git.action.commit.pull.request")
        : TakoBundle.message("git.action.pull.request");
    };
  }

  private static String summary(ThreadGit.Option option, GitCommands.Status status) {
    String target = status.remote() + "/" + status.branch();
    if (!option.commits()) {
      return TakoBundle.message("dialog.git.summary.branch", status.branch(), target);
    }
    return option.action().pushes()
      ? TakoBundle.message("dialog.git.summary.commit.push", status.changedFiles(), status.branch(), target)
      : TakoBundle.message("dialog.git.summary.commit", status.changedFiles(), status.branch());
  }

  private static String stepText(ThreadGit.Step step, GitCommands.Status status) {
    return switch (step) {
      case COMMIT -> TakoBundle.message("git.step.commit");
      case PUSH -> TakoBundle.message("git.step.push", status.remote() + "/" + status.branch());
      case PULL_REQUEST -> TakoBundle.message("git.step.pull.request");
    };
  }

  private static void notifyDone(Project project, ThreadGit.Result result) {
    GitInfo.PullRequest pullRequest = result.pullRequest();
    String text;
    if (pullRequest != null) {
      text = result.commit() != null
        ? TakoBundle.message("git.done.commit.pull.request", result.commit(), pullRequest.number())
        : TakoBundle.message("git.done.pull.request", pullRequest.number());
    } else if (result.pushedTo() != null) {
      text = result.commit() != null
        ? TakoBundle.message("git.done.commit.push", result.commit(), result.pushedTo())
        : TakoBundle.message("git.done.push", result.pushedTo());
    } else {
      text = TakoBundle.message("git.done.commit", String.valueOf(result.commit()));
    }
    Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(StringUtil.escapeXmlEntities(text), NotificationType.INFORMATION);
    if (pullRequest != null) {
      notification.addAction(NotificationAction.createSimpleExpiring(TakoBundle.message("git.open.pull.request"),
        () -> BrowserUtil.browse(pullRequest.url())));
    }
    notification.notify(project);
  }

  private static void notifyFailed(Project project, ThreadGit.ActionException failure) {
    String detail = failure.detail();
    String title = switch (failure.failure()) {
      case NOT_REPOSITORY -> TakoBundle.message("git.failed.not.repository");
      case ALREADY_RUNNING -> TakoBundle.message("git.failed.running");
      case BUSY -> TakoBundle.message("git.failed.busy", detail);
      case BRANCH_CHANGED -> TakoBundle.message("git.failed.branch.changed");
      case ON_DEFAULT_BRANCH -> TakoBundle.message("git.failed.default.branch", detail);
      case NOTHING_TO_COMMIT -> TakoBundle.message("git.failed.nothing.to.commit");
      case COMMIT_FAILED -> TakoBundle.message("git.failed.commit");
      case PUSH_REJECTED -> TakoBundle.message("git.failed.push.rejected");
      case PUSH_FAILED -> TakoBundle.message("git.failed.push");
      case PULL_REQUEST_FAILED -> TakoBundle.message("git.failed.pull.request");
      case CANCELLED -> TakoBundle.message("git.failed.cancelled");
    };
    boolean showsDetail = switch (failure.failure()) {
      case COMMIT_FAILED, PUSH_REJECTED, PUSH_FAILED, PULL_REQUEST_FAILED -> true;
      default -> false;
    };
    StringBuilder content = new StringBuilder();
    ThreadGit.Result done = failure.done();
    if (done.commit() != null) {
      content.append(TakoBundle.message("git.failed.after.commit", done.commit()));
    }
    if (done.pushedTo() != null) {
      content.append(content.isEmpty() ? "" : " ").append(TakoBundle.message("git.failed.after.push",
        done.pushedTo()));
    }
    if (showsDetail && !detail.isBlank()) {
      String tail = detail.length() <= DETAIL_LENGTH ? detail : "…" + detail.substring(detail.length() - DETAIL_LENGTH);
      content.append(content.isEmpty() ? "" : "\n\n").append(tail.strip());
    }
    String html = StringUtil.escapeXmlEntities(content.toString()).replace("\n", "<br>");
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(StringUtil.escapeXmlEntities(title), html, NotificationType.ERROR)
      .notify(project);
  }
}
