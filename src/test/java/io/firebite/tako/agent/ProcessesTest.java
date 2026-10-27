package io.firebite.tako.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProcessesTest {
  private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void runReturnsWhatTheProcessPrinted() throws IOException {
    Path source = this.program("Echo", "System.out.print(new String(System.in.readAllBytes()).toUpperCase());");

    assertEquals("HELLO", Processes.run(List.of(JAVA, source.toString()), this.folder.getRoot().toPath(),
      System.getenv(), "hello", 60));
  }

  @Test
  public void earlyExitReportsTheErrorOutputNotTheClosedPipe() {
    IOException error = assertThrows(IOException.class, () -> Processes.run(List.of(JAVA, "--no-such-flag"),
      this.folder.getRoot().toPath(), System.getenv(), "x".repeat(1 << 20), 60));

    assertTrue(error.getMessage(), error.getMessage().contains("--no-such-flag"));
  }

  @Test
  public void runStopsAfterTheTimeout() throws IOException {
    Path source = this.program("Sleep", "Thread.sleep(60_000);");

    IOException error = assertThrows(IOException.class, () -> Processes.run(List.of(JAVA, source.toString()),
      this.folder.getRoot().toPath(), System.getenv(), "", 1));

    assertEquals("No answer after 1 s", error.getMessage());
  }

  private Path program(String name, String body) throws IOException {
    Path source = this.folder.getRoot().toPath().resolve(name + ".java");
    Files.writeString(source, "public class " + name + " { public static void main(String[] args) throws Exception { "
      + body + " } }", StandardCharsets.UTF_8);
    return source;
  }
}
