package io.firebite.tako.chat;

import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.chat.thread.ThreadTitles;
import io.firebite.tako.ide.context.ContextItem;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** What a side chat starts with: its main thread, title and, without a forked session, an excerpt of the main thread. */
public final class SideChats {
  /** How much of the main thread's conversation an excerpt keeps, newest first. */
  public static final int EXCERPT_CHARS = 20000;

  private SideChats() {
  }

  /** The main thread's conversation as a chip for the side chat's first prompt, null when there is none. */
  public static @Nullable ContextItem excerpt(String title, String conversation) {
    if (conversation.isBlank()) {
      return null;
    }
    String text = "This is a side chat about the Tako thread \"" + title
      + "\". That thread goes on working on its own; "
      + "answer the side question with its conversation in mind. Its prompts and answers so far:\n\n"
      + "<main_thread>\n" + conversation.strip() + "\n</main_thread>";
    return ContextItem.create(ContextItem.Kind.SELECTION, TakoBundle.message("side.excerpt.label"), text);
  }

  /** The title of a side chat asked {@code question}; a blank one leaves it for the first prompt to name. */
  public static String title(String question) {
    return question.isBlank() ? ThreadStore.Record.DEFAULT_TITLE : ThreadTitles.fromPrompt(question);
  }

  /** The main thread of a side chat while it exists, else the thread itself. */
  public static ThreadStore.Record mainOf(ThreadStore store, ThreadStore.Record record) {
    if (!record.sideChat) {
      return record;
    }
    ThreadStore.Record main = store.find(record.parentId);
    return main != null ? main : record;
  }

  /** The side chats of the thread, in the store's order. */
  public static List<ThreadStore.Record> of(ThreadStore store, String mainId) {
    List<ThreadStore.Record> sideChats = new ArrayList<>();
    for (ThreadStore.Record record : store.all()) {
      if (record.sideChat && mainId.equals(record.parentId)) {
        sideChats.add(record);
      }
    }
    return sideChats;
  }
}
