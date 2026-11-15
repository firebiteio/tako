package io.firebite.tako.chat;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.chat.store.ThreadStore;
import io.firebite.tako.ide.Async;
import io.firebite.tako.ide.project.ProjectIcons;
import io.firebite.tako.ui.bridge.message.ChatError;
import io.firebite.tako.ui.bridge.message.workspace.ProjectItem;
import io.firebite.tako.ui.bridge.message.workspace.Projects;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** The folders threads work in: the IDE project first, then the ones the person added. */
public final class ProjectFolders {
  private final Project project;
  private final ChatService service;
  private final ThreadStore store;

  ProjectFolders(Project project, ChatService service, ThreadStore store) {
    this.project = project;
    this.service = service;
    this.store = store;
  }

  List<ThreadStore.ProjectFolder> all() {
    String basePath = this.project.getBasePath();
    return this.store.projects(basePath != null ? basePath : "", this.project.getName());
  }

  /** The project folder a thread stores, null for the IDE project itself. */
  @Nullable String threadPath(@Nullable String projectPath) {
    return projectPath == null || ThreadStore.samePath(projectPath, this.project.getBasePath()) ? null : projectPath;
  }

  /** @param projectPath a project folder, or null for the IDE project */
  String folderOf(@Nullable String projectPath) {
    String basePath = this.project.getBasePath();
    return projectPath != null ? projectPath : basePath != null ? basePath : "";
  }

  /** Adds the folder as a project unless it is one. Nothing for null, the IDE project. */
  void ensure(@Nullable String projectPath) {
    if (projectPath != null && this.store.findProject(projectPath) == null) {
      this.store.addProject(projectPath, Path.of(projectPath).getFileName().toString());
      this.post();
    }
  }

  /** Reads the folders' icons in the background. */
  void post() {
    List<ThreadStore.ProjectFolder> folders = this.all();
    Async.inBackground(() -> {
      List<ProjectItem> items = new ArrayList<>();
      for (int i = 0; i < folders.size(); i++) {
        ThreadStore.ProjectFolder folder = folders.get(i);
        ProjectIcons.Icons icons = ProjectIcons.find(Path.of(folder.path));
        items.add(new ProjectItem(folder.path, folder.name, folder.collapsed, i == 0, icons.light(),
          icons.dark()));
      }
      this.service.post(new Projects(items));
    });
  }

  /** Asks for a folder to add as a project. The chooser opens once the current event is handled. */
  public void add() {
    Async.onEdt(this::choose);
  }

  private void choose() {
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.singleDir()
      .withTitle(TakoBundle.message("dialog.add.project.title"))
      .withDescription(TakoBundle.message("dialog.add.project.description"));
    VirtualFile folder = FileChooser.chooseFile(descriptor, this.project, null);
    if (folder == null) {
      return;
    }
    this.store.addProject(folder.getPath(), folder.getName());
    this.post();
  }

  public void remove(String path) {
    if (ThreadStore.samePath(path, this.project.getBasePath())) {
      return;
    }
    for (ThreadStore.Record record : this.store.all()) {
      if (!record.archived && ThreadStore.samePath(record.projectPath, path)) {
        this.service.post(new ChatError(TakoBundle.message("error.project.remove")));
        return;
      }
    }
    this.store.removeProject(path);
    this.post();
  }

  public void collapse(String path, boolean collapsed) {
    ThreadStore.ProjectFolder folder = this.store.findProject(path);
    if (folder != null) {
      folder.collapsed = collapsed;
      this.store.save();
    }
  }
}
