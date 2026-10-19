package io.firebite.tako.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.FormBuilder;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.ChatService;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/** Settings | Tools | Tako, with what all agents share. Each agent has a page of its own below it. */
public final class ChatSettingsConfigurable implements Configurable {
  private ComboBox<AgentRegistry.Choice> defaultAgentBox;
  private JBCheckBox parallelRunsBox;

  @Override
  public @Nls String getDisplayName() {
    return TakoBundle.message("configurable.tako");
  }

  @Override
  public @Nullable JComponent createComponent() {
    this.defaultAgentBox = new ComboBox<>();
    this.defaultAgentBox.setRenderer(BuilderKt.textListCellRenderer(choice -> choice == null ? "" : choice.name()));
    this.parallelRunsBox = new JBCheckBox(TakoBundle.message("settings.tako.parallel.runs"));
    JPanel panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(TakoBundle.message("settings.tako.default.agent"), this.defaultAgentBox)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.tako.default.agent.hint")))
      .addComponent(this.parallelRunsBox)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.tako.parallel.runs.hint")))
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
    this.reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    ChatSettings.Data data = ChatSettings.state();
    return !this.selectedId().equals(data.defaultAgent) || this.parallelRunsBox.isSelected() != data.parallelRuns;
  }

  @Override
  public void apply() {
    ChatSettings.Data data = ChatSettings.state();
    boolean changed = !this.selectedId().equals(data.defaultAgent)
      || this.parallelRunsBox.isSelected() != data.parallelRuns;
    data.defaultAgent = this.selectedId();
    data.parallelRuns = this.parallelRunsBox.isSelected();
    if (changed) {
      ChatService.postSettingsToOpenProjects();
    }
  }

  @Override
  public void reset() {
    ChatSettings.Data data = ChatSettings.state();
    List<AgentRegistry.Choice> choices = AgentRegistry.choices(data.acpAgents);
    this.defaultAgentBox.setModel(new DefaultComboBoxModel<>(choices.toArray(AgentRegistry.Choice[]::new)));
    for (AgentRegistry.Choice choice : choices) {
      if (choice.id().equals(data.defaultAgent)) {
        this.defaultAgentBox.setSelectedItem(choice);
      }
    }
    this.parallelRunsBox.setSelected(data.parallelRuns);
  }

  private String selectedId() {
    return this.defaultAgentBox.getSelectedItem() instanceof AgentRegistry.Choice choice ? choice.id() : "";
  }
}
