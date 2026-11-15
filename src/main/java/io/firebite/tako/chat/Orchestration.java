package io.firebite.tako.chat;

import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.settings.ChatSettings;

/** The orchestrator role Tako's `/orchestrate` gives a thread's agent for one task. */
public final class Orchestration {
  /** What the orchestrator is told when the settings hold no instructions of their own. */
  public static final String DEFAULT_INSTRUCTIONS = """
    You are the orchestrator for the task above. Plan it and delegate the parts worth delegating to other Tako threads.

    1. Understand the task and the relevant code yourself first.
    2. Call list_agents. Follow the person's notes there when you choose agent, model and effort for each subtask: \
    cheaper, faster models for mechanical work, stronger ones for hard reasoning, and another agent where the notes \
    say it is better.
    3. Do small things yourself. A thread costs setup time and tokens.
    4. Split the rest into independent subtasks and start each with create_thread. The new thread sees nothing of \
    this conversation, so its prompt must be self-contained: goal, relevant files, constraints, how to verify, and \
    what to report back. Pass worktree: true when threads would edit the same files.
    5. Start all independent threads before you wait for any. Get their results one of two ways:
       - Pass wake: true to create_thread and send_to_thread, then end your turn or keep working. Tako prompts you \
    with each thread's answer as it finishes.
       - Call wait_for_threads with their ids: until all, or until any to handle each as it finishes.
    6. Read the results and verify them yourself, with the build and tests, instead of trusting the reports. Send \
    corrections with send_to_thread.
    7. Finish with a summary: what each thread did, with which agent and model, and what you did not verify.""";

  private Orchestration() {
  }

  /**
   * The instructions as a chip for the task's prompt. They follow the task in the IDE context block, so the timeline
   * and transcripts show the task alone.
   */
  public static ContextItem context(String label) {
    return ContextItem.create(ContextItem.Kind.SELECTION, label, instructions());
  }

  /** The instructions from the settings, or the defaults when those are blank. */
  public static String instructions() {
    String custom = ChatSettings.state().orchestrationInstructions;
    return custom.isBlank() ? DEFAULT_INSTRUCTIONS : custom;
  }
}
