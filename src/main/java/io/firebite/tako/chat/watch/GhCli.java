package io.firebite.tako.chat.watch;

import com.intellij.openapi.diagnostic.Logger;
import io.firebite.tako.chat.thread.ChatController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/** Runs the GitHub CLI and tells why a call failed. */
public final class GhCli {
  public static final int NOT_STARTED = -1;
  public static final int TIMED_OUT = -2;
  private static final Logger LOG = Logger.getInstance(GhCli.class);
  private static final long TIMEOUT_SECONDS = 20;
  /** How long a killed gh may take to exit, so its output files are free to delete, on Windows too. */
  private static final long KILL_WAIT_SECONDS = 5;
  private static final int AUTH_EXIT_CODE = 4;
  private static final int MESSAGE_LENGTH = 200;

  private GhCli() {
  }

  /**
   * Runs {@code executable} with a copy of {@code environment}, never prompting.
   *
   * @param executable {@code gh} or a path to it
   */
  public static Runner system(String executable, Map<String, String> environment) {
    return (directory, args) -> run(executable, environment, directory, args);
  }

  /** Why the call failed, or null when it succeeded. */
  public static @Nullable Failure classify(Result result) {
    if (result.exitCode() == 0) {
      return null;
    }
    if (result.exitCode() == NOT_STARTED) {
      return new Failure(Kind.MISSING, result.err());
    }
    String err = result.err().toLowerCase(Locale.ROOT);
    if (result.exitCode() == AUTH_EXIT_CODE || err.contains("gh auth login") || err.contains("not logged in")
      || err.contains("http 401") || err.contains("bad credentials")) {
      return new Failure(Kind.LOGGED_OUT, firstLine(result.err()));
    }
    if (err.contains("rate limit")) {
      return new Failure(Kind.RATE_LIMITED, firstLine(result.err()));
    }
    String line = firstLine(result.err());
    return new Failure(Kind.OTHER, line.isEmpty() ? "gh exited with " + result.exitCode() : line);
  }

  private static String firstLine(String text) {
    for (String line : text.split("\\R")) {
      if (!line.isBlank()) {
        return ChatController.cut(line.trim(), MESSAGE_LENGTH);
      }
    }
    return "";
  }

  private static Result run(String executable, Map<String, String> environment, Path directory, List<String> args) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.environment().put("GH_PROMPT_DISABLED", "1");
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Path out = null;
    Path err = null;
    try {
      out = Files.createTempFile("tako-gh", ".out");
      err = Files.createTempFile("tako-gh", ".err");
      builder.redirectOutput(out.toFile());
      builder.redirectError(err.toFile());
      Process process;
      try {
        process = builder.start();
      } catch (IOException e) {
        return new Result(NOT_STARTED, "", String.valueOf(e.getMessage()));
      }
      process.getOutputStream().close();
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        return new Result(TIMED_OUT, "", "gh did not answer within " + TIMEOUT_SECONDS + " seconds");
      }
      return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
        Files.readString(err, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return new Result(TIMED_OUT, "", "Could not read gh's output: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(TIMED_OUT, "", "Interrupted");
    } finally {
      delete(out);
      delete(err);
    }
  }

  private static void delete(@Nullable Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.debug("Could not delete " + file, e);
    }
  }

  public enum Kind {
    /** gh could not be started. */
    MISSING,
    LOGGED_OUT,
    RATE_LIMITED,
    OTHER
  }

  /** Runs gh in a folder. */
  public interface Runner {
    Result run(Path directory, List<String> args);
  }

  /** @param exitCode the exit code, or {@link #NOT_STARTED} or {@link #TIMED_OUT} */
  public record Result(
    int exitCode,
    String out,
    String err
  ) {
  }

  public record Failure(
    Kind kind,
    String message
  ) {
  }
}
