package io.firebite.tako.chat.thread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.firebite.tako.chat.thread.QueuedPrompts.Action;
import io.firebite.tako.chat.thread.QueuedPrompts.Delivery;
import io.firebite.tako.chat.thread.QueuedPrompts.SendNow;
import io.firebite.tako.chat.thread.QueuedPrompts.Status;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class QueuedPromptsTest {
  private final List<ChatMessage.Queue> snapshots = new ArrayList<>();
  private final QueuedPrompts queue = new QueuedPrompts(this.snapshots::add);

  private static ChatMessage.Submit prompt(String text) {
    return new ChatMessage.Submit(text, List.of(), List.of());
  }

  /** A turn runs for prompt 1, which the agent took. */
  private void runTurn() {
    assertEquals(Action.SEND, this.queue.offer(1, prompt("first"), Delivery.QUEUE, true));
    this.queue.dispatch(1, false);
    this.queue.taken(1, true);
  }

  private ChatMessage.Queue last() {
    return this.snapshots.getLast();
  }

  private List<Integer> turns() {
    List<Integer> turns = new ArrayList<>();
    for (ChatMessage.QueueItem item : this.last().items()) {
      turns.add(item.turn());
    }
    return turns;
  }

  private Status status(int turn) {
    for (ChatMessage.QueueItem item : this.last().items()) {
      if (item.turn() == turn) {
        return item.status();
      }
    }
    return null;
  }

  @Test
  public void promptWithNothingRunningIsSentAndNotShown() {
    assertEquals(Action.SEND, this.queue.offer(1, prompt("hi"), Delivery.QUEUE, true));
    assertTrue(this.queue.isRunning());
    assertTrue(this.last().running());
    assertTrue(this.last().items().isEmpty());
    assertTrue(this.queue.dispatch(1, false));
    assertFalse("handed once", this.queue.dispatch(1, false));
  }

  @Test
  public void promptsDuringATurnWaitInOrderAndGoOneAfterTheOther() {
    this.runTurn();
    assertEquals(Action.HOLD, this.queue.offer(2, prompt("second"), Delivery.QUEUE, true));
    assertEquals(Action.HOLD, this.queue.offer(3, prompt("third"), Delivery.QUEUE, true));
    assertEquals(List.of(2, 3), this.turns());

    QueuedPrompts.Entry next = this.queue.turnEnded(false);
    assertNotNull(next);
    assertEquals(2, next.turn());
    assertEquals(Status.SENDING, this.status(2));
    assertTrue(this.last().running());
    assertNull("one at a time", this.queue.turnEnded(false));

    this.queue.taken(2, true);
    assertEquals(3, this.queue.turnEnded(false).turn());
    this.queue.taken(3, true);
    assertNull(this.queue.turnEnded(false));
    assertFalse(this.queue.isRunning());
    assertFalse(this.last().running());
    assertTrue(this.last().items().isEmpty());
  }

  @Test
  public void aPromptOnItsWayKeepsTheQueueWaiting() {
    this.runTurn();
    this.queue.offer(2, prompt("steer"), Delivery.STEER, true);
    this.queue.offer(3, prompt("later"), Delivery.QUEUE, true);
    assertNull("the agent still owes a turn for the steered prompt", this.queue.turnEnded(false));
    assertTrue(this.queue.isRunning());
    this.queue.taken(2, true);
    assertEquals(3, this.queue.turnEnded(false).turn());
  }

  @Test
  public void stopHoldsTheQueueUntilAPromptIsSent() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    this.queue.stop();
    assertNull(this.queue.turnEnded(false));
    assertFalse(this.queue.isRunning());
    assertTrue(this.last().held());
    assertEquals(Status.HELD, this.status(2));

    assertEquals("a new prompt goes first", Action.SEND, this.queue.offer(3, prompt("new"), Delivery.QUEUE, true));
    assertFalse(this.last().held());
    this.queue.dispatch(3, false);
    this.queue.taken(3, true);
    assertEquals("then the queue goes on", 2, this.queue.turnEnded(false).turn());
  }

  @Test
  public void interruptedOrFailedTurnHoldsTheQueue() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    this.queue.interrupted();
    assertNull(this.queue.turnEnded(false));
    assertTrue(this.last().held());

    this.queue.sendNow(2);
    this.queue.taken(2, true);
    this.queue.offer(3, prompt("third"), Delivery.QUEUE, true);
    assertNull("an error holds it too", this.queue.turnEnded(true));
    assertTrue(this.last().held());
  }

  @Test
  public void stopFlagsDoNotLeakIntoTheNextTurn() {
    this.queue.stop();
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    assertEquals("a stop while idle stops nothing later", 2, this.queue.turnEnded(false).turn());
  }

  @Test
  public void promptTypedAfterStopGoesNext() {
    this.runTurn();
    this.queue.offer(2, prompt("held"), Delivery.QUEUE, true);
    this.queue.stop();
    assertEquals(Action.HOLD, this.queue.offer(3, prompt("redirect"), Delivery.QUEUE, true));
    assertEquals(Status.NEXT, this.status(3));
    assertEquals(List.of(3, 2), this.turns());
    assertEquals(3, this.queue.turnEnded(false).turn());
    assertEquals(Status.HELD, this.status(2));
  }

  @Test
  public void sendNowInterruptsAndGoesNextWithoutHoldingTheRest() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt("third"), Delivery.QUEUE, true);
    assertEquals(SendNow.INTERRUPT, this.queue.sendNow(3));
    assertEquals("twice does nothing", SendNow.NONE, this.queue.sendNow(3));
    assertEquals(List.of(3, 2), this.turns());
    this.queue.interrupted();
    assertEquals(3, this.queue.turnEnded(false).turn());
    assertFalse(this.last().held());
    this.queue.taken(3, true);
    assertEquals(2, this.queue.turnEnded(false).turn());
  }

  @Test
  public void sendNowWhileTheNextTurnIsOnItsWayDoesNotInterrupt() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt("third"), Delivery.QUEUE, true);
    this.queue.turnEnded(false);
    assertEquals(SendNow.QUEUED, this.queue.sendNow(3));
    assertEquals(List.of(2, 3), this.turns());
  }

  @Test
  public void sendNowWithNothingRunningSendsAndResumes() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt("third"), Delivery.QUEUE, true);
    this.queue.stop();
    this.queue.turnEnded(false);
    assertEquals(SendNow.SEND, this.queue.sendNow(3));
    assertTrue(this.queue.isRunning());
    assertFalse(this.last().held());
    assertTrue(this.queue.dispatch(3, false));
    this.queue.taken(3, true);
    assertEquals(2, this.queue.turnEnded(false).turn());
  }

  @Test
  public void stopAfterSendNowHoldsItToo() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    this.queue.sendNow(2);
    this.queue.stop();
    assertEquals(Status.HELD, this.status(2));
    assertNull(this.queue.turnEnded(false));
    assertTrue(this.last().held());
  }

  @Test
  public void steerHandsAHeldPromptToTheTurn() {
    this.runTurn();
    this.queue.offer(2, prompt("a"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt("b"), Delivery.QUEUE, true);
    assertTrue(this.queue.steer(3, true));
    assertFalse("twice does nothing", this.queue.steer(3, true));
    assertEquals(List.of(3, 2), this.turns());
    assertTrue(this.queue.isSteering(3));
    assertTrue(this.queue.dispatch(3, true));
    assertFalse("a joining prompt during the turn settles nothing", this.queue.taken(3, false));
    assertFalse(this.queue.isSteering(3));
    assertEquals(2, this.queue.turnEnded(false).turn());
  }

  @Test
  public void steerNeedsAnAgentThatCanAndNoSlashCommand() {
    this.runTurn();
    assertEquals(Action.HOLD, this.queue.offer(2, prompt("a"), Delivery.STEER, false));
    assertEquals(Action.HOLD, this.queue.offer(3, prompt("  /compact"), Delivery.STEER, true));
    assertFalse(this.queue.steer(2, false));
    assertFalse(this.queue.steer(3, true));
    assertEquals(Action.STEER, this.queue.offer(4, prompt("look at b too"), Delivery.STEER, true));
  }

  @Test
  public void steerWithNothingRunningJustSends() {
    assertEquals(Action.SEND, this.queue.offer(1, prompt("a"), Delivery.STEER, true));
    this.queue.dispatch(1, false);
    this.queue.taken(1, true);
    this.queue.turnEnded(false);
    assertFalse(this.queue.isRunning());
    assertEquals("nothing runs any more", Action.SEND, this.queue.offer(2, prompt("b"), Delivery.STEER, true));
  }

  @Test
  public void lateJoinAfterTheTurnEndedSettles() {
    this.runTurn();
    this.queue.offer(2, prompt("steer"), Delivery.STEER, true);
    this.queue.offer(3, prompt("later"), Delivery.QUEUE, true);
    this.queue.dispatch(2, true);
    assertNull(this.queue.turnEnded(false));
    assertTrue(this.queue.taken(2, false));
    assertEquals(3, this.queue.settle().turn());
  }

  @Test
  public void unknownOrDuplicateTakesAreIgnored() {
    this.runTurn();
    this.queue.offer(2, prompt("a"), Delivery.QUEUE, true);
    assertFalse(this.queue.taken(99, false));
    assertFalse(this.queue.taken(1, false));
    assertEquals(List.of(2), this.turns());
  }

  @Test
  public void stoppedTurnHoldsWhatTheAgentDoesNotHaveYet() {
    this.runTurn();
    this.queue.offer(2, prompt("handed"), Delivery.STEER, true);
    this.queue.offer(3, prompt("not handed"), Delivery.STEER, true);
    this.queue.dispatch(2, true);
    this.queue.stop();
    assertNull(this.queue.turnEnded(false));
    assertEquals("the agent has it and can't give it back", Status.STEERING, this.status(2));
    assertEquals(Status.HELD, this.status(3));
    assertTrue("it still owes a turn for the handed one", this.queue.isRunning());
    assertFalse("a held prompt is handed only once it is sent again", this.queue.dispatch(3, true));
    this.queue.taken(2, true);
    assertEquals(List.of(3), this.turns());
  }

  @Test
  public void stopAfterAStoppedTurnStopsWaitingForAPromptTheAgentDropped() {
    this.runTurn();
    this.queue.offer(2, prompt("handed"), Delivery.STEER, true);
    this.queue.dispatch(2, true);
    this.queue.stop();
    this.queue.turnEnded(false);
    assertTrue(this.queue.stop());
    assertFalse(this.queue.isRunning());
    assertEquals(Status.HELD, this.status(2));
  }

  @Test
  public void stopBetweenTurnsSettlesAtOnce() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    assertEquals(2, this.queue.turnEnded(false).turn());
    assertTrue(this.queue.stop());
    assertFalse(this.queue.isRunning());
    assertEquals(Status.HELD, this.status(2));
    assertTrue(this.last().held());
    assertFalse("a delivery still on its way is dropped", this.queue.dispatch(2, false));
  }

  @Test
  public void stopBeforeTheAgentHasThePromptDropsIt() {
    assertEquals(Action.SEND, this.queue.offer(1, prompt("first"), Delivery.QUEUE, true));
    assertTrue(this.queue.stop());
    assertFalse(this.queue.isRunning());
    assertFalse("the launch that follows sends nothing", this.queue.dispatch(1, false));
    assertTrue(this.last().items().isEmpty());
  }

  @Test
  public void runningTurnSettlesOnlyWhenTheAgentEndsIt() {
    this.runTurn();
    assertFalse(this.queue.stop());
    assertFalse("a second stop too", this.queue.stop());
    assertTrue(this.queue.isRunning());
    assertNull(this.queue.turnEnded(false));
    assertFalse(this.queue.isRunning());
  }

  @Test
  public void steerAfterStopGoesNextInstead() {
    this.runTurn();
    this.queue.offer(2, prompt("held"), Delivery.QUEUE, true);
    this.queue.stop();
    assertEquals(Action.HOLD, this.queue.offer(3, prompt("steer"), Delivery.STEER, true));
    assertEquals(Status.NEXT, this.status(3));
    assertFalse("a stopping turn takes no steer", this.queue.steer(2, true));
  }

  @Test
  public void refusedSteerGoesAsAPromptUnlessTheQueueHolds() {
    this.runTurn();
    this.queue.offer(2, prompt("a"), Delivery.STEER, true);
    this.queue.dispatch(2, true);
    assertTrue(this.queue.steerRefused(2));
    assertEquals(Status.STEERING, this.status(2));

    this.queue.offer(3, prompt("b"), Delivery.STEER, true);
    this.queue.dispatch(3, true);
    this.queue.stop();
    assertFalse(this.queue.steerRefused(3));
    assertEquals(Status.HELD, this.status(3));
    assertFalse("only a steering prompt", this.queue.steerRefused(3));
    assertFalse(this.queue.steerRefused(99));
  }

  @Test
  public void steeredPromptIsListedBeforeTheHeldOnes() {
    this.runTurn();
    this.queue.offer(2, prompt("held"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt("steer"), Delivery.STEER, true);
    assertEquals(List.of(3, 2), this.turns());
  }

  @Test
  public void stopWithNothingRunningSettlesNothing() {
    assertFalse(this.queue.stop());
    assertFalse(this.queue.isRunning());
  }

  @Test
  public void agentBusyOnItsOwnMakesPromptsQueue() {
    this.queue.busy();
    int changes = this.snapshots.size();
    this.queue.busy();
    assertEquals("a busy agent that stays busy tells nothing", changes, this.snapshots.size());
    assertEquals(Action.HOLD, this.queue.offer(1, prompt("a"), Delivery.QUEUE, true));
    assertEquals(1, this.queue.turnEnded(false).turn());
  }

  @Test
  public void interruptedOutsideATurnDoesNotStopTheNext() {
    this.queue.interrupted();
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    assertEquals(Status.HELD, this.status(2));
    assertEquals(2, this.queue.turnEnded(false).turn());
  }

  @Test
  public void dispatchHandsAPromptOnlyTheWayItIsMeantToGo() {
    this.runTurn();
    this.queue.offer(2, prompt("steer"), Delivery.STEER, true);
    assertFalse(this.queue.dispatch(2, false));
    assertTrue(this.queue.dispatch(2, true));
  }

  @Test
  public void takeEditsOnlyHeldPrompts() {
    this.runTurn();
    ChatMessage.Submit second = prompt("second");
    this.queue.offer(2, second, Delivery.QUEUE, true);
    assertSame(second, this.queue.take(2));
    assertNull("twice gives nothing", this.queue.take(2));
    assertNull("not queued", this.queue.take(1));
    this.queue.offer(3, prompt("third"), Delivery.QUEUE, true);
    this.queue.turnEnded(false);
    assertNull("on its way", this.queue.take(3));
    assertNull("the edited one is not sent", this.queue.turnEnded(false));
  }

  @Test
  public void turnEndsWhileThePersonEditsTheOnlyPrompt() {
    this.runTurn();
    this.queue.offer(2, prompt("second"), Delivery.QUEUE, true);
    assertNotNull(this.queue.take(2));
    assertNull(this.queue.turnEnded(false));
    assertFalse(this.queue.isRunning());
  }

  @Test
  public void moveUpSwapsWithTheHeldPromptBefore() {
    this.runTurn();
    this.queue.offer(2, prompt("a"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt("b"), Delivery.QUEUE, true);
    this.queue.offer(4, prompt("c"), Delivery.QUEUE, true);
    assertFalse("the first stays", this.queue.moveUp(2));
    assertTrue(this.queue.moveUp(4));
    assertEquals(List.of(2, 4, 3), this.turns());
    assertTrue(this.queue.moveUp(4));
    assertEquals(List.of(4, 2, 3), this.turns());
    assertFalse(this.queue.moveUp(99));
    this.queue.steer(4, true);
    assertFalse("not past a steering one", this.queue.moveUp(2));
  }

  @Test
  public void agentEndBringsEverythingBackHeldAndForgetsDirectSends() {
    this.runTurn();
    this.queue.offer(2, prompt("steer"), Delivery.STEER, true);
    this.queue.offer(3, prompt("next"), Delivery.NEXT, true);
    this.queue.offer(4, prompt("queued"), Delivery.QUEUE, true);
    this.queue.stopped();
    assertFalse(this.queue.isRunning());
    assertTrue(this.last().held());
    assertEquals(List.of(2, 3, 4), this.turns());
    for (ChatMessage.QueueItem item : this.last().items()) {
      assertEquals(Status.HELD, item.status());
    }
    assertEquals(Action.SEND, this.queue.offer(5, prompt("again"), Delivery.QUEUE, true));
  }

  @Test
  public void drainGivesTheShownPromptsAndClosesTheQueue() {
    this.runTurn();
    this.queue.offer(2, prompt("ä 😀 日本"), Delivery.QUEUE, true);
    this.queue.offer(3, prompt(""), Delivery.QUEUE, true);
    List<ChatMessage.Submit> drained = this.queue.drain();
    assertEquals(2, drained.size());
    assertEquals("ä 😀 日本", drained.get(0).text());
    assertEquals("", drained.get(1).text());
    int changes = this.snapshots.size();
    assertEquals(Action.SEND, this.queue.offer(4, prompt("late"), Delivery.QUEUE, true));
    this.queue.busy();
    this.queue.stop();
    this.queue.stopped();
    assertNull(this.queue.turnEnded(false));
    assertFalse(this.queue.dispatch(4, false));
    assertEquals("a closed queue tells nothing", changes, this.snapshots.size());
    assertTrue(this.queue.drain().isEmpty());
  }

  @Test
  public void hugeQueueKeepsItsOrder() {
    this.runTurn();
    for (int turn = 2; turn < 502; turn++) {
      this.queue.offer(turn, prompt("x".repeat(10_000)), Delivery.QUEUE, true);
    }
    for (int turn = 2; turn < 502; turn++) {
      QueuedPrompts.Entry next = this.queue.turnEnded(false);
      assertEquals(turn, next.turn());
      this.queue.taken(turn, true);
    }
    assertNull(this.queue.turnEnded(false));
  }
}
