package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SetupOutputTest {

  private static String of(String... chunks) {
    SetupOutput output = new SetupOutput();
    for (String chunk : chunks) {
      output.append(chunk);
    }
    output.finish();
    return output.text();
  }

  @Test
  public void plainTextStaysAsItIs() {
    assertEquals("", of());
    assertEquals("", of(""));
    assertEquals("a\nb ✓ 日本\n", of("a\nb ✓ 日本\n"));
  }

  @Test
  public void aLoneCarriageReturnRewritesTheLine() {
    assertEquals("done\n50%", of("done\n10%\r20%\r50%"));
    assertEquals("a\nb\n", of("a\r\nb\r\n"));
  }

  @Test
  public void aLineBreakSplitBetweenChunksStaysOneBreak() {
    assertEquals("a\nb", of("a\r", "\nb"));
    assertEquals("b", of("a\r", "b"));
    assertEquals("a", of("a\r"));
  }

  @Test
  public void colorsAndTitlesAreLeftOut() {
    assertEquals("red plain", of("\u001b[31mred\u001b[0m plain"));
    assertEquals("text", of("\u001b]0;title\u0007text"));
    assertEquals("text", of("\u001b]0;title\u001b\\text"));
    assertEquals("ab", of("a\u001b(Bb"));
  }

  @Test
  public void anEscapeSplitBetweenChunksIsStillLeftOut() {
    assertEquals("red", of("\u001b", "[3", "1m", "red"));
    assertEquals("x", of("\u001b]0;ti", "tle\u0007x"));
  }

  @Test
  public void anEscapeThatNeverEndsShowsAsText() {
    String rest = "[" + "1".repeat(100);

    assertEquals(rest + " more", of("\u001b" + rest, " more"));
  }

  @Test
  public void anUnfinishedEscapeAtTheEndIsDropped() {
    assertEquals("a", of("a\u001b[3"));
  }

  @Test
  public void onlyTheEndOfHugeOutputIsKept() {
    SetupOutput output = new SetupOutput();
    for (int i = 0; i < 10_000; i++) {
      output.append("line " + i + "\n");
    }

    String text = output.text();

    assertTrue(text.startsWith("…\nline "));
    assertTrue(text.endsWith("line 9999\n"));
    assertTrue(text.length() <= SetupOutput.LIMIT + 2);
  }

  @Test
  public void oneHugeLineIsCutToo() {
    String text = of("x".repeat(SetupOutput.LIMIT * 3));

    assertEquals("…\n" + "x".repeat(SetupOutput.LIMIT), text);
  }

  @Test
  public void aRewrittenLineAfterACutStaysRight() {
    SetupOutput output = new SetupOutput();
    output.append("y".repeat(SetupOutput.LIMIT + 10) + "\n");
    output.append("10%\r99%");

    assertTrue(output.text().endsWith("\n99%"));
  }
}
