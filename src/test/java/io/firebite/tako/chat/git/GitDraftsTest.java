package io.firebite.tako.chat.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class GitDraftsTest {
  @Test
  public void commitPromptAsksForTheMessageOnly() {
    String prompt = GitDrafts.prompt(true, false, "+added line", List.of("fix(ui): align chips"), List.of(), "");

    assertTrue(prompt.contains("<commit>"));
    assertFalse(prompt.contains("<title>"));
    assertTrue(prompt.contains("- fix(ui): align chips"));
    assertTrue(prompt.contains("+added line"));
    assertFalse(prompt.contains("conversation that led"));
  }

  @Test
  public void pullRequestPromptWithoutCommitDescribesTheBranch() {
    String prompt = GitDrafts.prompt(false, true, "", List.of(), List.of("feat one"), "Person: add login");

    assertFalse(prompt.contains("<commit>"));
    assertTrue(prompt.contains("<title>"));
    assertTrue(prompt.contains("<body>"));
    assertTrue(prompt.contains("- feat one"));
    assertTrue(prompt.contains("Person: add login"));
    assertFalse(prompt.contains("Changes:"));
  }

  @Test
  public void hugeInputIsCut() {
    String prompt = GitDrafts.prompt(true, true, "d".repeat(GitDrafts.MAX_DIFF * 3), List.of(), List.of(),
      "c".repeat(GitDrafts.MAX_CONVERSATION * 3) + "END");

    assertTrue(prompt.length() < GitDrafts.MAX_DIFF + GitDrafts.MAX_CONVERSATION + 3000);
    assertTrue("keeps the conversation's end", prompt.contains("cEND"));
  }

  @Test
  public void taggedAnswerIsSplit() {
    Draft draft = GitDrafts.parse("<commit>\nfix: grüße 🎉\n\nWhy it matters\n</commit>\n<title>\nFix greetings\n"
      + "</title>\n<body>\n## Summary\n- one\n</body>");

    assertEquals(new Draft("fix: grüße 🎉\n\nWhy it matters", "Fix greetings", "## Summary\n- one"), draft);
  }

  @Test
  public void fencesCrlfAndMissingClosingTagsAreTolerated() {
    Draft draft = GitDrafts.parse("```\r\n<commit>\r\nfix: a\r\n<title>\r\nTitle one\r\n<body>\r\n```md\r\nBody"
      + "\r\n```\r\n```");

    assertEquals(new Draft("fix: a", "Title one", "Body"), draft);
  }

  @Test
  public void untaggedAnswerIsTheCommitMessage() {
    assertEquals(new Draft("Add login\n\nDetails", "Add login", "Details"),
      GitDrafts.parse("  Add login\n\nDetails  "));
    assertEquals(new Draft("One line", "One line", ""), GitDrafts.parse("One line"));
  }

  @Test
  public void titleFallsBackToTheCommitSubject() {
    assertEquals(new Draft("feat: x\n\nbody", "feat: x", ""), GitDrafts.parse("<commit>feat: x\n\nbody</commit>"));
  }

  @Test
  public void multiLineTitleKeepsItsFirstLine() {
    assertEquals("One", GitDrafts.parse("<title>\nOne\nTwo\n</title>").title());
  }

  @Test
  public void emptyAnswerIsAnEmptyDraft() {
    assertEquals(Draft.EMPTY, GitDrafts.parse(""));
    assertEquals(Draft.EMPTY, GitDrafts.parse("   \n "));
  }

  @Test
  public void draftIsCompleteWhenTheNeededTextsAreWritten() {
    assertTrue(new Draft("msg", "", "").isComplete(true, false));
    assertFalse(new Draft("  ", "", "").isComplete(true, false));
    assertFalse(new Draft("msg", " ", "").isComplete(true, true));
    assertTrue(new Draft("", "Title", "").isComplete(false, true));
    assertTrue(Draft.EMPTY.isComplete(false, false));
  }
}
