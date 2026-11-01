package io.firebite.tako.chat.git;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.firebite.tako.TakoBundle;
import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import org.jetbrains.annotations.Nullable;

/** Asks for a git action's commit message and pull request texts, which an agent may fill in meanwhile. */
final class GitActionDialog extends DialogWrapper {
  private static final int MESSAGE_ROWS = 6;
  private static final int BODY_ROWS = 10;
  private static final int COLUMNS = 72;

  private final boolean commits;
  private final boolean pullRequest;
  private final String summary;
  private final JBTextArea message = new JBTextArea(MESSAGE_ROWS, COLUMNS);
  private final JBTextField title = new JBTextField();
  private final JBTextArea body = new JBTextArea(BODY_ROWS, COLUMNS);
  private final JBLabel note = new JBLabel();

  /**
   * @param label   the action's name, for the title and the OK button
   * @param summary what the action works on, shown on top
   */
  GitActionDialog(Project project, ThreadGit.Option option, String label, String summary) {
    super(project, true);
    this.commits = option.commits();
    this.pullRequest = option.action().createsPullRequest();
    this.summary = summary;
    this.setTitle(label);
    this.setOKButtonText(label);
    for (JTextComponent field : new JTextComponent[]{this.message, this.title, this.body}) {
      field.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(DocumentEvent event) {
          GitActionDialog.this.updateOk();
        }
      });
    }
    this.message.setLineWrap(true);
    this.message.setWrapStyleWord(true);
    this.body.setLineWrap(true);
    this.body.setWrapStyleWord(true);
    this.note.setForeground(UIUtil.getContextHelpForeground());
    this.init();
    this.updateOk();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    FormBuilder form = FormBuilder.createFormBuilder().addComponent(new JBLabel(this.summary));
    if (this.commits) {
      form.addLabeledComponent(TakoBundle.message("dialog.git.commit.message"), new JBScrollPane(this.message), true);
    }
    if (this.pullRequest) {
      form.addLabeledComponent(TakoBundle.message("dialog.git.pull.request.title"), this.title, true);
      form.addLabeledComponent(TakoBundle.message("dialog.git.pull.request.body"), new JBScrollPane(this.body), true);
    }
    JComponent panel = form.addComponent(this.note).getPanel();
    panel.setBorder(JBUI.Borders.empty(4, 0));
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return this.commits ? this.message : this.title;
  }

  void showNote(String text) {
    this.note.setText(text);
  }

  /** Puts the agent's texts into the fields the person left empty. */
  void fill(Draft draft) {
    fillIfEmpty(this.message, draft.commitMessage());
    fillIfEmpty(this.title, draft.title());
    fillIfEmpty(this.body, draft.body());
    this.note.setText("");
  }

  Draft draft() {
    return new Draft(this.message.getText(), this.title.getText(), this.body.getText());
  }

  private void updateOk() {
    this.setOKActionEnabled(this.draft().isComplete(this.commits, this.pullRequest));
  }

  private static void fillIfEmpty(JTextComponent field, String text) {
    if (field.getText().isBlank() && !text.isBlank()) {
      field.setText(text);
      field.setCaretPosition(0);
    }
  }
}
