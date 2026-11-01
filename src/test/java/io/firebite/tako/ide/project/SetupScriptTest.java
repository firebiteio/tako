package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class SetupScriptTest {

  @Test
  public void commandsSkipBlankLinesAndComments() {
    String text = "\uFEFF# install\r\n  npm ci  \r\n\r\n\t\n#build\nnpm run build # comment stays\n  # indented\nécho ✓\n";

    assertEquals(List.of("npm ci", "npm run build # comment stays", "écho ✓"), SetupScript.commands(text));
  }

  @Test
  public void emptyTextHasNoCommands() {
    assertEquals(List.of(), SetupScript.commands(""));
    assertEquals(List.of(), SetupScript.commands("\uFEFF"));
    assertEquals(List.of(), SetupScript.commands("\n\n# only comments\n   \n"));
  }

  @Test
  public void manyLinesAllCount() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      text.append("echo ").append(i).append('\n');
    }

    List<String> commands = SetupScript.commands(text.toString());

    assertEquals(5000, commands.size());
    assertEquals("echo 4999", commands.getLast());
  }

  @Test
  public void theWorktreesOwnFileComesFirst() throws Exception {
    Path worktree = Files.createTempDirectory("tako-worktree");
    Path repository = Files.createTempDirectory("tako-repo");
    Files.writeString(repository.resolve(SetupScript.FILE), "main\n");

    assertEquals(List.of("main"), SetupScript.read(worktree, repository));

    Files.writeString(worktree.resolve(SetupScript.FILE), "own\n");
    assertEquals(List.of("own"), SetupScript.read(worktree, repository));
  }

  @Test
  public void noFileMeansNoCommands() throws Exception {
    Path folder = Files.createTempDirectory("tako-none");

    assertEquals(List.of(), SetupScript.read(folder, folder));
    assertEquals(List.of(), SetupScript.read(folder.resolve("gone"), folder.resolve("gone too")));
  }

  @Test
  public void aFolderNamedLikeTheFileIsNoFile() throws Exception {
    Path folder = Files.createTempDirectory("tako-dir");
    Files.createDirectory(folder.resolve(SetupScript.FILE));

    assertEquals(List.of(), SetupScript.read(folder, folder));
  }

  @Test
  public void bytesThatAreNotUtf8DoNotFail() throws Exception {
    Path folder = Files.createTempDirectory("tako-bytes");
    Files.write(folder.resolve(SetupScript.FILE), new byte[]{'e', 'c', 'h', 'o', ' ', (byte) 0xff, '\n'});

    List<String> commands = SetupScript.read(folder, folder);

    assertEquals(1, commands.size());
    assertTrue(commands.getFirst().startsWith("echo "));
    assertEquals(new String(new byte[]{(byte) 0xff}, StandardCharsets.UTF_8), commands.getFirst().substring(5));
  }

  @Test
  public void windowsQuotesTheCommandForCmd() {
    Map<String, String> environment = Map.of("COMSPEC", "C:\\Windows\\system32\\cmd.exe");

    assertEquals(List.of("C:\\Windows\\system32\\cmd.exe", "/d", "/s", "/c", "\"echo \"a b\" > out.txt\""),
      SetupScript.shell("echo \"a b\" > out.txt", true, environment));
  }

  @Test
  public void windowsFindsCmdInTheSystemFolderWithoutComSpec() {
    assertEquals(Path.of("D:\\Win", "System32", "cmd.exe").toString(),
      SetupScript.shell("dir", true, Map.of("SYSTEMROOT", "D:\\Win")).getFirst());
    assertEquals("cmd.exe", SetupScript.shell("dir", true, Map.of("ComSpec", " ")).getFirst());
  }

  @Test
  public void unixRunsTheCommandInSh() {
    assertEquals(List.of("/bin/sh", "-c", "npm ci && echo 'done'"), SetupScript.shell("npm ci && echo 'done'", false,
      Map.of()));
  }

  @Test
  public void environmentNamesTheWorktreeAndTheMainCheckout() {
    Path worktree = Path.of("work", "trée 1");
    Path repository = Path.of("repo");

    Map<String, String> environment = SetupScript.environment(Map.of("PATH", "/bin"), worktree, repository);

    assertEquals("/bin", environment.get("PATH"));
    assertEquals(worktree.toString(), environment.get(SetupScript.WORKTREE_VARIABLE));
    assertEquals(repository.toString(), environment.get(SetupScript.REPOSITORY_VARIABLE));
  }
}
