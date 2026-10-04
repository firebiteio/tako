package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class FileIndexTest {

  @Test
  public void foldersAreDerivedFromFiles() {
    assertEquals(List.of("src/", "src/main/", "src/main/App.java", "README.md"),
      FileIndex.withFolders(List.of("src/main/App.java", "README.md")));
  }

  @Test
  public void nameMatchesRankAbovePathMatches() {
    List<String> entries = FileIndex.withFolders(List.of("src/main/App.java", "src/app/Main.java", "docs/apple.md",
      "lib/zapp.txt"));

    List<FileIndex.Match> matches = FileIndex.search(entries, "app", 10);

    assertEquals("src/app", matches.get(0).path());
    assertTrue(matches.get(0).isDirectory());
    assertEquals("docs/apple.md", matches.get(1).path());
    assertEquals("src/main/App.java", matches.get(2).path());
    assertEquals("lib/zapp.txt", matches.get(3).path());
  }

  @Test
  public void subsequenceMatchesLast() {
    List<FileIndex.Match> matches = FileIndex.search(List.of("src/main/ChatService.java", "notes.txt"), "chsvc", 10);

    assertEquals(1, matches.size());
    assertEquals("src/main/ChatService.java", matches.get(0).path());
  }

  @Test
  public void subsequenceIgnoresFolders() {
    List<String> entries = FileIndex.withFolders(List.of("subprojects/ui/renderer/build.gradle.kts",
      "subprojects/ui/api/src/main/java/io/firebite/Main.java", "subprojects/gpu/sample/src/Cube.java"));

    List<FileIndex.Match> matches = FileIndex.search(entries, "Cube", 10);

    assertEquals(List.of(new FileIndex.Match("subprojects/gpu/sample/src/Cube.java", false)), matches);
  }

  @Test
  public void walkSkipsBuildOutput() throws Exception {
    Path root = Files.createTempDirectory("project");
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/A.java"), "class A {}");
    Files.createDirectories(root.resolve("node_modules/x"));
    Files.writeString(root.resolve("node_modules/x/index.js"), "");

    List<String> files = FileIndex.walk(root);

    assertEquals(List.of("src/A.java"), files);
    assertFalse(files.contains("node_modules/x/index.js"));
  }
}
