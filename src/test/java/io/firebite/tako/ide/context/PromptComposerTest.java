package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class PromptComposerTest {

  @Test
  public void plainPromptStaysPlain() {
    assertEquals("hello", PromptComposer.compose("hello", List.of(), null));
  }

  @Test
  public void contextAndActiveFileFollowThePrompt() {
    ContextItem selection = new ContextItem("1", ContextItem.Kind.SELECTION, "A.java:1-2",
      "Selected in A.java (lines 1-2):\n```java\nx\n```");

    String prompt = PromptComposer.compose("explain", List.of(selection), "src/B.java");

    assertEquals("explain\n\n<ide_context>\nSelected in A.java (lines 1-2):\n```java\nx\n```\n\n"
      + "File open in the editor: src/B.java\n</ide_context>", prompt);
  }

  @Test
  public void slashCommandsGetNoContext() {
    assertEquals("/compact", PromptComposer.compose("/compact", List.of(), "src/B.java"));
  }

  @Test
  public void rebaseMovesPathsIntoTheWorktree() {
    String prompt = "File open in the editor: D:/code/app/src/B.java\nRoot: D:/code/app";

    assertEquals("File open in the editor: D:/code/app-tako/fix/1/src/B.java\nRoot: D:/code/app-tako/fix/1",
      PromptComposer.rebase(prompt, "D:/code/app", "D:/code/app-tako/fix/1"));
  }

  @Test
  public void rebaseLeavesFoldersThatOnlyShareThePrefix() {
    assertEquals("see D:/code/app-old/x", PromptComposer.rebase("see D:/code/app-old/x", "D:/code/app", "D:/w"));
  }
}
