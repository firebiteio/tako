package io.firebite.tako.agent;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What a started agent offers the composer.
 *
 * @param agent           the backend id
 * @param authDescription how the agent is billed or signed in, for the empty thread's hint
 * @param model           the selected model, a {@link Model#value()} or what the agent resolves one to
 */
public record AgentInfo(
  String agent,
  String agentName,
  String authDescription,
  Capabilities capabilities,
  List<Model> models,
  @Nullable String model,
  List<Mode> modes,
  @Nullable String mode,
  @Nullable String effort,
  List<Command> commands
) {

  /**
   * @param images  prompts may carry images
   * @param compact {@code /compact} shrinks the context
   * @param fork    {@link AgentBackend#fork} copies the session
   * @param declineReason the agent reads {@link PermissionAnswer#message} when a permission prompt is declined
   * @param resume  a new process can resume the session, so the thread can restart its agent
   */
  public record Capabilities(
    boolean images,
    boolean compact,
    boolean fork,
    boolean declineReason,
    boolean resume
  ) {
  }

  /** @param efforts the reasoning effort levels the model takes, empty when it takes none */
  public record Model(
    String value,
    String displayName,
    @Nullable String description,
    @Nullable String resolvedModel,
    List<String> efforts
  ) {
  }

  /** A permission or approval mode. */
  public record Mode(
    String value,
    String label,
    String description
  ) {
  }

  public record Command(
    String name,
    String description,
    String hint
  ) {
  }
}
