package io.firebite.tako.chat.thread;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.agent.ToolKind;
import io.firebite.tako.chat.store.ThreadPast;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A thread's prompts and the agent's answers as plain text, without tool calls or thinking, for another agent to read.
 * Also keeps the files changed and the latest todo list and plan, and the turns of agents the thread ran before.
 */
public final class Conversation {
  private final List<Turn> turns = new ArrayList<>();
  /** Prompts sent that the agent has not taken yet, by their key. */
  private final Map<Integer, String> sent = new HashMap<>();
  private final Set<String> files = new LinkedHashSet<>();
  private @Nullable String todos;
  private @Nullable String plan;
  /** What the agents before the current one did, which every reset starts from. */
  private ThreadPast past = ThreadPast.EMPTY;
  /** The first turn of the current agent. */
  private int segmentStart;

  public static Conversation of(List<AgentEvent> events) {
    Conversation conversation = new Conversation();
    conversation.reset(events);
    return conversation;
  }

  /** Starts over from the earlier agents' past and a replayed session's events. */
  public synchronized void reset(List<AgentEvent> events) {
    this.turns.clear();
    this.files.clear();
    for (ThreadPast.Turn turn : this.past.turns()) {
      Turn restored = new Turn(turn.prompt());
      restored.answer.append(turn.answer());
      this.turns.add(restored);
    }
    this.segmentStart = this.turns.size();
    this.files.addAll(this.past.files());
    this.todos = this.past.todos();
    this.plan = this.past.plan();
    for (AgentEvent event : events) {
      this.add(event);
    }
  }

  /** Makes {@code past} what every reset starts from, and starts over from it. */
  public synchronized void restore(ThreadPast past) {
    this.past = past;
    this.reset(List.of());
  }

  /** What the earlier agents did, as {@link #restore} last set it. */
  public synchronized ThreadPast past() {
    return this.past;
  }

  /**
   * Keeps the prompts and the answer text of the main agent, the files tool calls changed and the main agent's latest
   * todo list and plan. Subagents' text and everything else is left out.
   */
  public synchronized void add(AgentEvent event) {
    switch (event) {
      case AgentEvent.Prompt prompt -> this.prompt(prompt.text(), prompt.images().size());
      case AgentEvent.Text text -> {
        if (text.parentId() == null && !text.text().isBlank()) {
          if (this.turns.size() == this.segmentStart) {
            this.turns.add(new Turn(null));
          }
          StringBuilder answer = this.turns.getLast().answer;
          answer.append(answer.isEmpty() ? "" : "\n\n").append(text.text().strip());
        }
      }
      case AgentEvent.ToolStarted tool -> {
        if (tool.parentId() == null && tool.kind() == ToolKind.TODO) {
          this.todos = todoText(tool.input());
        } else if (tool.parentId() == null && tool.kind() == ToolKind.PLAN) {
          String plan = Json.string(tool.input(), "plan");
          if (plan != null && !plan.isBlank()) {
            this.plan = plan;
          }
        }
      }
      case AgentEvent.ToolFinished finished -> {
        if (finished.change() != null) {
          this.files.remove(finished.change().path());
          this.files.add(finished.change().path());
        }
      }
      case AgentEvent.FilesChanged changed -> {
        for (AgentEvent.Change change : changed.changes()) {
          this.files.remove(change.path());
          this.files.add(change.path());
        }
      }
      default -> {
      }
    }
  }

  /**
   * A prompt as typed, without the IDE context sent along. It counts once the agent takes it, see {@link #take}.
   *
   * @param images how many images it carries
   */
  public synchronized void send(int key, String text, int images) {
    this.sent.put(key, promptText(text, images));
  }

  /** The agent took the prompt sent with {@code key}, so the answer text that follows belongs to it. */
  public synchronized void take(int key) {
    String text = this.sent.remove(key);
    if (text != null) {
      this.prompt(text, 0);
    }
  }

  public synchronized boolean isEmpty() {
    return this.turns.isEmpty();
  }

  /**
   * The past once the thread leaves its current agent: the earlier segments, one for the current agent's turns, the
   * files changed and the latest todo list and plan.
   */
  public synchronized ThreadPast past(String agentName, String nextAgentName) {
    List<ThreadPast.Segment> segments = new ArrayList<>(this.past.segments());
    segments.add(new ThreadPast.Segment(agentName, nextAgentName, this.snapshot(this.segmentStart, this.turns.size())));
    return new ThreadPast(segments, new ArrayList<>(this.files), this.todos, this.plan);
  }

  /** The whole thread for {@link Handoff#text}. */
  public synchronized Handoff.Input handoffInput() {
    return new Handoff.Input(this.snapshot(0, this.turns.size()), new ArrayList<>(this.files), this.todos, this.plan);
  }

  private List<ThreadPast.Turn> snapshot(int from, int to) {
    List<ThreadPast.Turn> snapshot = new ArrayList<>();
    for (Turn turn : this.turns.subList(from, to)) {
      snapshot.add(new ThreadPast.Turn(turn.prompt, turn.answer.toString()));
    }
    return snapshot;
  }

  /** Slash commands are left out. */
  private void prompt(String text, int images) {
    String prompt = promptText(text, images);
    if (!prompt.startsWith("/")) {
      this.turns.add(new Turn(prompt));
    }
  }

  /** The prompt stripped, with how many images it carried. A prompt of only images shows as such. */
  private static String promptText(String text, int images) {
    String prompt = text.strip();
    if (prompt.isEmpty()) {
      return "[images]";
    }
    if (images <= 0 || prompt.startsWith("/")) {
      return prompt;
    }
    return prompt + " [" + images + (images == 1 ? " image]" : " images]");
  }

  /** Each todo as {@code - [status] content}, one per line. */
  private static String todoText(JsonObject input) {
    StringBuilder text = new StringBuilder();
    for (JsonObject todo : Json.objects(input, "todos")) {
      String content = Json.string(todo, "content");
      if (content != null && !content.isBlank()) {
        String status = Json.string(todo, "status");
        text.append("- [").append(status != null ? status : "pending").append("] ").append(content.strip())
          .append('\n');
      }
    }
    return text.toString().strip();
  }

  /**
   * The conversation, oldest turn first. When it is longer than {@code maxChars}, the oldest turns are left out and a
   * note says how many. A newest turn longer than that keeps its end.
   */
  public synchronized String text(int maxChars) {
    List<String> kept = new ArrayList<>();
    int length = 0;
    for (int i = this.turns.size() - 1; i >= 0; i--) {
      String turn = this.turns.get(i).text();
      if (!kept.isEmpty() && length + turn.length() > maxChars) {
        break;
      }
      kept.add(turn.length() > maxChars ? "[cut] " + turn.substring(turn.length() - maxChars) : turn);
      length += turn.length();
    }
    StringBuilder text = new StringBuilder();
    int left = this.turns.size() - kept.size();
    if (left > 0) {
      text.append('[').append(left).append(left == 1 ? " earlier turn" : " earlier turns").append(" left out]\n\n");
    }
    for (int i = kept.size() - 1; i >= 0; i--) {
      text.append(kept.get(i)).append(i > 0 ? "\n\n" : "");
    }
    return text.toString();
  }

  private static final class Turn {
    private final @Nullable String prompt;
    private final StringBuilder answer = new StringBuilder();

    private Turn(@Nullable String prompt) {
      this.prompt = prompt;
    }

    private String text() {
      StringBuilder text = new StringBuilder();
      if (this.prompt != null) {
        text.append("Person: ").append(this.prompt);
      }
      if (!this.answer.isEmpty()) {
        text.append(text.isEmpty() ? "" : "\n\n").append("Agent: ").append(this.answer);
      }
      return text.toString();
    }
  }
}
