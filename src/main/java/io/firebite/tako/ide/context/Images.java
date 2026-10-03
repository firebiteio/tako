package io.firebite.tako.ide.context;

import io.firebite.tako.agent.ImageAttachment;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;

/** Prepares images for the API, at most {@link #MAX_EDGE} px on the long edge and under {@link #MAX_BYTES}. */
public final class Images {
  /** Anthropic recommends at most 1568 px on the long edge. The server scales larger images down anyway. */
  public static final int MAX_EDGE = 1568;
  /** The API takes at most 5 MB per image, and base64 adds a third. */
  public static final int MAX_BYTES = 3_500_000;

  private Images() {
  }

  public static boolean isImageFile(@Nullable String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
      || lower.endsWith(".webp");
  }

  public static @Nullable ImageAttachment fromFile(Path file) throws IOException {
    String name = file.getFileName().toString();
    byte[] bytes = Files.readAllBytes(file);
    String mediaType = mediaType(name);
    BufferedImage image = ImageIO.read(file.toFile());
    if (image == null) {
      return bytes.length <= MAX_BYTES && mediaType != null ? encode(mediaType, bytes, name) : null;
    }
    boolean fits = image.getWidth() <= MAX_EDGE && image.getHeight() <= MAX_EDGE && bytes.length <= MAX_BYTES;
    if (fits && mediaType != null) {
      return encode(mediaType, bytes, name);
    }
    return fromImage(image, name);
  }

  public static ImageAttachment fromImage(Image source, String name) throws IOException {
    BufferedImage image = scaled(toBuffered(source));
    byte[] png = write(image, "png");
    if (png.length <= MAX_BYTES) {
      return encode("image/png", png, name);
    }
    BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = opaque.createGraphics();
    graphics.drawImage(image, 0, 0, Color.WHITE, null);
    graphics.dispose();
    return encode("image/jpeg", write(opaque, "jpg"), name);
  }

  static BufferedImage scaled(BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    int longEdge = Math.max(width, height);
    if (longEdge <= MAX_EDGE) {
      return image;
    }
    double factor = (double) MAX_EDGE / longEdge;
    int targetWidth = Math.max(1, (int) Math.round(width * factor));
    int targetHeight = Math.max(1, (int) Math.round(height * factor));
    BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = target.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
    graphics.dispose();
    return target;
  }

  private static BufferedImage toBuffered(Image source) {
    if (source instanceof BufferedImage buffered) {
      return buffered;
    }
    BufferedImage image = new BufferedImage(Math.max(1, source.getWidth(null)), Math.max(1, source.getHeight(null)),
      BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return image;
  }

  private static byte[] write(BufferedImage image, String format) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    if (!ImageIO.write(image, format, output)) {
      throw new IOException("No writer for " + format);
    }
    return output.toByteArray();
  }

  private static ImageAttachment encode(String mediaType, byte[] bytes, String name) {
    return new ImageAttachment(mediaType, Base64.getEncoder().encodeToString(bytes), name);
  }

  private static @Nullable String mediaType(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".png")) {
      return "image/png";
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (lower.endsWith(".gif")) {
      return "image/gif";
    }
    if (lower.endsWith(".webp")) {
      return "image/webp";
    }
    return null;
  }
}
