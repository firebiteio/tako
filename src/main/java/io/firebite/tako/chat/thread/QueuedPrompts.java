package io.firebite.tako.chat.thread;

import io.firebite.tako.ui.bridge.ChatMessage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The prompts of one thread the agent has not taken yet: the ones held until the running turn ends, and the ones on
 * their way to the agent. Decides when the next one goes and when the queue holds. Safe from any thread; the listener
 * hears each change under the queue's lock, so changes reach it in order.
 */
public final class QueuedPrompts {
  private final Listener listener;
  private final List<Entry> entries = new ArrayList<>();
  /** A turn runs or a prompt is on its way to the agent. */
  private boolean isRunning;
  /** Held prompts wait until a prompt is sent. */
  private boolean isHeld;
  /** The agent works on a turn now. */
  private boolean isTurnOpen;
  private boolean wasInterrupted;
  private boolean isStopRequested;
  private boolean isClosed;

  public QueuedPrompts(Listener listener) {
    this.listener = listener;
  }

  /**
   * Takes a prompt the person sent.
   *
   * @param canSteer the agent can take prompts into the running turn
   * @return what to do with it now
   */
  public synchronized Action offer(int turn, ChatMessage.Submit prompt, Delivery delivery, boolean canSteer) {
    if (this.isClosed) {
      return Action.SEND;
    }
    this.isHeld = false;
    if (!this.isRunning) {
      this.isRunning = true;
      this.entries.add(new Entry(turn, prompt, Status.SENDING, false));
      this.changed();
      return Action.SEND;
    }
    if (delivery == Delivery.STEER && canSteer && isSteerable(prompt.text()) && !this.isStopping()) {
      this.entries.add(this.frontEnd(), new Entry(turn, prompt, Status.STEERING, true));
      this.changed();
      return Action.STEER;
    }
    Entry entry = new Entry(turn, prompt, Status.HELD, true);
    if (delivery == Delivery.NEXT || this.isStopping()) {
      entry.status = Status.NEXT;
      this.entries.add(this.frontEnd(), entry);
    } else {
      this.entries.add(entry);
    }
    this.changed();
    return Action.HOLD;
  }

  /** The agent works, on a prompt or on its own. */
  public synchronized void busy() {
    if (this.isClosed || (this.isRunning && this.isTurnOpen)) {
      return;
    }
    this.isRunning = true;
    this.isTurnOpen = true;
    this.changed();
  }

  /** The agent says it stopped the running turn. */
  public synchronized void interrupted() {
    if (this.isTurnOpen) {
      this.wasInterrupted = true;
    }
  }

  /**
   * The person stops the agent: the queue holds, and prompts that were to go next wait too. A stop between turns while
   * a prompt is on its way settles at once: prompts on their way are held again, and the one sent at once is dropped.
   * A running turn settles when the agent ends it.
   *
   * @return true when it settled at once, so no turn's end will tell the chat
   */
  public synchronized boolean stop() {
    if (this.isClosed) {
      return false;
    }
    this.isHeld = true;
    for (Entry entry : this.entries) {
      if (entry.status == Status.NEXT) {
        entry.status = Status.HELD;
      }
    }
    if (this.isRunning && !this.isTurnOpen) {
      this.endStopped();
      return true;
    }
    if (this.isTurnOpen) {
      this.isStopRequested = true;
    }
    this.changed();
    return false;
  }

  /**
   * The running turn ended. After a stop or an error, prompts on their way that were not handed to the agent yet come
   * back held, and so does the queue unless a prompt is to go next. Prompts the agent already has stay on their way:
   * it may still take them, and it can't give them back.
   *
   * @return the prompt to send now, marked {@link Status#SENDING}, or null
   */
  public synchronized @Nullable Entry turnEnded(boolean failed) {
    if (this.isClosed) {
      return null;
    }
    boolean stopped = this.wasInterrupted || this.isStopRequested;
    this.wasInterrupted = false;
    this.isStopRequested = false;
    this.isTurnOpen = false;
    if (stopped || failed) {
      boolean hasNext = false;
      for (Entry entry : this.entries) {
        if (entry.isShown && !entry.isDispatched
          && (entry.status == Status.SENDING || entry.status == Status.STEERING)) {
          entry.status = Status.HELD;
        }
        hasNext |= entry.status == Status.NEXT;
      }
      this.isHeld |= !hasNext;
    }
    return this.next();
  }

  /**
   * Like a turn's end without a stop, unless a turn is open, e.g. after the agent took a steered prompt once its turn
   * had ended.
   */
  public synchronized @Nullable Entry settle() {
    return this.isClosed || this.isTurnOpen ? null : this.next();
  }

  /** Takes a held prompt out of the queue, e.g. to edit it. Null when it is not held (any more). */
  public synchronized ChatMessage.@Nullable Submit take(int turn) {
    Entry entry = this.find(turn);
    if (this.isClosed || entry == null || entry.status != Status.HELD) {
      return null;
    }
    this.entries.remove(entry);
    if (this.entries.isEmpty() && !this.isRunning) {
      this.isHeld = false;
    }
    this.changed();
    return entry.prompt;
  }

  /** Swaps a held prompt with the held one before it. */
  public synchronized boolean moveUp(int turn) {
    Entry entry = this.find(turn);
    if (this.isClosed || entry == null || entry.status != Status.HELD) {
      return false;
    }
    int index = this.entries.indexOf(entry);
    for (int i = index - 1; i >= 0; i--) {
      if (this.entries.get(i).status == Status.HELD) {
        this.entries.set(index, this.entries.get(i));
        this.entries.set(i, entry);
        this.changed();
        return true;
      }
    }
    return false;
  }

  /**
   * Sends a held prompt before the others. With a turn running it goes once that turn ends, which the caller hurries
   * unless the next turn is already on its way. Resumes a held queue.
   */
  public synchronized SendNow sendNow(int turn) {
    Entry entry = this.find(turn);
    if (this.isClosed || entry == null || entry.status != Status.HELD) {
      return SendNow.NONE;
    }
    this.isHeld = false;
    this.entries.remove(entry);
    if (!this.isRunning) {
      this.isRunning = true;
      entry.status = Status.SENDING;
      this.entries.addFirst(entry);
      this.changed();
      return SendNow.SEND;
    }
    entry.status = Status.NEXT;
    this.entries.add(this.frontEnd(), entry);
    boolean isOnItsWay = false;
    for (Entry other : this.entries) {
      isOnItsWay |= other.status == Status.SENDING;
    }
    this.changed();
    return this.isTurnOpen && !isOnItsWay ? SendNow.INTERRUPT : SendNow.QUEUED;
  }

  /** Hands a held prompt to the running turn, unless it is stopping. Slash commands are not steered. */
  public synchronized boolean steer(int turn, boolean canSteer) {
    Entry entry = this.find(turn);
    if (this.isClosed || entry == null || entry.status != Status.HELD || !this.isRunning || !canSteer
      || !isSteerable(entry.prompt.text()) || this.isStopping()) {
      return false;
    }
    entry.status = Status.STEERING;
    this.entries.remove(entry);
    this.entries.add(this.frontEnd(), entry);
    this.changed();
    return true;
  }

  /** Whether the prompt is still to be handed to the agent. Marks it handed. Call right before handing it. */
  public synchronized boolean dispatch(int turn, boolean steer) {
    Entry entry = this.find(turn);
    if (this.isClosed || entry == null || entry.isDispatched
      || entry.status != (steer ? Status.STEERING : Status.SENDING)) {
      return false;
    }
    entry.isDispatched = true;
    return true;
  }

  /** Whether no prompt but this one is on its way to the agent. */
  public synchronized boolean owesOnly(int turn) {
    for (Entry entry : this.entries) {
      if (entry.turn != turn && (entry.status == Status.SENDING || entry.status == Status.STEERING)) {
        return false;
      }
    }
    return true;
  }

  /** The prompt the agent has not taken yet, or null. */
  public synchronized ChatMessage.@Nullable Submit promptOf(int turn) {
    Entry entry = this.find(turn);
    return entry == null ? null : entry.prompt;
  }

  /**
   * The agent refused a steered prompt. Unless the queue holds, it goes as a turn of its own; else it waits held.
   *
   * @return true to send it as a prompt; false when it was held, and the caller should {@link #settle}
   */
  public synchronized boolean steerRefused(int turn) {
    Entry entry = this.find(turn);
    if (this.isClosed || entry == null || entry.status != Status.STEERING) {
      return false;
    }
    if (!this.isHeld && !this.isStopping()) {
      return true;
    }
    entry.status = Status.HELD;
    entry.isDispatched = false;
    this.changed();
    return false;
  }

  /** Whether the prompt is handed to the agent to steer its turn and still not taken. */
  public synchronized boolean isSteering(int turn) {
    Entry entry = this.find(turn);
    return !this.isClosed && entry != null && entry.status == Status.STEERING;
  }

  /**
   * The agent took a prompt.
   *
   * @return true when a steered prompt joined a turn that had already ended, so the caller should {@link #settle}
   */
  public synchronized boolean taken(int turn, boolean startsTurn) {
    if (this.isClosed) {
      return false;
    }
    Entry entry = this.find(turn);
    boolean isLate = entry != null && !startsTurn && !this.isTurnOpen && entry.status == Status.STEERING;
    if (entry != null) {
      this.entries.remove(entry);
    }
    if (startsTurn) {
      this.isRunning = true;
      this.isTurnOpen = true;
    }
    this.changed();
    return isLate;
  }

  /** The agent is gone: what it had not taken waits held, prompts the chat shows as turns are forgotten. */
  public synchronized void stopped() {
    if (this.isClosed) {
      return;
    }
    this.endStopped();
  }

  /** Tells the listener the current state again, e.g. after the state was changed elsewhere. */
  public synchronized void refresh() {
    if (!this.isClosed) {
      this.changed();
    }
  }

  /** Empties and closes the queue. @return the queued prompts the chat showed, in order */
  public synchronized List<ChatMessage.Submit> drain() {
    List<ChatMessage.Submit> prompts = new ArrayList<>();
    for (Entry entry : this.entries) {
      if (entry.isShown) {
        prompts.add(entry.prompt);
      }
    }
    this.entries.clear();
    this.isRunning = false;
    this.isClosed = true;
    return prompts;
  }

  public synchronized boolean isRunning() {
    return this.isRunning;
  }

  public synchronized boolean isClosed() {
    return this.isClosed;
  }

  /** What the chat shows. */
  public synchronized ChatMessage.Queue snapshot() {
    List<ChatMessage.QueueItem> items = new ArrayList<>();
    for (Entry entry : this.entries) {
      if (entry.isShown) {
        items.add(new ChatMessage.QueueItem(entry.turn, entry.status));
      }
    }
    return new ChatMessage.Queue(items, this.isHeld && !items.isEmpty(), this.isRunning);
  }

  /** Shown prompts back to held, the others forgotten, the queue held, nothing running. */
  private void endStopped() {
    Iterator<Entry> iterator = this.entries.iterator();
    while (iterator.hasNext()) {
      Entry entry = iterator.next();
      if (entry.isShown) {
        entry.status = Status.HELD;
        entry.isDispatched = false;
      } else {
        iterator.remove();
      }
    }
    this.isHeld = !this.entries.isEmpty();
    this.isRunning = false;
    this.isTurnOpen = false;
    this.wasInterrupted = false;
    this.isStopRequested = false;
    this.changed();
  }

  /** The next prompt to send once no turn runs, or null after it noted that nothing runs. */
  private @Nullable Entry next() {
    for (Entry entry : this.entries) {
      if (entry.status == Status.SENDING || entry.status == Status.STEERING) {
        this.changed();
        return null;
      }
    }
    Entry next = null;
    for (Entry entry : this.entries) {
      if (entry.status == Status.NEXT || (entry.status == Status.HELD && !this.isHeld)) {
        next = entry;
        break;
      }
    }
    if (next == null) {
      this.isRunning = false;
    } else {
      next.status = Status.SENDING;
      next.isDispatched = false;
      this.entries.remove(next);
      this.entries.addFirst(next);
    }
    this.changed();
    return next;
  }

  /** Where prompts that go before the held ones end. */
  private int frontEnd() {
    int index = 0;
    while (index < this.entries.size() && this.entries.get(index).status != Status.HELD) {
      index++;
    }
    return index;
  }

  private @Nullable Entry find(int turn) {
    for (Entry entry : this.entries) {
      if (entry.turn == turn) {
        return entry;
      }
    }
    return null;
  }

  private void changed() {
    this.listener.changed(this.snapshot());
  }

  /** The person stopped the open turn, which has not ended yet. */
  private boolean isStopping() {
    return this.isTurnOpen && (this.isStopRequested || this.wasInterrupted);
  }

  /** Agents run slash commands only as a prompt of their own, so those are not steered. */
  public static boolean isSteerable(String text) {
    return !text.stripLeading().startsWith("/");
  }

  /** Hears every change, under the queue's lock: it must not block or call back into the queue. */
  public interface Listener {
    void changed(ChatMessage.Queue snapshot);
  }

  /** How the person wants a prompt sent while a turn runs. */
  public enum Delivery {
    /** After the running turn and the prompts queued before it. */
    QUEUE,
    /** Right after the running turn, even one that was stopped. */
    NEXT,
    /** Into the running turn, if the agent can take it. */
    STEER
  }

  public enum Action {
    /** Send it now; nothing runs. */
    SEND,
    /** It waits in the queue. */
    HOLD,
    /** Hand it to the running turn now. */
    STEER
  }

  public enum Status {
    /** Waits for its turn. */
    HELD,
    /** Goes once the running turn ends, before the held ones. */
    NEXT,
    /** On its way to the agent as a turn of its own. */
    SENDING,
    /** Handed to the running turn; the agent takes it into that turn or as the next one. */
    STEERING
  }

  public enum SendNow {
    NONE,
    /** It goes next; stop the running turn to hurry it. */
    INTERRUPT,
    /** It goes next, once the turn on its way ended. */
    QUEUED,
    /** Send it now; nothing runs. */
    SEND
  }

  /**
   * A prompt the agent has not taken.
   *
   * @param isShown the chat shows it as queued; false for a prompt sent at once, which the chat shows as its turn
   */
  public static final class Entry {
    private final int turn;
    private final ChatMessage.Submit prompt;
    private final boolean isShown;
    private Status status;
    private boolean isDispatched;

    private Entry(int turn, ChatMessage.Submit prompt, Status status, boolean isShown) {
      this.turn = turn;
      this.prompt = prompt;
      this.status = status;
      this.isShown = isShown;
    }

    public int turn() {
      return this.turn;
    }

    public ChatMessage.Submit prompt() {
      return this.prompt;
    }
  }
}
