package io.firebite.tako.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Starting and stopping agent processes. */
public final class Processes {
  /** How long {@link #run} waits for the output after the process exited. */
  private static final long STREAM_GRACE_SECONDS = 5;
  private static final ExecutorService READERS = Executors.newCachedThreadPool(
    Thread.ofPlatform().daemon().name("tako-process-output-", 0).factory());

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

  /**
   * Runs {@code command} to its end with {@code input} on stdin and returns what it printed.
   *
   * @throws IOException when it cannot start, outlasts the timeout or exits with an error, with its error output
   */
  public static String run(List<String> command, Path workDirectory, Map<String, String> environment, String input,
    long timeoutSeconds) throws IOException {
    Process process = start(command, workDirectory, environment);
    CompletableFuture<String> output = readAll(process.getInputStream());
    CompletableFuture<String> errors = readAll(process.getErrorStream());
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // It exited before reading all of it. Its exit code and error output tell why.
    }
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new TimeoutException();
      }
      if (process.exitValue() != 0) {
        String error = errors.get(STREAM_GRACE_SECONDS, TimeUnit.SECONDS).strip();
        throw new IOException(error.isEmpty() ? "Exit code " + process.exitValue() : error);
      }
      return output.get(STREAM_GRACE_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      destroyTree(process);
      throw new IOException("No answer after " + timeoutSeconds + " s", e);
    } catch (InterruptedException e) {
      destroyTree(process);
      Thread.currentThread().interrupt();
      throw new IOException(e);
    } catch (ExecutionException e) {
      throw new IOException(e.getCause());
    }
  }

  /** Stops the process and the processes it started. npm installs agents as scripts that start the real one. */
  public static void destroyTree(Process process) {
    Iterator<ProcessHandle> descendants = process.descendants().iterator();
    while (descendants.hasNext()) {
      descendants.next().destroy();
    }
    process.destroy();
  }

  /**
   * Reads the stream to its end on its own thread, so a full pipe cannot block the process. A process it started may
   * keep the pipe open after it exits, which leaves the thread reading until that one exits too.
   */
  private static CompletableFuture<String> readAll(InputStream stream) {
    return CompletableFuture.supplyAsync(() -> {
      try (stream) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, READERS);
  }
}
