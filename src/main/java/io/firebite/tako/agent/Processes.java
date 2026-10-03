package io.firebite.tako.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Starting and stopping agent processes. */
public final class Processes {
  private Processes() {
  }

  /** Starts {@code command} with exactly {@code environment}. */
  public static Process start(List<String> command, Path workDirectory, Map<String, String> environment)
    throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workDirectory.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    return builder.start();
  }

  /** Stops the process and the processes it started. npm installs agents as scripts that start the real one. */
  public static void destroyTree(Process process) {
    Iterator<ProcessHandle> descendants = process.descendants().iterator();
    while (descendants.hasNext()) {
      descendants.next().destroy();
    }
    process.destroy();
  }
}
