package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PastedTextTest {
  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void textBecomesAFileFromTheThresholdInUtf8Bytes() {
    assertFalse(PastedText.isLarge("x".repeat(PastedText.FILE_THRESHOLD - 1)));
    assertTrue(PastedText.isLarge("x".repeat(PastedText.FILE_THRESHOLD)));
    assertFalse(PastedText.isLarge("é".repeat(PastedText.FILE_THRESHOLD / 2 - 1)));
    assertTrue(PastedText.isLarge("é".repeat(PastedText.FILE_THRESHOLD / 2)));
    assertFalse(PastedText.isLarge("日".repeat(PastedText.FILE_THRESHOLD / 3)));
    assertTrue(PastedText.isLarge("日".repeat(PastedText.FILE_THRESHOLD / 3 + 1)));
  }

  @Test
  public void writesOnePastTheHighestNumberAndCreatesTheFolder() throws Exception {
    Path directory = this.folder.getRoot().toPath().resolve("pasted");

    Path first = PastedText.write(directory, "first ä");
    Files.writeString(directory.resolve("pasted-text-5.txt"), "taken");
    Path next = PastedText.write(directory, "next");

    assertEquals(directory.resolve("pasted-text-1.txt"), first);
    assertEquals(directory.resolve("pasted-text-6.txt"), next);
    assertEquals("first ä", Files.readString(first, StandardCharsets.UTF_8));
    assertEquals("next", Files.readString(next, StandardCharsets.UTF_8));
  }

  @Test
  public void folderIsOpenOnlyToItsOwner() throws Exception {
    Path directory = this.folder.getRoot().toPath().resolve("pasted");
    assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));

    PastedText.write(directory, "secret");

    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
  }

  @Test
  public void findsOnlyPastedFilesInTheFolder() throws Exception {
    Path directory = this.folder.getRoot().toPath();
    Path pasted = PastedText.write(directory, "text");

    assertEquals(pasted, PastedText.fileOf(IdeContext.file(pasted), directory));
    assertNull(PastedText.fileOf(IdeContext.file(directory.resolve("notes.txt")), directory));
    assertNull(PastedText.fileOf(IdeContext.file(Path.of("elsewhere", "pasted-text-1.txt")), directory));
  }

  @Test
  public void deletesOnlyOldPastedFiles() throws Exception {
    Path directory = this.folder.getRoot().toPath();
    Path old = PastedText.write(directory, "old");
    Path recent = PastedText.write(directory, "recent");
    Path other = Files.writeString(directory.resolve("other.txt"), "other");
    Instant cutoff = Instant.now().minus(PastedText.MAX_AGE);
    Files.setLastModifiedTime(old, FileTime.from(cutoff.minusSeconds(60)));
    Files.setLastModifiedTime(other, FileTime.from(cutoff.minusSeconds(60)));

    PastedText.deleteOlderThan(directory, cutoff);

    assertFalse(Files.exists(old));
    assertTrue(Files.exists(recent));
    assertTrue(Files.exists(other));
  }
}
