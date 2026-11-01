package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReviewTargetTest {

  @Test
  public void turnComparesTheNewVersionWithTheDisk() {
    ReviewTarget target = ReviewTarget.turn("thread-1", "/repo/A.java");

    assertEquals("thread-1", target.threadId());
    assertFalse(target.before().isOnDisk());
    assertTrue(target.after().isOnDisk());
  }

  @Test
  public void runNamesItsStartCommitAndComparesTheNewVersionWithTheDisk() {
    ReviewTarget target = ReviewTarget.run("run-thread", "/repo-tako/run-1/A.java");

    assertEquals("the commit the parallel run started from", target.before().description());
    assertFalse(target.before().isOnDisk());
    assertTrue(target.after().isOnDisk());
  }

  @Test
  public void proposedEditComparesTheOldVersionWithTheDisk() {
    ReviewTarget target = ReviewTarget.proposed("thread-1", "/repo/A.java");

    assertTrue(target.before().isOnDisk());
    assertFalse(target.after().isOnDisk());
  }

  @Test
  public void fileHasNoThreadVersionOrDiskCheck() {
    ReviewTarget target = ReviewTarget.file("/repo/A.java");

    assertNull(target.threadId());
    assertNull(target.before().description());
    assertNull(target.after().description());
    assertFalse(target.after().isOnDisk());
  }

  @Test
  public void windowsPathsUseForwardSlashes() {
    assertEquals("C:/work/repo-tako/run-1/src/A.java",
      ReviewTarget.turn("t", "C:\\work\\repo-tako\\run-1\\src\\A.java").path());
  }
}
