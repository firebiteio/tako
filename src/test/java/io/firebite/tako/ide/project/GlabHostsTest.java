package io.firebite.tako.ide.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class GlabHostsTest {
  private static final String CONFIG = """
    git_protocol: ssh
    host: gitlab.com
    # hosts: not.this
    hosts:
        gitlab.com:
            api_protocol: https
            api_host: gitlab.com
            custom_headers: []
        Git.Example.com:
            use_keyring: "true"
            user: someone
        "quoted.example.com":
            api_host: quoted.example.com
    aliases:
        mr: merge request
    """;

  @Test
  public void hostsAreTheKeysUnderHosts() {
    assertEquals(List.of("gitlab.com", "git.example.com", "quoted.example.com"),
      List.copyOf(GlabHosts.parse(CONFIG)));
  }

  @Test
  public void configWithoutHostsHasNone() {
    assertTrue(GlabHosts.parse("host: gitlab.com\n").isEmpty());
    assertTrue(GlabHosts.parse("").isEmpty());
  }

  @Test
  public void readsTheConfigFolderAndTheHostVariable() throws Exception {
    Path folder = Files.createTempDirectory("tako-glab");
    Path config = folder.resolve("config.yml");
    Files.writeString(config, CONFIG);
    try {
      Set<String> hosts = GlabHosts.read(Map.of("GLAB_CONFIG_DIR", folder.toString(), "GITLAB_HOST",
        "https://Lab.Example.org/"));

      assertTrue(hosts.containsAll(Set.of("lab.example.org", "gitlab.com", "git.example.com", "quoted.example.com")));
    } finally {
      Files.deleteIfExists(config);
      Files.deleteIfExists(folder);
    }
  }
}
