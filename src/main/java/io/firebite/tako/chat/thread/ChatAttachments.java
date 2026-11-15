package io.firebite.tako.chat.thread;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EnvironmentUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.ide.context.IdeContext;
import io.firebite.tako.ide.context.Images;
import io.firebite.tako.ide.context.PastedText;
import io.firebite.tako.ide.project.FileIndex;
import io.firebite.tako.ui.bridge.message.ChatError;
import io.firebite.tako.ui.bridge.message.thread.ChatImage;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/** What the thread's composer attaches: images, large pastes and the files an {@code @} mention finds. */
public final class ChatAttachments {
  private static final Logger LOG = Logger.getInstance(ChatAttachments.class);
  private static final int FILE_MATCHES = 30;
  private static final String PASTED_IMAGE_NAME = "pasted-image.png";

  private final Project project;
  private final ChatView view;
  private final Supplier<@Nullable String> projectPath;

  ChatAttachments(Project project, ChatView view, Supplier<@Nullable String> projectPath) {
    this.project = project;
    this.view = view;
    this.projectPath = projectPath;
  }

  /** Reads image files in the background and attaches them to the composer. */
  public void attachFiles(List<Path> files) {
    Async.inBackground(() -> this.readImages(files));
  }

  private void readImages(List<Path> files) {
    for (Path file : files) {
      try {
        ImageAttachment image = Images.fromFile(file);
        if (image == null) {
          this.postError(TakoBundle.message("error.image.unsupported", file.getFileName()));
        } else {
          this.view.post(new ChatImage(image));
        }
      } catch (IOException e) {
        this.postError(TakoBundle.message("error.image.read", file.getFileName(), e.getMessage()));
      }
    }
  }

  /** Attaches the clipboard's image, if it holds one. Must run on the EDT. */
  public void pasteImage() {
    Image image;
    try {
      image = CopyPasteManager.getInstance().getContents(DataFlavor.imageFlavor);
    } catch (RuntimeException e) {
      image = null;
    }
    if (image == null) {
      return;
    }
    Image clipboardImage = image;
    Async.inBackground(() -> {
      try {
        this.view.post(new ChatImage(Images.fromImage(clipboardImage, PASTED_IMAGE_NAME)));
      } catch (IOException e) {
        this.postError(TakoBundle.message("error.clipboard.image", e.getMessage()));
      }
    });
  }

  /**
   * Writes a large paste to a file in {@link PastedText#folder()}, which every launch passes as an additional
   * directory, so the agent reads it instead of the prompt carrying it. Writes right away, so the file is attached
   * before the next key is handled.
   *
   * @return the file as context, or null after showing why it could not be written
   */
  public @Nullable ContextItem pasteText(String text) {
    try {
      return IdeContext.file(PastedText.write(PastedText.folder(), text));
    } catch (IOException e) {
      this.postError(TakoBundle.message("error.paste.text", e.getMessage()));
      return null;
    }
  }

  /** Deletes the file of a pasted text chip removed before sending. Other chips are left alone. */
  public void discardPaste(ContextItem item) {
    Path file = PastedText.fileOf(item, PastedText.folder());
    if (file == null) {
      return;
    }
    Async.inBackground(() -> {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        LOG.debug("Could not delete " + file, e);
      }
    });
  }

  /** Asks for image files to attach. The chooser opens once the current event is handled. */
  public void pickImages() {
    Async.onEdt(this::chooseImages);
  }

  private void chooseImages() {
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.multiFiles()
      .withTitle(TakoBundle.message("dialog.attach.images.title"))
      .withFileFilter(file -> Images.isImageFile(file.getName()));
    List<Path> paths = new ArrayList<>();
    for (VirtualFile file : FileChooser.chooseFiles(descriptor, this.project, null)) {
      paths.add(file.toNioPath());
    }
    this.attachFiles(paths);
  }

  /** Project files matching an {@code @} mention. Lists the folder, so call off the EDT. */
  public List<FileIndex.Match> findFiles(String query) {
    String directory = this.projectPath.get();
    if (directory == null) {
      return List.of();
    }
    List<String> entries = FileIndex.entries(Path.of(directory), EnvironmentUtil.getEnvironmentMap());
    return FileIndex.search(entries, query, FILE_MATCHES);
  }

  private void postError(String text) {
    this.view.post(new ChatError(text));
  }
}
