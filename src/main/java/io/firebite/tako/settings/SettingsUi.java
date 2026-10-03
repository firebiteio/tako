package io.firebite.tako.settings;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import io.firebite.tako.agent.AgentInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Form pieces the agents' settings pages share. */
final class SettingsUi {
  private SettingsUi() {
  }

  /** A choice among the values, preceded by {@code ""}, which keeps the agent's own default. */
  static ComboBox<String> choiceBox(List<String> values, String defaultLabel, List<AgentInfo.Mode> labels) {
    List<String> items = new ArrayList<>();
    items.add("");
    for (String value : values) {
      if (!value.isEmpty()) {
        items.add(value);
      }
    }
    ComboBox<String> box = new ComboBox<>(items.toArray(String[]::new));
    box.setRenderer(BuilderKt.textListCellRenderer(
      value -> value == null || value.isEmpty() ? defaultLabel : label(value, labels)));
    return box;
  }

  static String selected(ComboBox<String> box) {
    return Objects.requireNonNullElse((String) box.getSelectedItem(), "");
  }

  private static String label(String value, List<AgentInfo.Mode> labels) {
    for (AgentInfo.Mode mode : labels) {
      if (mode.value().equals(value)) {
        return mode.label() + " (" + value + ")";
      }
    }
    return value;
  }
}
