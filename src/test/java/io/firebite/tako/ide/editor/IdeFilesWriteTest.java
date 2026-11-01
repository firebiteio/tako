package io.firebite.tako.ide.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class IdeFilesWriteTest extends BasePlatformTestCase {
  public void testWritesANewFileAsUtf8() throws Exception {
    Path path = Files.createTempDirectory("tako-write").resolve("plan.md");

    VirtualFile file = IdeFiles.writeText(path, "# Dunkelmodus für 日本 🎨\n");

    assertEquals(path.toRealPath(), file.toNioPath().toRealPath());
    assertEquals("# Dunkelmodus für 日本 🎨\n", Files.readString(path, StandardCharsets.UTF_8));
  }

  public void testReplacesAFileWrittenOutsideTheIde() throws Exception {
    Path path = Files.createTempDirectory("tako-write").resolve("plan.md");
    IdeFiles.writeText(path, "first, a much longer text\n");
    Files.writeString(path, "changed outside\n");

    VirtualFile file = IdeFiles.writeText(path, "second\n");

    assertEquals("second\n", Files.readString(path));
    assertEquals("second\n", new String(file.contentsToByteArray(), StandardCharsets.UTF_8));
  }

  public void testReplacesAFileOpenWithUnsavedEdits() throws Exception {
    Path path = Files.createTempDirectory("tako-write").resolve("plan.md");
    VirtualFile file = IdeFiles.writeText(path, "first\n");
    Document document = FileDocumentManager.getInstance().getDocument(file);
    WriteCommandAction.runWriteCommandAction(this.getProject(), () -> document.setText("unsaved edit\n"));

    IdeFiles.writeText(path, "the plan\n");

    assertEquals("the plan\n", Files.readString(path));
    assertEquals("the plan\n", document.getText());
    assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
  }

  public void testCreatesAMissingFolder() throws Exception {
    Path path = Files.createTempDirectory("tako-write").resolve("docs").resolve("plans").resolve("plan.md");

    IdeFiles.writeText(path, "x\n");

    assertEquals("x\n", Files.readString(path));
  }

  public void testRefusesToWriteOverAFolder() throws Exception {
    Path folder = Files.createTempDirectory("tako-write").resolve("plan.md");
    Files.createDirectories(folder);

    assertThrows(IOException.class, () -> IdeFiles.writeText(folder, "x\n"));
    assertTrue(Files.isDirectory(folder));
  }
}
