package io.firebite.tako.chat.thread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.chat.store.ThreadPast;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HandoffTest {
  private static final Handoff.Facts FACTS = new Handoff.Facts("Claude Code", "/work/repo", "feature", null);

  @Test
  public void anEmptyThreadHandsOverOnlyWhereItStands() {
    String text = Handoff.text(input(List.of()), FACTS, Handoff.MAX_TOKENS);

    assertTrue(text.startsWith("<handoff>\nThis thread ran with Claude Code until now. You continue it in a new "
      + "session."));
    assertTrue(text.contains("\nFolder: /work/repo\nBranch: feature\n"));
    assertTrue(text.endsWith("</handoff>"));
    assertFalse(text.contains("Conversation so far"));
    assertFalse(text.contains("todo"));
    assertFalse(text.contains("Files changed"));
  }

  @Test
  public void aShortConversationGoesWholeAndInOrder() {
    String text = Handoff.text(input(List.of(turn("fix the build", "Fixed it."), turn("thanks", "Welcome."))),
      new Handoff.Facts("Codex", null, "main", "/wt"), Handoff.MAX_TOKENS);

    assertTrue(text.contains("Branch: main (worktree /wt)"));
    assertTrue(text.contains("Conversation so far, oldest first:\nPerson: fix the build\nAgent: Fixed it.\n\n"
      + "Person: thanks\nAgent: Welcome.\n</handoff>"));
    assertFalse(text.contains("left out"));
  }

  @Test
  public void aLongConversationStaysWithinTheBudgetAndKeepsTheFirstRequestAndTheNewestTurns() {
    List<ThreadPast.Turn> turns = new ArrayList<>();
    turns.add(turn("the original request " + "r".repeat(2000), "first answer"));
    for (int i = 1; i < 500; i++) {
      turns.add(turn("prompt " + i + " " + "p".repeat(300), "answer " + i + " " + "a".repeat(1500)));
    }

    String text = Handoff.text(input(turns), FACTS, 2000);

    assertTrue(Handoff.tokens(text) <= 2000);
    assertTrue(text.contains("Person: the original request"));
    assertTrue(text.contains("[…]"));
    assertTrue(text.contains("Person: prompt 499 "));
    assertTrue(text.contains("answer 499 "));
    assertTrue(text.contains(" turns left out]"));
    assertTrue(text.indexOf("the original request") < text.indexOf("left out]"));
    assertTrue(text.indexOf("left out]") < text.indexOf("prompt 499"));
  }

  @Test
  public void aNewestTurnOverTheBudgetIsShortenedToFit() {
    String text = Handoff.text(input(List.of(turn("big " + "x".repeat(100_000), "done " + "y".repeat(100_000)))),
      FACTS, 1000);

    assertTrue(Handoff.tokens(text) <= 1000);
    assertTrue(text.contains("Person: big x"));
    assertTrue(text.contains("Agent: done y"));
    assertTrue(text.contains("[…]"));
  }

  @Test
  public void aBudgetTooSmallForAnyTurnSaysTheyAreLeftOut() {
    String text = Handoff.text(input(List.of(turn("a".repeat(5000), ""), turn("b".repeat(5000), ""))), FACTS, 100);

    assertTrue(text.contains("[2 turns left out]"));
  }

  @Test
  public void unicodeIsCountedInBytesAndNeverSplit() {
    String emoji = "😀".repeat(20_000);
    String text = Handoff.text(input(List.of(turn("日本語のテキスト" + emoji, emoji))), FACTS, 3000);

    assertTrue(Handoff.tokens(text) <= 3000);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertTrue(Character.isLowSurrogate(text.charAt(i + 1)));
      } else if (Character.isLowSurrogate(c)) {
        assertTrue(Character.isHighSurrogate(text.charAt(i - 1)));
      }
    }
    assertEquals(4, Handoff.tokens("日本語のテ".substring(0, 4)));
  }

  @Test
  public void shortenKeepsHeadAndTail() {
    assertEquals("abc", Handoff.shorten("abc", 10));
    String shortened = Handoff.shorten("0123456789".repeat(10), 25);
    assertTrue(shortened.startsWith("0123456"));
    assertTrue(shortened.endsWith("3456789"));
    assertTrue(shortened.contains(" […] "));
  }

  @Test
  public void todosPlanAndFilesHaveTheirOwnSectionsWithinCaps() {
    List<String> files = new ArrayList<>();
    for (int i = 0; i < 130; i++) {
      files.add("src/File" + i + ".java");
    }
    Handoff.Input input = new Handoff.Input(List.of(turn("go", "ok")), files, "- [pending] " + "t".repeat(10_000),
      "plan " + "p".repeat(20_000));

    String text = Handoff.text(input, FACTS, Handoff.MAX_TOKENS);

    assertTrue(text.contains("\nLatest todo list:\n- [pending] t"));
    assertTrue(text.contains("\nLatest plan:\nplan p"));
    assertTrue(text.contains("- src/File129.java\n"));
    assertTrue(text.contains("- src/File30.java\n"));
    assertFalse(text.contains("- src/File29.java\n"));
    assertTrue(text.contains("- and 30 more\n"));
    assertTrue(text.length() < 4000 + 8000 + 130 * 30 + 2000);
    assertTrue(text.contains("Person: go\nAgent: ok"));
  }

  private static Handoff.Input input(List<ThreadPast.Turn> turns) {
    return new Handoff.Input(turns, List.of(), null, null);
  }

  private static ThreadPast.Turn turn(String prompt, String answer) {
    return new ThreadPast.Turn(prompt, answer);
  }
}
