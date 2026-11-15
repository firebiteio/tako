package io.firebite.tako.chat;

import io.firebite.tako.ui.bridge.message.thread.BenchFailed;
import io.firebite.tako.ui.bridge.message.thread.Submit;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A bench to start: a git worktree and a thread for each run, each submitting the prompt. If no run starts,
 * {@link BenchFailed} gives the prompt back to the source.
 *
 * @param prompt the typed prompt, whose text names the bench
 * @param source the unused thread the bench replaces, closed once the runs have threads
 */
public record BenchRequest(
  Submit prompt,
  @Nullable String projectPath,
  String source,
  List<RunSpec> runs
) {

  /** Blank values keep the agent's defaults. */
  public record RunSpec(
    String agent,
    String model,
    String mode,
    String effort
  ) {
  }
}
