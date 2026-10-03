package io.firebite.tako.ide.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.ImageAttachment;
import io.firebite.tako.json.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.Test;

public class ImagesTest {

  private static BufferedImage decode(ImageAttachment attachment) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(attachment.data())));
  }

  @Test
  public void largeImageIsScaledToTheLongEdgeLimit() throws Exception {
    ImageAttachment attachment = Images.fromImage(new BufferedImage(3136, 1000, BufferedImage.TYPE_INT_RGB),
      "wide.png");

    BufferedImage result = decode(attachment);
    assertEquals("image/png", attachment.mediaType());
    assertEquals(Images.MAX_EDGE, result.getWidth());
    assertEquals(500, result.getHeight());
  }

  @Test
  public void noisyImageFallsBackToJpegUnderTheSizeLimit() throws Exception {
    BufferedImage noise = new BufferedImage(1500, 1500, BufferedImage.TYPE_INT_RGB);
    Random random = new Random(1);
    for (int y = 0; y < noise.getHeight(); y++) {
      for (int x = 0; x < noise.getWidth(); x++) {
        noise.setRGB(x, y, random.nextInt());
      }
    }

    ImageAttachment attachment = Images.fromImage(noise, "noise.png");

    assertEquals("image/jpeg", attachment.mediaType());
    assertTrue(Base64.getDecoder().decode(attachment.data()).length <= Images.MAX_BYTES);
  }

  @Test
  public void smallFileIsSentUnchanged() throws Exception {
    Path file = Files.createTempFile("small", ".png");
    ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", file.toFile());

    ImageAttachment attachment = Images.fromFile(file);

    assertEquals("image/png", attachment.mediaType());
    assertEquals(Base64.getEncoder().encodeToString(Files.readAllBytes(file)), attachment.data());
  }

  @Test
  public void contentBlockMatchesTheMessagesApi() {
    JsonObject block = new ImageAttachment("image/png", "AAAA", "a.png").toContentBlock();

    assertEquals("image", Json.string(block, "type"));
    assertEquals("base64", Json.string(Json.object(block, "source"), "type"));
    assertEquals("image/png", Json.string(Json.object(block, "source"), "media_type"));
    assertEquals("AAAA", Json.string(Json.object(block, "source"), "data"));
  }
}
