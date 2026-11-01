package io.firebite.tako.ide.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Temporary git repositories for tests, with an environment that ignores the user's global and system git config, so
 * signing, global hooks or templates cannot change what a test sees.
 */
public final class TestRepos {
  public static final Map<String, String> ENVIRONMENT = environment();

  private TestRepos() {
  }

  /** A new repository on branch {@code main} with an identity, without commits. */
  public static Path repository() throws Exception {
    Path repository = Files.createTempDirectory("tako-repo").toRealPath();
    git(repository, "init", "-q", "-b", "main");
    git(repository, "config", "user.email", "test@example.com");
    git(repository, "config", "user.name", "Test");
    git(repository, "config", "core.autocrlf", "false");
    return repository;
  }

  /** A repository with one commit of {@code a.txt}. */
  public static Path committed() throws Exception {
    Path repository = repository();
    write(repository, "a.txt", "one\n");
    git(repository, "add", ".");
    git(repository, "commit", "-q", "-m", "base");
    return repository;
  }

  /** A bare repository to push to, added to {@code repository} as {@code name}. */
  public static Path addRemote(Path repository, String name) throws Exception {
    Path bare = Files.createTempDirectory("tako-remote").toRealPath();
    git(bare, "init", "-q", "--bare", "-b", "main");
    git(repository, "remote", "add", name, bare.toString());
    return bare;
  }

  /** Installs a hook that runs {@code script} in sh. */
  public static void hook(Path repository, String name, String script) throws IOException {
    Path file = repository.resolve(".git/hooks").resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "#!/bin/sh\n" + script + "\n", StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException ignored) {
    }
  }

  public static void write(Path repository, String path, String text) throws IOException {
    Path file = repository.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /** Runs git and returns what it printed. Fails the test when git fails. */
  public static String git(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false"));
    command.addAll(List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().putAll(ENVIRONMENT);
    Process process = builder.start();
    process.getOutputStream().close();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new AssertionError("git " + String.join(" ", arguments) + " failed: " + output);
    }
    return output;
  }

  private static Map<String, String> environment() {
    Map<String, String> environment = new HashMap<>(System.getenv());
    try {
      Path config = Files.createTempFile("tako-gitconfig", "");
      environment.put("GIT_CONFIG_GLOBAL", config.toString());
      environment.put("GIT_CONFIG_NOSYSTEM", "1");
      environment.put("GIT_CEILING_DIRECTORIES", Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        .toString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return Map.copyOf(environment);
  }
}
