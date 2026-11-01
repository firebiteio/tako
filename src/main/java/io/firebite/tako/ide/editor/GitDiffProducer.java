package io.firebite.tako.ide.editor;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.chains.DiffRequestProducer;
import com.intellij.diff.chains.DiffRequestProducerException;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.ide.project.GitDiffs;
import io.firebite.tako.ide.project.Worktrees;
import java.io.IOException;
import java.util.Map;

/**
 * One file of a git diff, read when the diff window gets to it. The working tree side is the file itself, so it shows
 * later edits and can be edited.
 */
final class GitDiffProducer implements DiffRequestProducer {
  private final Project project;
  private final GitDiffs.Changes changes;
  private final GitDiffs.Change change;
  private final String beforeTitle;
  private final String afterTitle;
  private final Map<String, String> environment;
  private final long limit;

  /** @param limit the largest file size in bytes that is loaded */
  GitDiffProducer(Project project, GitDiffs.Changes changes, GitDiffs.Change change, String beforeTitle,
    String afterTitle, Map<String, String> environment, long limit) {
    this.project = project;
    this.changes = changes;
    this.change = change;
    this.beforeTitle = beforeTitle;
    this.afterTitle = afterTitle;
    this.environment = environment;
    this.limit = limit;
  }

  @Override
  public String getName() {
    String oldPath = this.change.oldPath();
    return oldPath == null ? this.change.path() : TakoBundle.message("diff.git.renamed", this.change.path(), oldPath);
  }

  @Override
  public DiffRequest process(UserDataHolder context, ProgressIndicator indicator)
    throws DiffRequestProducerException {
    String title = this.getName();
    try {
      DiffContent before = this.before();
      DiffContent after = this.after();
      return new SimpleDiffRequest(title, before, after, this.beforeTitle, this.afterTitle);
    } catch (Unshown e) {
      return new MessageDiffRequest(title, e.getMessage());
    } catch (Worktrees.GitException | IOException e) {
      throw new DiffRequestProducerException(String.valueOf(e.getMessage()));
    }
  }

  private DiffContent before() throws Unshown, Worktrees.GitException, IOException {
    String revision = this.changes.beforeRevision();
    if (revision == null || this.change.status() == 'A') {
      return DiffContentFactory.getInstance().createEmpty();
    }
    String oldPath = this.change.oldPath();
    return this.committed(revision, oldPath != null ? oldPath : this.change.path());
  }

  private DiffContent after() throws Unshown, Worktrees.GitException, IOException {
    if (this.change.status() == 'D') {
      return DiffContentFactory.getInstance().createEmpty();
    }
    String revision = this.changes.afterRevision();
    return revision != null ? this.committed(revision, this.change.path()) : this.workingTree(this.change.path());
  }

  private DiffContent committed(String revision, String path) throws Unshown, Worktrees.GitException, IOException {
    GitDiffs.Content content = GitDiffs.content(this.changes.topLevel(), revision, path, this.limit,
      this.environment);
    if (content.isSubmodule()) {
      throw new Unshown(TakoBundle.message("diff.git.submodule"));
    }
    if (content.bytes() == null) {
      throw Unshown.tooLarge(content.size());
    }
    String name = PathUtil.getFileName(path);
    return DiffContentFactory.getInstance().createFromBytes(this.project, content.bytes(),
      FileTypeManager.getInstance().getFileTypeByFileName(name), name);
  }

  private DiffContent workingTree(String path) throws Unshown {
    VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(this.changes.topLevel().resolve(path));
    if (file == null || !file.isValid()) {
      return DiffContentFactory.getInstance().createEmpty();
    }
    if (file.isDirectory()) {
      throw new Unshown(TakoBundle.message("diff.git.submodule"));
    }
    if (file.getLength() > this.limit) {
      throw Unshown.tooLarge(file.getLength());
    }
    return DiffContentFactory.getInstance().create(this.project, file);
  }

  /** A side the window shows a message for instead of its content. */
  private static final class Unshown extends Exception {
    Unshown(String message) {
      super(message, null, false, false);
    }

    static Unshown tooLarge(long size) {
      return new Unshown(TakoBundle.message("diff.git.too.large", StringUtil.formatFileSize(size)));
    }
  }
}
