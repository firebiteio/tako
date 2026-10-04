package io.firebite.tako.ide.editor;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IdeFilesTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void newestFilesListsNestedFilesNewestFirstUpToTheLimit() throws IOException {
    Path root = this.folder.getRoot().toPath();
    this.write(root.resolve("old.txt"), 1_000);
    this.write(root.resolve("notes/new.md"), 3_000);
    this.write(root.resolve("mid.txt"), 2_000);

    assertEquals(List.of("notes/new.md", "mid.txt", "old.txt"), IdeFiles.newestFiles(root, 10));
    assertEquals(List.of("notes/new.md"), IdeFiles.newestFiles(root, 1));
    assertEquals(List.of(), IdeFiles.newestFiles(root.resolve("missing"), 10));
  }

  private void write(Path file, long modified) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x");
    Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
  }
}
