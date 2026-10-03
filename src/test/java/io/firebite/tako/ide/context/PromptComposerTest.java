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
}
