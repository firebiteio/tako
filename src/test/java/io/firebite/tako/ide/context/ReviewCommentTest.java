package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ReviewCommentTest {
  private static final ReviewTarget TURN = ReviewTarget.turn("thread-1", "/repo/src/Foo.java");

  @Test
  public void commentOnNewLinesNamesFileLinesAndVersion() {
    List<ReviewComment.Line> lines = List.of(new ReviewComment.Line(0, 12, "int a = 1;"),
      new ReviewComment.Line(0, 13, "int b = 2;"));

    ContextItem item = ReviewComment.item(TURN, lines, "  use one declaration  ", null);

    assertNotNull(item);
    assertEquals(ContextItem.Kind.REVIEW, item.kind());
    assertEquals("Foo.java:12-13: use one declaration", item.label());
    assertEquals("Review comment on /repo/src/Foo.java, lines 12-13 (after your change):\n```java\nint a = 1;\n"
      + "int b = 2;\n```\nComment: use one declaration", item.text());
  }

  @Test
  public void oneLineSaysLine() {
    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(0, 7, "x();")), "why?", null);

    assertNotNull(item);
    assertEquals("Foo.java:7: why?", item.label());
    assertTrue(item.text().startsWith("Review comment on /repo/src/Foo.java, line 7 (after your change):\n"));
  }

  @Test
  public void commentOnTheOldSideNumbersTheOldVersion() {
    List<ReviewComment.Line> lines = List.of(new ReviewComment.Line(4, 0, "old();"),
      new ReviewComment.Line(5, 0, "older();"));

    ContextItem item = ReviewComment.item(TURN, lines, "keep these", null);

    assertNotNull(item);
    assertEquals("Foo.java:4-5: keep these", item.label());
    assertEquals("Review comment on /repo/src/Foo.java, lines 4-5 (before your change):\n```java\nold();\n"
      + "older();\n```\nComment: keep these", item.text());
  }

  @Test
  public void linesOfBothVersionsShowAsADiffNumberedInTheNewVersion() {
    List<ReviewComment.Line> lines = List.of(new ReviewComment.Line(9, 10, "same();"),
      new ReviewComment.Line(10, 0, "removed();"), new ReviewComment.Line(0, 11, "added();"));

    ContextItem item = ReviewComment.item(TURN, lines, "not like this", null);

    assertNotNull(item);
    assertEquals("Foo.java:10-11: not like this", item.label());
    assertEquals("Review comment on /repo/src/Foo.java, lines 10-11 (after your change), lines only in the old "
      + "version marked -:\n```diff\n same();\n-removed();\n+added();\n```\nComment: not like this", item.text());
  }

  @Test
  public void contextAndAddedLinesNeedNoDiff() {
    List<ReviewComment.Line> lines = List.of(new ReviewComment.Line(3, 3, "a"), new ReviewComment.Line(0, 4, "b"));

    ContextItem item = ReviewComment.item(TURN, lines, "ok", null);

    assertNotNull(item);
    assertTrue(item.text().contains("```java\na\nb\n```"));
  }

  @Test
  public void proposedEditNamesBothVersions() {
    ReviewTarget proposed = ReviewTarget.proposed("thread-1", "C:\\repo\\Bar.kt");

    ContextItem after = ReviewComment.item(proposed, List.of(new ReviewComment.Line(0, 2, "val x = 1")), "no", null);
    ContextItem before = ReviewComment.item(proposed, List.of(new ReviewComment.Line(2, 0, "val x = 0")), "no", null);

    assertNotNull(after);
    assertNotNull(before);
    assertTrue(after.text().startsWith("Review comment on C:/repo/Bar.kt, line 2 (your proposed edit, not applied "
      + "yet):\n```kt\n"));
    assertTrue(before.text().startsWith("Review comment on C:/repo/Bar.kt, line 2 (the current file, before your "
      + "proposed edit):\n"));
    assertEquals("Bar.kt:2: no", after.label());
  }

  @Test
  public void aFileCommentHasNoVersion() {
    ContextItem item = ReviewComment.item(ReviewTarget.file("/repo/Makefile"),
      List.of(new ReviewComment.Line(0, 1, "all:")), "add a clean target", null);

    assertNotNull(item);
    assertEquals("Review comment on /repo/Makefile, line 1:\n```\nall:\n```\nComment: add a clean target",
      item.text());
  }

  @Test
  public void blankCommentOrNoLinesGiveNothing() {
    List<ReviewComment.Line> lines = List.of(new ReviewComment.Line(0, 1, "a"));

    assertNull(ReviewComment.item(TURN, lines, "", null));
    assertNull(ReviewComment.item(TURN, lines, " \n\t ", null));
    assertNull(ReviewComment.item(TURN, List.of(), "comment", null));
  }

  @Test
  public void multiLineCommentStaysWholeInTheTextAndOnOneLineInTheLabel() {
    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(0, 1, "a")), "first\n\nsecond\tthird",
      null);

    assertNotNull(item);
    assertEquals("Foo.java:1: first second third", item.label());
    assertTrue(item.text().endsWith("\nComment: first\n\nsecond\tthird"));
  }

  @Test
  public void longCommentIsCutInTheLabelOnly() {
    String comment = "x".repeat(100);

    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(0, 1, "a")), comment, null);

    assertNotNull(item);
    assertEquals("Foo.java:1: " + "x".repeat(39) + "…", item.label());
    assertTrue(item.text().endsWith("Comment: " + comment));
  }

  @Test
  public void unicodeSurvivesAndTheLabelDoesNotSplitAnEmoji() {
    ReviewTarget target = ReviewTarget.turn("t", "/repo/ünï/日本.md");
    String comment = "a".repeat(38) + "😀😀";

    ContextItem item = ReviewComment.item(target, List.of(new ReviewComment.Line(0, 1, "→ ✓ 😀")), comment, null);

    assertNotNull(item);
    assertEquals("日本.md:1: " + "a".repeat(38) + "…", item.label());
    assertTrue(item.text().contains("/repo/ünï/日本.md"));
    assertTrue(item.text().contains("```md\n→ ✓ 😀\n```"));
  }

  @Test
  public void backticksInTheCodeGetALongerFence() {
    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(0, 1, "```")), "c", null);

    assertNotNull(item);
    assertTrue(item.text().contains("\n````java\n```\n````\n"));
  }

  @Test
  public void hugeCodeKeepsItsStart() {
    String line = "y".repeat(IdeContext.MAX_EXCERPT + 50);

    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(0, 1, line)), "too long", null);

    assertNotNull(item);
    assertTrue(item.text().startsWith("Review comment on /repo/src/Foo.java, line 1 (after your change) (first "
      + IdeContext.MAX_EXCERPT + " of " + line.length() + " characters):\n```java\n"
      + "y".repeat(IdeContext.MAX_EXCERPT) + "\n```\n"));
  }

  @Test
  public void emptyLineCanBeCommented() {
    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(1, 0, "")), "file was empty", null);

    assertNotNull(item);
    assertTrue(item.text().contains("```java\n\n```"));
  }

  @Test
  public void noteComesLast() {
    ContextItem item = ReviewComment.item(TURN, List.of(new ReviewComment.Line(0, 1, "a")), "c",
      ReviewComment.CHANGED_NOTE);

    assertNotNull(item);
    assertTrue(item.text().endsWith("Comment: c\n" + ReviewComment.CHANGED_NOTE));
  }

  @Test
  public void linesMapEditorLinesToBothVersions() {
    String[] editor = {"keep", "gone", "new\r", "neither"};
    int[] before = {0, 1, -1, -1};
    int[] after = {0, -1, 1, -1};

    List<ReviewComment.Line> lines = ReviewComment.lines(3, 0, line -> before[line], line -> after[line],
      line -> editor[line]);

    assertEquals(List.of(new ReviewComment.Line(1, 1, "keep"), new ReviewComment.Line(2, 0, "gone"),
      new ReviewComment.Line(0, 2, "new")), lines);
  }

  @Test
  public void linesOutsideBothVersionsGiveNone() {
    assertEquals(List.of(), ReviewComment.lines(0, 2, line -> -1, line -> -1, line -> "x"));
  }

  @Test
  public void noteComparesTheShownVersionWithTheDisk() {
    assertNull(ReviewComment.note("a\nb", true, "a\nb"));
    assertNull(ReviewComment.note("a\nb", true, "a\r\nb"));
    assertNull(ReviewComment.note("a\nb", true, "a\rb"));
    assertEquals(ReviewComment.CHANGED_NOTE, ReviewComment.note("a\nb", true, "a\nc"));
    assertEquals(ReviewComment.CHANGED_NOTE, ReviewComment.note("", true, "new"));
  }

  @Test
  public void noteSaysWhenTheFileIsGone() {
    assertEquals(ReviewComment.GONE_NOTE, ReviewComment.note("a", false, null));
    assertNull(ReviewComment.note("", false, null));
  }

  @Test
  public void unreadableFileGetsNoNote() {
    assertNull(ReviewComment.note("a", true, null));
  }
}
