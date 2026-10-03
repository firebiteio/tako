package io.firebite.tako.agent;

import com.google.gson.JsonObject;
import io.firebite.tako.json.Json;

/** An image sent with a prompt, base64 encoded as the Messages API takes it. */
public record ImageAttachment(
  String mediaType,
  String data,
  String name
) {
  private static final String IMAGE_MEDIA_PREFIX = "image/";

  /** False when the page sent something the API does not take as an image. */
  public boolean isValid() {
    return this.mediaType != null && this.data != null && this.mediaType.startsWith(IMAGE_MEDIA_PREFIX);
  }

  /** The {@code image} content block of a user message. */
  public JsonObject toContentBlock() {
    JsonObject source = Json.of("type", "base64");
    source.addProperty("media_type", this.mediaType);
    source.addProperty("data", this.data);
    JsonObject block = Json.of("type", "image");
    block.add("source", source);
    return block;
  }
}
