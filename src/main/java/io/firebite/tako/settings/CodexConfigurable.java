package io.firebite.tako.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.agent.codex.CodexBackend;
import io.firebite.tako.chat.ChatService;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/** Settings | Tools | Tako | Codex. */
public final class CodexConfigurable implements Configurable {
  private TextFieldWithBrowseButton executableField;
  private JBTextField modelField;
  private ComboBox<String> modeBox;
  private ComboBox<String> effortBox;

  @Override
  public @Nls String getDisplayName() {
    return TakoBundle.message("configurable.codex");
  }

  @Override
  public @Nullable JComponent createComponent() {
    this.executableField = new TextFieldWithBrowseButton();
    this.executableField.addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile());
    this.modelField = new JBTextField();
    this.modelField.getEmptyText().setText(TakoBundle.message("settings.codex.model.empty"));
    List<String> modes = new ArrayList<>();
    for (AgentInfo.Mode mode : CodexBackend.modes()) {
      modes.add(mode.value());
    }
    this.modeBox = SettingsUi.choiceBox(modes, TakoBundle.message("settings.codex.mode.default"), CodexBackend.modes());
    this.effortBox = SettingsUi.choiceBox(CodexBackend.EFFORTS, TakoBundle.message("settings.codex.effort.default"),
      List.of());

    JPanel panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(TakoBundle.message("settings.executable"), this.executableField)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.codex.executable.hint")))
      .addSeparator()
      .addLabeledComponent(TakoBundle.message("settings.default.model"), this.modelField)
      .addLabeledComponent(TakoBundle.message("settings.default.mode"), this.modeBox)
      .addLabeledComponent(TakoBundle.message("settings.default.effort"), this.effortBox)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.defaults.hint")))
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
    this.reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    ChatSettings.Data data = ChatSettings.state();
    return !this.executableField.getText().trim().equals(data.codexPath)
      || !this.modelField.getText().trim().equals(data.codexModel)
      || !SettingsUi.selected(this.modeBox).equals(data.codexMode)
      || !SettingsUi.selected(this.effortBox).equals(data.codexEffort);
  }

  @Override
  public void apply() {
    ChatSettings.Data data = ChatSettings.state();
    data.codexPath = this.executableField.getText().trim();
    data.codexModel = this.modelField.getText().trim();
    data.codexMode = SettingsUi.selected(this.modeBox);
    data.codexEffort = SettingsUi.selected(this.effortBox);
    ChatService.retryFailedStartsInOpenProjects();
  }

  @Override
  public void reset() {
    ChatSettings.Data data = ChatSettings.state();
    this.executableField.setText(data.codexPath);
    this.modelField.setText(data.codexModel);
    this.modeBox.setSelectedItem(data.codexMode);
    this.effortBox.setSelectedItem(data.codexEffort);
  }
}
