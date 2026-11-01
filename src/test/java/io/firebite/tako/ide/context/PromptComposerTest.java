package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class PromptComposerTest {

  @Test
  public void plainPromptStaysPlain() {
    assertEquals("hello", PromptComposer.compose("hello", List.of()));
  }

  @Test
  public void contextFollowsThePrompt() {
    String prompt = PromptComposer.compose("explain", List.of(selection()));

    assertEquals("explain\n\n<ide_context>\nSelected in A.java (lines 1-2):\n```java\nx\n```\n\n</ide_context>",
      prompt);
  }

  @Test
  public void threadIsReferencedByTitleAndId() {
    String prompt = PromptComposer.compose("continue", List.of(ContextItem.thread("t-1", "Login bug")));

    assertEquals("continue\n\n<ide_context>\nReferenced Tako thread \"Login bug\" (id t-1). Read its conversation "
      + "with Tako's get_thread tool, threadId \"t-1\" and conversation true.\n\n</ide_context>", prompt);
  }

  @Test
  public void threadTitleQuotesAreEscaped() {
    assertTrue(ContextItem.thread("t-1", "Say \"hi\"").text().startsWith("Referenced Tako thread \"Say \\\"hi\\\"\""));
  }

  @Test
  public void slashCommandsGetNoContext() {
    assertEquals("/compact", PromptComposer.compose("/compact", List.of(selection())));
  }

  private static ContextItem selection() {
    return new ContextItem("1", ContextItem.Kind.SELECTION, "A.java:1-2",
      "Selected in A.java (lines 1-2):\n```java\nx\n```");
  }

  @Test
  public void rebaseMovesPathsIntoTheWorktree() {
    String prompt = "File open in the editor: D:/code/app/src/B.java\nRoot: D:/code/app";

    assertEquals("File open in the editor: D:/code/app-tako/fix/1/src/B.java\nRoot: D:/code/app-tako/fix/1",
      PromptComposer.rebase(prompt, "D:/code/app", "D:/code/app-tako/fix/1"));
  }

  @Test
  public void handoffGoesFirstIntoTheContextAndTranscriptsShowOnlyTheTypedPrompt() {
    String prompt = PromptComposer.withHandoff(PromptComposer.compose("explain", List.of(selection())),
      "<handoff>\nbefore\n</handoff>");

    assertEquals(
      "explain\n\n<ide_context>\n<handoff>\nbefore\n</handoff>\nSelected in A.java (lines 1-2):\n```java\nx\n"
        + "```\n\n</ide_context>",
      prompt);
    assertEquals("explain", PromptComposer.typedPart(prompt));
    assertTrue(PromptComposer.hasContext(prompt));
  }

  @Test
  public void handoffAddsAContextBlockToAPlainOrEmptyPrompt() {
    String plain = PromptComposer.withHandoff("go on 日本", "<handoff>h</handoff>");
    String empty = PromptComposer.withHandoff("", "<handoff>h</handoff>");

    assertEquals("go on 日本\n\n<ide_context>\n<handoff>h</handoff>\n</ide_context>", plain);
    assertEquals("go on 日本", PromptComposer.typedPart(plain));
    assertEquals("", PromptComposer.typedPart(empty));
  }

  @Test
  public void rebaseLeavesFoldersThatOnlyShareThePrefix() {
    assertEquals("see D:/code/app-old/x", PromptComposer.rebase("see D:/code/app-old/x", "D:/code/app", "D:/w"));
  }
}
