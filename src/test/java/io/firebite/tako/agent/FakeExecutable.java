package io.firebite.tako.agent;

import com.google.gson.Gson;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** A script that runs a test class's {@code main}, with Gson, as if it were an installed agent CLI. */
public final class FakeExecutable {
  private FakeExecutable() {
  }

  /** Writes the script, named {@code name.cmd} on Windows and {@code name} elsewhere. */
  public static Path write(String name, Class<?> mainClass) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    Path directory = Files.createTempDirectory("fake-" + name);
    String classpath = classes(mainClass) + File.pathSeparator + classes(Gson.class);
    if (isWindows()) {
      Path script = directory.resolve(name + ".cmd");
      Files.writeString(script, "@\"" + java + "\" -cp \"" + classpath + "\" " + mainClass.getName()
        + " %*\r\n", StandardCharsets.UTF_8);
      return script;
    }
    Path script = directory.resolve(name);
    Files.writeString(script, "#!/bin/sh\nexec '" + java + "' -cp '" + classpath + "' " + mainClass.getName()
      + " \"$@\"\n", StandardCharsets.UTF_8);
    script.toFile().setExecutable(true);
    return script;
  }

  /** The folder or jar the class was loaded from. */
  public static Path classes(Class<?> type) throws Exception {
    String resource = type.getName().replace('.', '/') + ".class";
    URL url = Objects.requireNonNull(type.getClassLoader().getResource(resource), resource);
    if ("jar".equals(url.getProtocol())) {
      String file = url.getPath();
      return Path.of(new URI(file.substring(0, file.indexOf("!/"))));
    }
    Path classFile = Path.of(url.toURI());
    Path root = classFile;
    for (int i = 0; i < type.getName().split("\\.").length; i++) {
      root = root.getParent();
    }
    return root;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
