package io.firebite.tako.chat;

import io.firebite.tako.chat.store.ThreadStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the usage limits threads' agents stopped on, and sends a thread's continue prompt once its limit reset, if the
 * person scheduled that. Changes records only; the caller saves and shows them.
 *
 * <p>Several threads waiting for the same limit do not all resume at once: an agent resumes one thread per
 * {@link #SPACING_MILLIS}, and a limit hit again pushes the agent's other waiting threads to its new reset.
 */
final class LimitResumes {
  /** How long an agent waits between two resumes. */
  static final long SPACING_MILLIS = 30_000;
  /** A resume this overdue, e.g. after the IDE was closed for days, no longer goes out. */
  static final long MAX_OVERDUE_MILLIS = 24 * 60 * 60 * 1000L;
  /** A reset further ahead is taken as nonsense. */
  static final long MAX_AHEAD_MILLIS = 31 * 24 * 60 * 60 * 1000L;
  /** A reset this far in the past is taken as stale. */
  static final long STALE_RESET_MILLIS = 60 * 60 * 1000L;

  /**
   * Commands that only show something, such as the limit itself, without arguments. Others, like {@code /clear} or
   * {@code /init}, move the thread on.
   */
  private static final Pattern LOOKING_COMMAND = Pattern.compile(
    "/(usage|status|cost|context|stats|help|doctor|config|mcp|model)");

  private final LongSupplier clock;
  /** When each agent last resumed a thread, by agent id. Shared by every project's chat. */
  private final Map<String, Long> lastResumes;

  LimitResumes(LongSupplier clock, Map<String, Long> lastResumes) {
    this.clock = clock;
    this.lastResumes = lastResumes;
  }

  /**
   * The thread's agent stopped on a limit. Replaces what the thread had. A resume still waiting, e.g. when a queued
   * prompt ran into the limit again, keeps its prompt, and stays scheduled, or is scheduled once the reset is named.
   *
   * @param resetsAt epoch millis, 0 when unknown
   * @param all      the project's threads, whose waits for the same agent move to a later reset
   * @return the records changed, the thread's first
   */
  List<ThreadStore.Record> onLimit(ThreadStore.Record record, @Nullable String label, long resetsAt,
    List<ThreadStore.Record> all) {
    long now = this.clock.getAsLong();
    ThreadStore.LimitStop previous = record.limit;
    ThreadStore.LimitStop stop = new ThreadStore.LimitStop();
    stop.label = label;
    stop.hitAt = now;
    stop.resetsAt = plausible(resetsAt, now);
    if (previous != null) {
      boolean wanted = previous.scheduled || previous.resumesOnceReset;
      stop.prompt = previous.prompt;
      stop.scheduled = wanted && stop.resetsAt != 0;
      stop.resumesOnceReset = wanted && stop.resetsAt == 0;
    }
    record.limit = stop;
    return this.pushOthers(record, all, now);
  }

  /**
   * The agent named the reset of the limit it reported without one. Fills it in, unless the person moved on and the
   * thread has no such limit any more.
   *
   * @param resetsAt epoch millis
   * @return the records changed, the thread's first; empty when nothing changed
   */
  List<ThreadStore.Record> onReset(ThreadStore.Record record, @Nullable String label, long resetsAt,
    List<ThreadStore.Record> all) {
    long now = this.clock.getAsLong();
    ThreadStore.LimitStop stop = record.limit;
    long plausible = plausible(resetsAt, now);
    if (stop == null || stop.resetsAt != 0 || plausible == 0) {
      return List.of();
    }
    stop.resetsAt = plausible;
    stop.label = label;
    stop.scheduled = stop.resumesOnceReset;
    stop.resumesOnceReset = false;
    return this.pushOthers(record, all, now);
  }

  /** The reset, or 0 when it is unknown, stale or too far ahead to be real. */
  private static long plausible(long resetsAt, long now) {
    return resetsAt > now - STALE_RESET_MILLIS && resetsAt < now + MAX_AHEAD_MILLIS ? resetsAt : 0;
  }

  /** Moves the agent's other waiting threads to the thread's later reset, as the account is limited until then. */
  private List<ThreadStore.Record> pushOthers(ThreadStore.Record record, List<ThreadStore.Record> all, long now) {
    ThreadStore.LimitStop stop = record.limit;
    List<ThreadStore.Record> changed = new ArrayList<>();
    changed.add(record);
    if (stop == null || stop.resetsAt == 0) {
      return changed;
    }
    for (ThreadStore.Record other : all) {
      ThreadStore.LimitStop waiting = other.limit;
      if (other != record && waiting != null && waiting.scheduled && other.agent().equals(record.agent())
        && waiting.fireAt() < stop.fireAt()) {
        waiting.resetsAt = stop.resetsAt;
        waiting.hitAt = now;
        changed.add(other);
      }
    }
    return changed;
  }

  /**
   * Whether a prompt moves the thread on, so its limit and resume no longer matter. A command that only shows
   * something, such as {@code /usage}, does not, so the person can check the limit without losing the resume.
   */
  static boolean movesOn(String typed) {
    return !LOOKING_COMMAND.matcher(typed.strip()).matches();
  }

  /** Resumes the thread once its limit reset. Only for a reset the agent named and that is not long past. */
  boolean schedule(ThreadStore.Record record) {
    ThreadStore.LimitStop stop = record.limit;
    if (stop == null || stop.scheduled || stop.resetsAt == 0 || stop.fireAt() <= this.clock.getAsLong()) {
      return false;
    }
    stop.scheduled = true;
    return true;
  }

  boolean cancel(ThreadStore.Record record) {
    ThreadStore.LimitStop stop = record.limit;
    if (stop == null || !stop.scheduled && !stop.resumesOnceReset) {
      return false;
    }
    stop.scheduled = false;
    stop.resumesOnceReset = false;
    return true;
  }

  /** @param text what the resume sends; blank for the default */
  boolean setPrompt(ThreadStore.Record record, String text) {
    ThreadStore.LimitStop stop = record.limit;
    if (stop == null) {
      return false;
    }
    stop.prompt = text.isBlank() ? null : text;
    return true;
  }

  /** Forgets the limit, e.g. as the person sent a prompt or archived the thread. */
  boolean dismiss(ThreadStore.Record record) {
    if (record.limit == null) {
      return false;
    }
    record.limit = null;
    return true;
  }

  /**
   * The resumes that go out now, which no longer wait, and the waits that ended without one, overdue by more than
   * {@link #MAX_OVERDUE_MILLIS}.
   *
   * @param isBusy whether the thread's agent works or waits, so a resume would join someone else's turn
   */
  Due due(List<ThreadStore.Record> all, Predicate<ThreadStore.Record> isBusy) {
    long now = this.clock.getAsLong();
    List<ThreadStore.Record> changed = new ArrayList<>();
    List<ThreadStore.Record> ready = new ArrayList<>();
    for (ThreadStore.Record record : all) {
      ThreadStore.LimitStop stop = record.limit;
      if (stop == null || !stop.scheduled) {
        continue;
      }
      if (now - stop.fireAt() > MAX_OVERDUE_MILLIS) {
        stop.scheduled = false;
        changed.add(record);
      } else if (stop.fireAt() <= now && !isBusy.test(record)) {
        ready.add(record);
      }
    }
    ready.sort(Comparator.comparingLong((ThreadStore.Record record) -> record.limit.fireAt())
      .thenComparingLong(record -> record.limit.hitAt));
    List<Resume> fire = new ArrayList<>();
    Set<String> resumedAgents = new HashSet<>();
    for (ThreadStore.Record record : ready) {
      String agent = record.agent();
      Long last = this.lastResumes.get(agent);
      boolean spaced = last == null || now - last >= SPACING_MILLIS || now < last;
      if (!spaced || !resumedAgents.add(agent)) {
        continue;
      }
      fire.add(new Resume(record, record.limit.prompt));
      record.limit = null;
      this.lastResumes.put(agent, now);
      changed.add(record);
    }
    return new Due(changed, fire);
  }

  /**
   * @param changed the records whose limit changed, to save and show
   * @param fire    the prompts to send now
   */
  record Due(
    List<ThreadStore.Record> changed,
    List<Resume> fire
  ) {
  }

  /** @param prompt what to send, null for the default */
  record Resume(
    ThreadStore.Record record,
    @Nullable String prompt
  ) {
  }
}
