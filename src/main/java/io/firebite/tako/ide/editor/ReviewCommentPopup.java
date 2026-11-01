package io.firebite.tako.ide.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import io.firebite.tako.TakoBundle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;

/** A box at the editor's caret to type a review comment in. */
public final class ReviewCommentPopup {
  private static final String ADD = "tako.review.add";

  private ReviewCommentPopup() {
  }

  /**
   * Enter hands the typed comment to {@code onAdd} and closes the box, Shift+Enter starts a new line, Esc closes it.
   * Clicks elsewhere leave it open, so a typed comment is not lost by accident. Must run on the EDT.
   */
  public static void show(Editor editor, String title, Consumer<String> onAdd) {
    JBTextArea area = new JBTextArea(3, 50);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.getEmptyText().setText(TakoBundle.message("review.popup.empty"));
    JBScrollPane scroll = new JBScrollPane(area);
    scroll.setBorder(JBUI.Borders.empty(4));
    JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, area)
      .setTitle(title)
      .setAdText(TakoBundle.message("review.popup.hint"))
      .setResizable(true)
      .setMovable(true)
      .setRequestFocus(true)
      .setFocusable(true)
      .setCancelOnClickOutside(false)
      .setCancelOnWindowDeactivation(false)
      .setCancelKeyEnabled(true)
      .createPopup();
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ADD);
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
      DefaultEditorKit.insertBreakAction);
    area.getActionMap().put(ADD, new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        String comment = area.getText();
        if (comment.isBlank()) {
          return;
        }
        popup.closeOk(null);
        onAdd.accept(comment);
      }
    });
    EditorUtil.disposeWithEditor(editor, popup);
    popup.showInBestPositionFor(editor);
  }
}
