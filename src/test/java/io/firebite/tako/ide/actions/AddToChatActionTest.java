package io.firebite.tako.ide.actions;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.ide.context.ContextItem;
import java.lang.reflect.Proxy;
import javax.swing.JPanel;

public class AddToChatActionTest extends BasePlatformTestCase {
  private final AddToChatAction action = new AddToChatAction();
  private Editor consoleEditor;
  private ConsoleView console;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    EditorFactory editors = EditorFactory.getInstance();
    this.consoleEditor = editors.createViewer(editors.createDocument("> Task :test\nBUILD FAILED"), this.getProject());
    this.console = (ConsoleView) Proxy.newProxyInstance(ConsoleView.class.getClassLoader(),
      new Class<?>[]{ConsoleView.class}, (proxy, method, args) -> null);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      EditorFactory.getInstance().releaseEditor(this.consoleEditor);
    } finally {
      super.tearDown();
    }
  }

  public void testTerminalSelectionIsLabeledTerminal() {
    AnActionEvent event = this.event(SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, this.getProject())
      .add(AddToChatAction.TERMINAL_VIEW, new Object())
      .add(PlatformDataKeys.PREDEFINED_TEXT, "BUILD FAILED")
      .build());

    assertTrue(event.getPresentation().isEnabledAndVisible());
    ContextItem item = AddToChatAction.outputSelection(event);
    assertEquals("Terminal", item.label());
    assertTrue(item.text().startsWith("Selected in the terminal:"));
  }

  public void testTerminalInAnEditorTabWithoutSelectionAttachesNoFile() {
    VirtualFile file = this.myFixture.configureByText("terminal.txt", "").getVirtualFile();
    AnActionEvent event = this.event(SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, this.getProject())
      .add(AddToChatAction.TERMINAL_VIEW, new Object())
      .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, new VirtualFile[]{file})
      .build());

    assertFalse(event.getPresentation().isEnabledAndVisible());
  }

  public void testConsoleSelectionIsLabeledByItsRunConfiguration() {
    this.consoleEditor.getSelectionModel().setSelection(13, 25);
    RunContentDescriptor run = new RunContentDescriptor(null, null, new JPanel(), "MyApp");
    try {
      AnActionEvent event = this.event(this.consoleContext().add(AddToChatAction.RUN_CONTENT_DESCRIPTOR, run).build());

      assertTrue(event.getPresentation().isEnabledAndVisible());
      ContextItem item = AddToChatAction.outputSelection(event);
      assertEquals("MyApp", item.label());
      assertEquals("Selected in the console of MyApp:\n```\nBUILD FAILED\n```", item.text());
    } finally {
      Disposer.dispose(run);
    }
  }

  public void testConsoleWithoutSelectionHidesTheAction() {
    AnActionEvent event = this.event(this.consoleContext().build());

    assertFalse(event.getPresentation().isEnabledAndVisible());
  }

  public void testFileEditorSelectionIsNotOutput() {
    this.myFixture.configureByText("A.java", "class <selection>A</selection> {}");
    AnActionEvent event = this.event(SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, this.getProject())
      .add(CommonDataKeys.EDITOR, this.myFixture.getEditor())
      .build());

    assertTrue(event.getPresentation().isEnabledAndVisible());
    assertNull(AddToChatAction.outputSelection(event));
  }

  private SimpleDataContext.Builder consoleContext() {
    return SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, this.getProject())
      .add(CommonDataKeys.EDITOR, this.consoleEditor)
      .add(AddToChatAction.CONSOLE_VIEW, this.console);
  }

  private AnActionEvent event(DataContext context) {
    AnActionEvent event = TestActionEvent.createTestEvent(this.action, context);
    this.action.update(event);
    return event;
  }
}
