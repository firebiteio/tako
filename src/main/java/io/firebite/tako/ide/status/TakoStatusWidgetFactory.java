package io.firebite.tako.ide.status;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import io.firebite.tako.TakoBundle;

public final class TakoStatusWidgetFactory implements StatusBarWidgetFactory {
  @Override
  public String getId() {
    return TakoStatusWidget.ID;
  }

  @Override
  public String getDisplayName() {
    return TakoBundle.message("status.widget.name");
  }

  @Override
  public StatusBarWidget createWidget(Project project) {
    return new TakoStatusWidget(project);
  }
}
