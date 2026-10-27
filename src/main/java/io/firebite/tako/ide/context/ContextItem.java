package io.firebite.tako.ide.context;

import com.google.gson.annotations.SerializedName;
import java.util.UUID;

/**
 * A piece of IDE context attached to the next prompt.
 *
 * @param label what the chat shows on the chip
 * @param text  what the prompt carries
 */
public record ContextItem(
  String id,
  Kind kind,
  String label,
  String text
) {
  public static ContextItem create(Kind kind, String label, String text) {
    return new ContextItem(UUID.randomUUID().toString(), kind, label, text);
  }

  /** Another Tako thread, by reference: the agent reads its conversation over MCP when it needs it. */
  public static ContextItem thread(String threadId, String title) {
    return create(Kind.THREAD, title, "Referenced Tako thread \"" + title + "\" (id " + threadId + "). Read its "
      + "conversation with Tako's get_thread tool, threadId \"" + threadId + "\" and conversation true.");
  }

  public enum Kind {
    @SerializedName("selection") SELECTION,
    @SerializedName("file") FILE,
    @SerializedName("problems") PROBLEMS,
    @SerializedName("thread") THREAD
  }
}
