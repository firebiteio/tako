package io.firebite.tako.ide.editor;

import com.intellij.diff.chains.DiffRequestProducerException;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.firebite.tako.ide.project.GitDiffs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GitDiffProducerTest extends BasePlatformTestCase {
  private static final Map<String, String> ENVIRONMENT = System.getenv();

  private Path repository;
  private String first;
  private String second;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    this.repository = Files.createTempDirectory("tako-producer").toRealPath();
    git(this.repository, "init", "-q", "-b", "main");
    git(this.repository, "config", "user.email", "test@example.com");
    git(this.repository, "config", "user.name", "Test");
    git(this.repository, "config", "core.autocrlf", "false");
    this.write("a.txt", "one\n");
    this.write("old.txt", "renamed text\n");
    git(this.repository, "add", "--all");
    git(this.repository, "commit", "-q", "-m", "first");
    this.first = git(this.repository, "rev-parse", "HEAD").trim();
    this.write("a.txt", "two\n");
    git(this.repository, "commit", "-q", "-am", "second");
    this.second = git(this.repository, "rev-parse", "HEAD").trim();
  }

  public void testWorkingTreeSideIsTheFileOnDisk() throws Exception {
    this.write("a.txt", "three\n");

    SimpleDiffRequest request = this.simple(this.uncommitted(new GitDiffs.Change('M', "a.txt", null)));

    assertEquals("a.txt", request.getTitle());
    assertEquals("two\n", text(request.getContents().get(0)));
    assertEquals("three\n", text(request.getContents().get(1)));
    assertEquals(List.of("HEAD", "Working tree"), request.getContentTitles());
  }

  public void testCommittedSidesComeFromGit() throws Exception {
    GitDiffs.Changes changes = new GitDiffs.Changes(this.repository, this.first, this.second, "a^", "a",
      List.of(new GitDiffs.Change('M', "a.txt", null)));
    this.write("a.txt", "changed later\n");

    SimpleDiffRequest request = this.simple(this.producer(changes, changes.files().get(0), 1000));

    assertEquals("one\n", text(request.getContents().get(0)));
    assertEquals("two\n", text(request.getContents().get(1)));
  }

  public void testAddedAndDeletedFilesHaveAnEmptySide() throws Exception {
    this.write("new.txt", "new\n");

    SimpleDiffRequest added = this.simple(this.uncommitted(new GitDiffs.Change('A', "new.txt", null)));
    SimpleDiffRequest deleted = this.simple(this.uncommitted(new GitDiffs.Change('D', "a.txt", null)));

    assertInstanceOf(added.getContents().get(0), EmptyContent.class);
    assertEquals("new\n", text(added.getContents().get(1)));
    assertEquals("two\n", text(deleted.getContents().get(0)));
    assertInstanceOf(deleted.getContents().get(1), EmptyContent.class);
  }

  public void testAFileDeletedAfterTheListWasReadShowsAsEmpty() throws Exception {
    Files.delete(this.repository.resolve("a.txt"));

    SimpleDiffRequest request = this.simple(this.uncommitted(new GitDiffs.Change('M', "a.txt", null)));

    assertInstanceOf(request.getContents().get(1), EmptyContent.class);
  }

  public void testARenameShowsBothNamesAndTheOldContent() throws Exception {
    git(this.repository, "mv", "old.txt", "new name.txt");

    GitDiffProducer producer = this.uncommitted(new GitDiffs.Change('R', "new name.txt", "old.txt"));
    SimpleDiffRequest request = this.simple(producer);

    assertEquals("new name.txt (renamed from old.txt)", producer.getName());
    assertEquals("renamed text\n", text(request.getContents().get(0)));
    assertEquals("renamed text\n", text(request.getContents().get(1)));
  }

  public void testLargeFilesAndFoldersShowAMessage() throws Exception {
    this.write("nested/inner.txt", "x\n");
    GitDiffs.Changes changes = new GitDiffs.Changes(this.repository, this.second, null, "HEAD", null, List.of());

    DiffRequest largeCommitted = this.producer(changes, new GitDiffs.Change('M', "a.txt", null), 2)
      .process(new UserDataHolderBase(), new EmptyProgressIndicator());
    DiffRequest largeWorking = this.producer(changes, new GitDiffs.Change('A', "a.txt", null), 2)
      .process(new UserDataHolderBase(), new EmptyProgressIndicator());
    DiffRequest folder = this.producer(changes, new GitDiffs.Change('A', "nested", null), 1000)
      .process(new UserDataHolderBase(), new EmptyProgressIndicator());

    assertInstanceOf(largeCommitted, MessageDiffRequest.class);
    assertTrue(((MessageDiffRequest) largeCommitted).getMessage().startsWith("File is too large to show"));
    assertInstanceOf(largeWorking, MessageDiffRequest.class);
    assertInstanceOf(folder, MessageDiffRequest.class);
  }

  public void testAMovedSubmoduleShowsAMessage() throws Exception {
    git(this.repository, "update-index", "--add", "--cacheinfo", "160000," + this.first + ",sub");
    git(this.repository, "commit", "-q", "-m", "submodule");
    String withSubmodule = git(this.repository, "rev-parse", "HEAD").trim();
    git(this.repository, "update-index", "--cacheinfo", "160000," + this.second + ",sub");
    git(this.repository, "commit", "-q", "-m", "move submodule");
    String moved = git(this.repository, "rev-parse", "HEAD").trim();
    GitDiffs.Changes changes = new GitDiffs.Changes(this.repository, withSubmodule, moved, "a", "b",
      List.of(new GitDiffs.Change('M', "sub", null)));

    DiffRequest request = this.producer(changes, changes.files().get(0), 1000)
      .process(new UserDataHolderBase(), new EmptyProgressIndicator());

    assertInstanceOf(request, MessageDiffRequest.class);
    assertEquals("Submodule or nested repository, no file diff", ((MessageDiffRequest) request).getMessage());
  }

  public void testAGitFailureIsReportedForThatFileOnly() {
    GitDiffs.Changes changes = new GitDiffs.Changes(this.repository, "0123456789012345678901234567890123456789",
      null, "gone", null, List.of());
    GitDiffProducer producer = this.producer(changes, new GitDiffs.Change('M', "a.txt", null), 1000);

    assertThrows(DiffRequestProducerException.class,
      () -> producer.process(new UserDataHolderBase(), new EmptyProgressIndicator()));
  }

  private GitDiffProducer uncommitted(GitDiffs.Change change) {
    GitDiffs.Changes changes = new GitDiffs.Changes(this.repository, this.second, null, "HEAD", null,
      List.of(change));
    return this.producer(changes, change, 1000);
  }

  private GitDiffProducer producer(GitDiffs.Changes changes, GitDiffs.Change change, long limit) {
    return new GitDiffProducer(this.getProject(), changes, change,
      changes.beforeName() == null ? "Empty" : changes.beforeName(),
      changes.afterName() == null ? "Working tree" : changes.afterName(), ENVIRONMENT, limit);
  }

  private SimpleDiffRequest simple(GitDiffProducer producer) throws Exception {
    DiffRequest request = producer.process(new UserDataHolderBase(), new EmptyProgressIndicator());
    assertInstanceOf(request, SimpleDiffRequest.class);
    return (SimpleDiffRequest) request;
  }

  private static String text(DiffContent content) {
    assertInstanceOf(content, DocumentContent.class);
    return ((DocumentContent) content).getDocument().getText();
  }

  private void write(String path, String text) throws Exception {
    Path file = this.repository.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private static String git(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(output, 0, process.waitFor());
    return output;
  }
}
