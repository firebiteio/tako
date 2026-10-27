package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PastedTextTest {
  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void textBecomesAFileFromTheThreshold() {
    assertFalse(PastedText.isLarge("x".repeat(PastedText.FILE_THRESHOLD - 1)));
    assertTrue(PastedText.isLarge("x".repeat(PastedText.FILE_THRESHOLD)));
  }

  @Test
  public void writesTheNextFreeNumberAndCreatesTheFolder() throws Exception {
    Path directory = this.folder.getRoot().toPath().resolve("pasted");

    Path first = PastedText.write(directory, "first ä");
    Path second = PastedText.write(directory, "second");

    assertEquals(directory.resolve("pasted-text-1.txt"), first);
    assertEquals(directory.resolve("pasted-text-2.txt"), second);
    assertEquals("first ä", Files.readString(first, StandardCharsets.UTF_8));
    assertEquals("second", Files.readString(second, StandardCharsets.UTF_8));
  }
}
