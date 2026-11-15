package io.firebite.tako.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.ChatService;
import io.firebite.tako.chat.Orchestration;
import java.awt.FlowLayout;
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
  private JBCheckBox steerBox;
  private JBCheckBox settlePullRequestBox;
  private JBCheckBox settleIdleBox;
  private JBIntSpinner settleDaysSpinner;
  private JBTextArea delegationNotesArea;
  private JBTextArea orchestrationArea;

  @Override
  public @Nls String getDisplayName() {
    return TakoBundle.message("configurable.tako");
  }

  @Override
  public @Nullable JComponent createComponent() {
    this.defaultAgentBox = new ComboBox<>();
    this.defaultAgentBox.setRenderer(BuilderKt.textListCellRenderer(choice -> choice == null ? "" : choice.name()));
    this.parallelRunsBox = new JBCheckBox(TakoBundle.message("settings.tako.parallel.runs"));
    this.steerBox = new JBCheckBox(TakoBundle.message("settings.tako.steer"));
    this.settlePullRequestBox = new JBCheckBox(TakoBundle.message("settings.tako.settle.pull.request"));
    this.settleIdleBox = new JBCheckBox(TakoBundle.message("settings.tako.settle.idle"));
    this.settleDaysSpinner = new JBIntSpinner(ChatSettings.DEFAULT_IDLE_DAYS, ChatSettings.MIN_IDLE_DAYS,
      ChatSettings.MAX_IDLE_DAYS);
    this.settleIdleBox.addActionListener(event -> this.settleDaysSpinner.setEnabled(this.settleIdleBox.isSelected()));
    JPanel idleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    idleRow.add(this.settleIdleBox);
    idleRow.add(this.settleDaysSpinner);
    idleRow.add(new JBLabel(TakoBundle.message("settings.tako.settle.idle.days")));
    this.delegationNotesArea = new JBTextArea(5, 0);
    this.delegationNotesArea.setLineWrap(true);
    this.delegationNotesArea.setWrapStyleWord(true);
    this.orchestrationArea = new JBTextArea(10, 0);
    this.orchestrationArea.setLineWrap(true);
    this.orchestrationArea.setWrapStyleWord(true);
    JPanel panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(TakoBundle.message("settings.tako.default.agent"), this.defaultAgentBox)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.tako.default.agent.hint")))
      .addComponent(this.parallelRunsBox)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.tako.parallel.runs.hint")))
      .addComponent(this.steerBox)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.tako.steer.hint")))
      .addComponent(new TitledSeparator(TakoBundle.message("settings.tako.settle")))
      .addComponent(this.settlePullRequestBox)
      .addComponent(idleRow)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.tako.settle.hint")))
      .addComponent(new TitledSeparator(TakoBundle.message("settings.tako.delegation")))
      .addLabeledComponent(TakoBundle.message("settings.tako.delegation.notes"),
        new JBScrollPane(this.delegationNotesArea), true)
      .addComponent(new JBLabel(TakoBundle.message("settings.tako.delegation.notes.hint")))
      .addLabeledComponent(TakoBundle.message("settings.tako.orchestration"),
        new JBScrollPane(this.orchestrationArea), true)
      .addComponent(new JBLabel(TakoBundle.message("settings.tako.orchestration.hint")))
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
    this.reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    ChatSettings.Data data = ChatSettings.state();
    return !this.selectedId().equals(data.defaultAgent) || this.parallelRunsBox.isSelected() != data.parallelRuns
      || this.steerBox.isSelected() != data.steerWhileWorking
      || this.settlePullRequestBox.isSelected() != data.settleOnPullRequest
      || this.settleIdleBox.isSelected() != data.settleIdle
      || this.settleDaysSpinner.getNumber() != data.idleDays()
      || !this.delegationNotesArea.getText().equals(data.delegationNotes)
      || !this.orchestrationText().equals(data.orchestrationInstructions);
  }

  @Override
  public void apply() {
    boolean changed = this.isModified();
    ChatSettings.Data data = ChatSettings.state();
    data.defaultAgent = this.selectedId();
    data.parallelRuns = this.parallelRunsBox.isSelected();
    data.steerWhileWorking = this.steerBox.isSelected();
    data.settleOnPullRequest = this.settlePullRequestBox.isSelected();
    data.settleIdle = this.settleIdleBox.isSelected();
    data.settleIdleDays = this.settleDaysSpinner.getNumber();
    data.delegationNotes = this.delegationNotesArea.getText();
    data.orchestrationInstructions = this.orchestrationText();
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
    this.steerBox.setSelected(data.steerWhileWorking);
    this.settlePullRequestBox.setSelected(data.settleOnPullRequest);
    this.settleIdleBox.setSelected(data.settleIdle);
    this.settleDaysSpinner.setNumber(data.idleDays());
    this.settleDaysSpinner.setEnabled(data.settleIdle);
    this.delegationNotesArea.setText(data.delegationNotes);
    this.orchestrationArea.setText(Orchestration.instructions());
  }

  private String selectedId() {
    return this.defaultAgentBox.getSelectedItem() instanceof AgentRegistry.Choice choice ? choice.id() : "";
  }

  /** The area's text to store: blank when it holds the defaults, so later default changes still reach it. */
  private String orchestrationText() {
    String text = this.orchestrationArea.getText();
    return text.isBlank() || text.equals(Orchestration.DEFAULT_INSTRUCTIONS) ? "" : text;
  }
}
