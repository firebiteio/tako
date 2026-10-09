package io.firebite.tako.ide.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class ProjectIconsTest {
  private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";

  @Test
  public void ideIconWinsOverFavicon() throws Exception {
    Path folder = Files.createTempDirectory("project");
    Files.createDirectories(folder.resolve(".idea"));
    Files.writeString(folder.resolve(".idea/icon.svg"), SVG, StandardCharsets.UTF_8);
    Files.writeString(folder.resolve(".idea/icon_dark.svg"), SVG, StandardCharsets.UTF_8);
    Files.createDirectories(folder.resolve("public"));
    Files.write(folder.resolve("public/favicon.ico"), new byte[]{0, 0, 1, 0});

    ProjectIcons.Icons icons = ProjectIcons.find(folder);

    assertEquals(folder.resolve(".idea/icon.svg"), icons.light().file());
    assertArrayEquals(SVG.getBytes(StandardCharsets.UTF_8), icons.light().bytes());
    assertTrue(icons.light().isSvg());
    assertEquals(folder.resolve(".idea/icon_dark.svg"), icons.dark().file());
  }

  @Test
  public void faviconIsTheFallback() throws Exception {
    Path folder = Files.createTempDirectory("project");
    Files.createDirectories(folder.resolve("public"));
    Files.write(folder.resolve("public/favicon.ico"), new byte[]{0, 0, 1, 0});

    ProjectIcons.Icons icons = ProjectIcons.find(folder);

    assertEquals(folder.resolve("public/favicon.ico"), icons.light().file());
    assertArrayEquals(new byte[]{0, 0, 1, 0}, icons.light().bytes());
    assertFalse(icons.light().isSvg());
    assertNull(icons.dark());
  }

  @Test
  public void hugeOrMissingIconsAreIgnored() throws Exception {
    Path folder = Files.createTempDirectory("project");
    Files.write(folder.resolve("favicon.png"), new byte[(int) ProjectIcons.MAX_BYTES + 1]);

    ProjectIcons.Icons icons = ProjectIcons.find(folder);

    assertNull(icons.light());
    assertNull(icons.dark());
  }
}
