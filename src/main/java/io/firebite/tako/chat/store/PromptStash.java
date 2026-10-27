package io.firebite.tako.chat.store;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.context.ContextItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Drafts put aside from the composer, newest first. Kept in their own file beside the threads, since their images can
 * be large.
 */
@Service(Service.Level.PROJECT)
public final class PromptStash {
  private static final Logger LOG = Logger.getInstance(PromptStash.class);
  private static final Gson GSON = new Gson();

  private final Path file;
  private final Data data;

  public PromptStash(Project project) {
    this(Path.of(PathManager.getSystemPath(), "tako", "stash-" + project.getLocationHash() + ".json"));
  }

  PromptStash(Path file) {
    this.file = file;
    this.data = load(file);
  }

  public static PromptStash getInstance(Project project) {
    return project.getService(PromptStash.class);
  }

  public synchronized Entry push(String text, List<ContextItem> context, List<ImageAttachment> images) {
    Entry entry = new Entry();
    entry.id = UUID.randomUUID().toString();
    entry.text = text;
    entry.context = new ArrayList<>(context);
    entry.images = new ArrayList<>(images);
    entry.savedAt = System.currentTimeMillis();
    this.data.entries.addFirst(entry);
    this.save();
    return entry;
  }

  /** Newest first. */
  public synchronized List<Entry> entries() {
    return new ArrayList<>(this.data.entries);
  }

  /** Removes the entry. @return it, or null when it is gone already */
  public synchronized @Nullable Entry take(String id) {
    for (Entry entry : this.data.entries) {
      if (entry.id.equals(id)) {
        this.data.entries.remove(entry);
        this.save();
        return entry;
      }
    }
    return null;
  }

  private void save() {
    try {
      Files.createDirectories(this.file.getParent());
      Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
      Files.writeString(temporary, GSON.toJson(this.data), StandardCharsets.UTF_8);
      Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warn("Could not save the prompt stash to " + this.file, e);
    }
  }

  private static Data load(Path file) {
    if (!Files.isRegularFile(file)) {
      return new Data();
    }
    try {
      Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
      if (data == null) {
        return new Data();
      }
      if (data.entries == null) {
        data.entries = new ArrayList<>();
      }
      return data;
    } catch (IOException | JsonParseException e) {
      LOG.warn("Could not read the prompt stash from " + file, e);
      return new Data();
    }
  }

  /** One stashed draft: the typed text with its attached context and images. */
  public static final class Entry {
    public String id = "";
    public String text = "";
    public List<ContextItem> context = new ArrayList<>();
    public List<ImageAttachment> images = new ArrayList<>();
    public long savedAt;

    /** The text's first non-blank line, else the first attachment's name. */
    public String label() {
      for (String line : this.text.split("\n")) {
        if (!line.isBlank()) {
          return line.strip();
        }
      }
      if (!this.context.isEmpty()) {
        return this.context.getFirst().label();
      }
      return this.images.isEmpty() ? "" : this.images.getFirst().name();
    }
  }

  static final class Data {
    List<Entry> entries = new ArrayList<>();
  }
}
