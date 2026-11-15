package io.firebite.tako.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.FormBuilder;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.claude.ClaudeBackend;
import io.firebite.tako.agent.claude.ClaudeLaunch;
import io.firebite.tako.chat.ChatService;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/** Settings | Tools | Tako | Claude Code. */
public final class ClaudeCodeConfigurable implements Configurable {
  private TextFieldWithBrowseButton executableField;
  private ComboBox<AuthMode> authModeBox;
  private JBPasswordField apiKeyField;
  private JBTextField modelField;
  private ComboBox<String> modeBox;
  private ComboBox<String> effortBox;
  private @Nullable String loadedApiKey;

  @Override
  public @Nls String getDisplayName() {
    return TakoBundle.message("configurable.claude");
  }

  @Override
  public @Nullable JComponent createComponent() {
    this.executableField = new TextFieldWithBrowseButton();
    this.executableField.addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile());
    this.authModeBox = new ComboBox<>(AuthMode.values());
    this.authModeBox.setRenderer(BuilderKt.textListCellRenderer(mode -> mode == null ? "" : mode.label()));
    this.apiKeyField = new JBPasswordField();
    this.modelField = new JBTextField();
    this.modelField.getEmptyText().setText(TakoBundle.message("settings.claude.model.empty"));
    String cliDefault = TakoBundle.message("settings.claude.cli.default");
    this.modeBox = SettingsUi.choiceBox(ClaudeLaunch.PERMISSION_MODES, cliDefault, ClaudeBackend.modes());
    this.effortBox = SettingsUi.choiceBox(ClaudeLaunch.EFFORT_LEVELS, cliDefault, List.of());

    JPanel panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(TakoBundle.message("settings.executable"), this.executableField)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.claude.executable.hint")))
      .addLabeledComponent(TakoBundle.message("settings.claude.authentication"), this.authModeBox)
      .addLabeledComponent(TakoBundle.message("settings.claude.api.key"), this.apiKeyField)
      .addComponentToRightColumn(new JBLabel(TakoBundle.message("settings.claude.api.key.hint")))
      .addSeparator()
      .addLabeledComponent(TakoBundle.message("settings.default.model"), this.modelField)
      .addLabeledComponent(TakoBundle.message("settings.default.permission.mode"), this.modeBox)
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
    return !this.executableField.getText().trim().equals(data.claudePath)
      || this.authModeBox.getSelectedItem() != data.authMode
      || !this.modelField.getText().trim().equals(data.claudeModel)
      || !SettingsUi.selected(this.modeBox).equals(data.claudeMode)
      || !SettingsUi.selected(this.effortBox).equals(data.claudeEffort)
      || (this.loadedApiKey != null && !this.currentApiKey().equals(this.loadedApiKey));
  }

  @Override
  public void apply() {
    ChatSettings.Data data = ChatSettings.state();
    data.claudePath = this.executableField.getText().trim();
    data.authMode = (AuthMode) this.authModeBox.getSelectedItem();
    data.claudeModel = this.modelField.getText().trim();
    data.claudeMode = SettingsUi.selected(this.modeBox);
    data.claudeEffort = SettingsUi.selected(this.effortBox);
    String key = this.currentApiKey();
    if (this.loadedApiKey != null && !key.equals(this.loadedApiKey)) {
      this.loadedApiKey = key;
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        ApiKeyStore.save(key);
        ApplicationManager.getApplication().invokeLater(ChatService::retryFailedStartsInOpenProjects);
      });
    } else {
      ChatService.retryFailedStartsInOpenProjects();
    }
  }

  @Override
  public void reset() {
    ChatSettings.Data data = ChatSettings.state();
    this.executableField.setText(data.claudePath);
    this.authModeBox.setSelectedItem(data.authMode);
    this.modelField.setText(data.claudeModel);
    this.modeBox.setSelectedItem(data.claudeMode);
    this.effortBox.setSelectedItem(data.claudeEffort);
    this.loadedApiKey = null;
    this.apiKeyField.setEnabled(false);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String key = ApiKeyStore.load();
      ApplicationManager.getApplication().invokeLater(() -> {
        this.loadedApiKey = key == null ? "" : key;
        this.apiKeyField.setText(this.loadedApiKey);
        this.apiKeyField.setEnabled(true);
      }, ModalityState.any());
    });
  }

  private String currentApiKey() {
    char[] chars = this.apiKeyField.getPassword();
    String key = new String(chars).trim();
    Arrays.fill(chars, '\0');
    return key;
  }
}
