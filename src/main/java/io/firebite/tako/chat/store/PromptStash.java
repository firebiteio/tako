package io.firebite.tako.chat.store;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ui.bridge.ChatMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Drafts put aside from the composer, newest first. Kept in their own file beside the threads, since their images can
 * be large. Creating the service reads that file and every change rewrites it, so use it off the EDT.
 */
@Service(Service.Level.PROJECT)
public final class PromptStash {
  /** Pushing more drops the oldest. */
  public static final int MAX_ENTRIES = 20;
  private static final Logger LOG = Logger.getInstance(PromptStash.class);
  private static final Gson GSON = new Gson();

  private final Path file;
  /** Where large pastes are kept as files, see {@link PastedText}. */
  private final Path pastedFolder;
  private Data data;
  /** Where an unreadable stash file was moved, until {@link #takeSetAside()}. */
  private @Nullable Path setAside;
  /** False when the file could neither be read nor moved aside, so saving would lose the drafts in it. */
  private boolean isWritable = true;

  public PromptStash(Project project) {
    this(TakoFolder.get().resolve("stash-" + project.getLocationHash() + ".json"),
      PastedText.folder());
  }

  PromptStash(Path file, Path pastedFolder) {
    this.file = file;
    this.pastedFolder = pastedFolder;
    this.data = this.load();
  }

  public static PromptStash getInstance(Project project) {
    return project.getService(PromptStash.class);
  }

  /**
   * Saves a draft as the newest entry, with the text of its pasted text chips, since old pasted files get deleted.
   *
   * @throws IOException when it could not be saved; the stash is then as before
   */
  public synchronized Entry push(ChatMessage.Submit prompt) throws IOException {
    Entry entry = this.entry(prompt);
    this.save(List.of(entry));
    return entry;
  }

  /**
   * Saves drafts in one write, the last one as the newest entry. Like {@link #push} each.
   *
   * @return how many of them the stash kept
   * @throws IOException when they could not be saved; the stash is then as before
   */
  public synchronized int pushAll(List<ChatMessage.Submit> prompts) throws IOException {
    List<Entry> added = new ArrayList<>();
    for (int i = prompts.size() - 1; i >= 0; i--) {
      added.add(this.entry(prompts.get(i)));
    }
    this.save(added);
    return Math.min(added.size(), MAX_ENTRIES);
  }

  private Entry entry(ChatMessage.Submit prompt) {
    Entry entry = new Entry();
    entry.id = UUID.randomUUID().toString();
    entry.prompt = prompt;
    entry.savedAt = System.currentTimeMillis();
    for (ContextItem item : prompt.context()) {
      Path pasted = PastedText.fileOf(item, this.pastedFolder);
      String text = pasted != null ? readOrNull(pasted) : null;
      if (text != null) {
        entry.pasted.put(item.id(), text);
      }
    }
    return entry;
  }

  /** Puts the entries, newest first, before the old ones, dropping the oldest beyond {@link #MAX_ENTRIES}. */
  private void save(List<Entry> added) throws IOException {
    Data before = this.data;
    this.data = new Data();
    for (Entry entry : added) {
      if (this.data.entries.size() < MAX_ENTRIES) {
        this.data.entries.add(entry);
      }
    }
    for (Entry old : before.entries) {
      if (this.data.entries.size() < MAX_ENTRIES) {
        this.data.entries.add(old);
      }
    }
    try {
      this.write();
    } catch (IOException e) {
      this.data = before;
      throw e;
    }
  }

  /** Newest first. */
  public synchronized List<Entry> entries() {
    return new ArrayList<>(this.data.entries);
  }

  /**
   * Removes the entry to put it back in the composer. Pasted text whose file was deleted meanwhile gets a new file.
   *
   * @return it, or null when it is gone already
   */
  public synchronized @Nullable Entry take(String id) {
    Entry entry = this.detach(id);
    if (entry == null) {
      return null;
    }
    List<ContextItem> context = new ArrayList<>();
    for (ContextItem item : entry.prompt.context()) {
      context.add(this.withPastedFile(item, entry.pasted.get(item.id())));
    }
    entry.prompt = new ChatMessage.Submit(entry.prompt.text(), context, entry.prompt.images());
    return entry;
  }

  /** Deletes the entry and the files of its pasted text. */
  public synchronized void remove(String id) {
    Entry entry = this.detach(id);
    if (entry == null) {
      return;
    }
    for (ContextItem item : entry.prompt.context()) {
      Path pasted = PastedText.fileOf(item, this.pastedFolder);
      String text = entry.pasted.get(item.id());
      if (pasted != null && text != null && text.equals(readOrNull(pasted))) {
        try {
          Files.deleteIfExists(pasted);
        } catch (IOException e) {
          LOG.debug("Could not delete " + pasted, e);
        }
      }
    }
  }

  private @Nullable Entry detach(String id) {
    for (Entry entry : this.data.entries) {
      if (entry.id.equals(id)) {
        this.data.entries.remove(entry);
        try {
          this.write();
        } catch (IOException e) {
          LOG.warn("Could not save the prompt stash to " + this.file, e);
        }
        return entry;
      }
    }
    return null;
  }

  /** The chip, or a chip for a new file when its pasted file is gone or now holds another paste. */
  private ContextItem withPastedFile(ContextItem item, @Nullable String text) {
    Path pasted = PastedText.fileOf(item, this.pastedFolder);
    if (pasted == null || text == null || text.equals(readOrNull(pasted))) {
      return item;
    }
    try {
      return IdeContext.file(PastedText.write(this.pastedFolder, text));
    } catch (IOException e) {
      LOG.warn("Could not write the stashed pasted text again", e);
      return item;
    }
  }

  private static @Nullable String readOrNull(Path file) {
    try {
      return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    } catch (IOException e) {
      return null;
    }
  }

  /** Where an unreadable stash file was moved, to tell the person once; null afterwards. */
  public synchronized @Nullable Path takeSetAside() {
    Path path = this.setAside;
    this.setAside = null;
    return path;
  }

  private void write() throws IOException {
    if (!this.isWritable) {
      throw new IOException("Could not read " + this.file + ", so it is not overwritten");
    }
    AtomicFiles.write(this.file, GSON.toJson(this.data));
  }

  private Data load() {
    if (!Files.isRegularFile(this.file)) {
      return new Data();
    }
    try {
      return sanitized(GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), Data.class));
    } catch (IOException | JsonParseException e) {
      LOG.warn("Could not read the prompt stash from " + this.file, e);
      Path target = this.file.resolveSibling(this.file.getFileName() + ".unreadable-" + System.currentTimeMillis());
      try {
        Files.move(this.file, target);
        this.setAside = target;
      } catch (IOException moveFailed) {
        LOG.warn("Could not move the unreadable prompt stash aside", moveFailed);
        this.isWritable = false;
      }
      return new Data();
    }
  }

  /** Drops entries without an id or prompt and fills in what a prompt lacks. */
  private static Data sanitized(@Nullable Data data) {
    Data clean = new Data();
    if (data == null || data.entries == null) {
      return clean;
    }
    for (Entry entry : data.entries) {
      if (entry != null && entry.id != null && entry.prompt != null) {
        ChatMessage.Submit prompt = entry.prompt;
        entry.prompt = new ChatMessage.Submit(Objects.requireNonNullElse(prompt.text(), ""), present(prompt.context()),
          present(prompt.images()));
        if (entry.pasted == null) {
          entry.pasted = new HashMap<>();
        }
        clean.entries.add(entry);
      }
    }
    return clean;
  }

  private static <T> List<T> present(@Nullable List<T> items) {
    List<T> present = new ArrayList<>();
    if (items != null) {
      for (T item : items) {
        if (item != null) {
          present.add(item);
        }
      }
    }
    return present;
  }

  /** One stashed draft. */
  public static final class Entry {
    public String id = "";
    public ChatMessage.Submit prompt = new ChatMessage.Submit("", List.of(), List.of());
    public long savedAt;
    /** The text of each pasted text chip, by chip id. */
    public Map<String, String> pasted = new HashMap<>();

    /** The text's first non-blank line, else the first attachment's name. */
    public String label() {
      for (String line : this.prompt.text().split("\n")) {
        if (!line.isBlank()) {
          return line.strip();
        }
      }
      if (!this.prompt.context().isEmpty()) {
        return Objects.requireNonNullElse(this.prompt.context().getFirst().label(), "");
      }
      return this.prompt.images().isEmpty()
        ? ""
        : Objects.requireNonNullElse(this.prompt.images().getFirst().name(), "");
    }
  }

  static final class Data {
    List<Entry> entries = new ArrayList<>();
  }
}
