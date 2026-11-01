package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlansTest {
  @Test
  public void titleIsTheFirstHeadingOfAnyLevel() {
    assertEquals("Add dark mode", Plans.title("Some intro\n\n### Add dark mode\n# Later"));
    assertEquals("Add dark mode", Plans.title("# Add dark mode ##\n"));
    assertEquals("Fix login", Plans.title("   ## Fix login\r\nsteps"));
  }

  @Test
  public void titleDropsAPlanPrefix() {
    assertEquals("Add dark mode", Plans.title("# Plan: Add dark mode"));
    assertEquals("Add dark mode", Plans.title("# PLAN - Add dark mode"));
    assertEquals("Planning the release", Plans.title("# Planning the release"));
    assertEquals("Plan-based caching", Plans.title("# Plan-based caching"));
    assertEquals("Add dark mode", Plans.title("# Plan — Add dark mode"));
  }

  @Test
  public void titleSkipsAnEmptyPlanHeading() {
    assertEquals("Add dark mode", Plans.title("# Plan:\n\n## Add dark mode"));
  }

  @Test
  public void titleIgnoresCommentsInCodeBlocks() {
    assertEquals("Add dark mode", Plans.title("Run:\n```bash\n# install deps\nnpm i\n```\n## Add dark mode"));
    assertEquals("Add dark mode", Plans.title("~~~~\n# a\n```\n# b\n~~~~\n# Add dark mode"));
    assertNull(Plans.title("```\n# never closed"));
  }

  @Test
  public void titleIsNullWithoutAHeading() {
    assertNull(Plans.title(""));
    assertNull(Plans.title("   \n\n"));
    assertNull(Plans.title("Just steps\n1. do it"));
    assertNull(Plans.title("#hashtag is no heading"));
    assertNull(Plans.title("    # indented code"));
    assertNull(Plans.title("# Plan:"));
    assertNull(Plans.title("#    "));
  }

  @Test
  public void titleKeepsUnicode() {
    assertEquals("Dunkelmodus für 日本 🎨", Plans.title("# Dunkelmodus für 日本 🎨"));
  }

  @Test
  public void titleOfAHugePlanIsFound() {
    String plan = "# Big\n" + "line\n".repeat(200_000);

    assertEquals("Big", Plans.title(plan));
  }

  @Test
  public void fileNameIsTheTitleSlug() {
    assertEquals("add-dark-mode-to-settings.md", Plans.fileName("# Plan: Add *dark* mode to `Settings`!"));
  }

  @Test
  public void fileNameWithoutAUsableTitleIsPlan() {
    assertEquals("plan.md", Plans.fileName(""));
    assertEquals("plan.md", Plans.fileName("no heading"));
    assertEquals("plan.md", Plans.fileName("# 日本語のプラン"));
    assertEquals("plan.md", Plans.fileName("# <>:\"/\\|?*"));
    assertEquals("plan.md", Plans.fileName("# ..."));
  }

  @Test
  public void fileNameAvoidsWindowsDeviceNames() {
    assertEquals("plan-con.md", Plans.fileName("# CON"));
    assertEquals("plan-nul.md", Plans.fileName("# nul"));
    assertEquals("plan-com1.md", Plans.fileName("# COM1"));
    assertEquals("console.md", Plans.fileName("# Console"));
  }

  @Test
  public void fileNameHasNoCharactersInvalidOnWindows() {
    String name = Plans.fileName("# a<b>c:d\"e/f\\g|h?i*j. ");

    assertEquals("a-b-c-d-e-f-g-h-i-j.md", name);
  }

  @Test
  public void fileNameIsCutWithoutATrailingDash() {
    String name = Plans.fileName("# " + "word ".repeat(40));

    assertTrue(name, name.length() <= 63);
    assertTrue(name, name.endsWith("word.md"));
  }

  @Test
  public void fileTextHasUnixLineEndsAndOneFinalNewline() {
    assertEquals("# A\n\nstep\n", Plans.fileText("# A\r\n\r\nstep\r\n\r\n  \n"));
    assertEquals("a\nb\n", Plans.fileText("a\rb"));
    assertEquals("\n", Plans.fileText(""));
    assertEquals("  indented\n", Plans.fileText("  indented"));
  }

  @Test
  public void promptHoldsTheTrimmedPlan() {
    assertEquals("Implement this plan:\n\n# A\nstep", Plans.implementPrompt("\n\n# A\nstep\n\n"));
  }

  @Test
  public void claudeNeverStartsInPlanMode() {
    assertEquals("default", Plans.implementMode("claude", "plan", ""));
    assertEquals("default", Plans.implementMode("claude", "", "plan"));
    assertEquals("default", Plans.implementMode("claude", "", ""));
    assertEquals("acceptEdits", Plans.implementMode("claude", "acceptEdits", "plan"));
    assertEquals("", Plans.implementMode("claude", "", "acceptEdits"));
    assertEquals("bypassPermissions", Plans.implementMode("claude", "bypassPermissions", ""));
  }

  @Test
  public void otherAgentsKeepTheirModeButPlan() {
    assertEquals("full-access", Plans.implementMode("codex", "full-access", ""));
    assertEquals("", Plans.implementMode("codex", "", ""));
    assertEquals("", Plans.implementMode("codex", "plan", ""));
  }
}
