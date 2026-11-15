package io.firebite.tako.ide.status;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.ChatService;
import io.firebite.tako.chat.thread.ChatController;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ui.FormatKt;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * How many threads work and wait for the person, and what today cost. A click shows the thread that needs a look.
 * Touched on the EDT, like the status bar, except for the cost.
 */
final class TakoStatusWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
  static final String ID = "Tako.Status";
  private static final long COST_DELAY_SECONDS = 10;
  private static final long COST_SECONDS = 60;

  private final ChatService service;
  private final Runnable listener = this::refresh;
  private final ScheduledFuture<?> costRefresh;
  private volatile double todayCost;
  private String text;
  private String tooltip = "";
  /** The thread a click shows: the first waiting for the person, else the first working one. */
  private @Nullable String target;
  private @Nullable StatusBar statusBar;

  TakoStatusWidget(Project project) {
    this.service = ChatService.getInstance(project);
    this.text = TakoBundle.message("status.widget.name");
    this.service.addActivityListener(this.listener);
    this.costRefresh = AppExecutorUtil.getAppScheduledExecutorService()
      .scheduleWithFixedDelay(this::refreshCost, COST_DELAY_SECONDS, COST_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public String ID() {
    return ID;
  }

  @Override
  public WidgetPresentation getPresentation() {
    return this;
  }

  @Override
  public void install(StatusBar bar) {
    this.statusBar = bar;
    this.refresh();
  }

  @Override
  public void dispose() {
    this.service.removeActivityListener(this.listener);
    this.costRefresh.cancel(false);
    this.statusBar = null;
  }

  @Override
  public String getText() {
    return this.text;
  }

  @Override
  public float getAlignment() {
    return Component.CENTER_ALIGNMENT;
  }

  @Override
  public String getTooltipText() {
    return this.tooltip;
  }

  @Override
  public Consumer<MouseEvent> getClickConsumer() {
    return event -> this.service.show(this.target);
  }

  private void refreshCost() {
    this.todayCost = this.service.sessions().todayCost();
    Async.onEdt(this::refresh);
  }

  private void refresh() {
    int waiting = 0;
    int working = 0;
    String firstWaiting = null;
    String firstWorking = null;
    StringBuilder lines = new StringBuilder();
    for (ChatService.ThreadActivity thread : this.service.activity()) {
      String state;
      if (thread.state() == ChatController.State.WAITING) {
        waiting++;
        firstWaiting = firstWaiting != null ? firstWaiting : thread.id();
        state = TakoBundle.message("status.state.waiting");
      } else if (thread.state() == ChatController.State.WORKING) {
        working++;
        firstWorking = firstWorking != null ? firstWorking : thread.id();
        state = TakoBundle.message("status.state.working");
      } else {
        continue;
      }
      lines.append("<br>").append(StringUtil.escapeXmlEntities(thread.title())).append(" · ").append(state);
    }
    this.target = firstWaiting != null ? firstWaiting : firstWorking;
    List<String> parts = new ArrayList<>();
    if (waiting > 0) {
      parts.add(TakoBundle.message("status.waiting", waiting));
    }
    if (working > 0) {
      parts.add(TakoBundle.message("status.working", working));
    }
    if (this.todayCost > 0) {
      parts.add(TakoBundle.message("status.today", FormatKt.money(this.todayCost)));
    }
    String name = TakoBundle.message("status.widget.name");
    String newText = parts.isEmpty() ? name : name + ": " + String.join(" · ", parts);
    String newTooltip = "<html>" + TakoBundle.message("status.tooltip") + lines + "</html>";
    StatusBar bar = this.statusBar;
    if (bar != null && !(newText.equals(this.text) && newTooltip.equals(this.tooltip))) {
      this.text = newText;
      this.tooltip = newTooltip;
      bar.updateWidget(ID);
    }
  }
}
