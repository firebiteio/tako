package io.firebite.tako.chat;

import static org.junit.Assert.assertEquals;

import io.firebite.tako.chat.thread.ChatController.State;
import java.util.List;
import org.junit.Test;

public class ChildWakesTest {
  private final ChildWakes wakes = new ChildWakes();

  @Test
  public void aHandedPromptWakesTheParentOnceWhenTheChildIsIdle() {
    this.wakes.handed("parent", "child");

    assertEquals(List.of(), this.wakes.changed("child", State.WORKING));
    assertEquals(List.of("parent"), this.wakes.changed("child", State.IDLE));
    assertEquals("a turn the person starts later wakes nobody", List.of(),
      this.wakes.changed("child", State.WORKING));
    assertEquals(List.of(), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void severalPromptsHandedBeforeItIsDoneWakeOnce() {
    this.wakes.handed("parent", "child");
    this.wakes.handed("parent", "child");
    this.wakes.handed("other", "child");

    assertEquals(List.of("parent", "other"), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void withoutAHandedPromptNobodyWakes() {
    assertEquals(List.of(), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void waitingForThePersonWakesNobodyButStoppingDoes() {
    this.wakes.handed("parent", "child");

    assertEquals(List.of(), this.wakes.changed("child", State.WAITING));
    assertEquals(List.of("parent"), this.wakes.changed("child", State.STOPPED));
  }

  @Test
  public void aWaitThatReturnedTheResultTakesTheWake() {
    this.wakes.handed("parent", "child");
    this.wakes.waitStarted("parent", "child");
    this.wakes.waitEnded("parent", "child", true);

    assertEquals(List.of(), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void aWaitRunningWhenTheChildIsDoneTakesTheWake() {
    this.wakes.handed("parent", "child");
    this.wakes.handed("other", "child");
    this.wakes.waitStarted("parent", "child");

    assertEquals(List.of("other"), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void aWaitThatTimedOutKeepsTheWake() {
    this.wakes.handed("parent", "child");
    this.wakes.waitStarted("parent", "child");
    this.wakes.waitEnded("parent", "child", false);

    assertEquals(List.of("parent"), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void anotherThreadsWaitKeepsTheWake() {
    this.wakes.handed("parent", "child");
    this.wakes.waitStarted("other", "child");
    this.wakes.waitEnded("other", "child", true);

    assertEquals(List.of("parent"), this.wakes.changed("child", State.IDLE));
  }

  @Test
  public void aClosedChildWakesNobody() {
    this.wakes.handed("parent", "child");
    this.wakes.closed("child");

    assertEquals(List.of(), this.wakes.changed("child", State.IDLE));
  }
}
