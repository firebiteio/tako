package io.firebite.tako.agent;

/** A file before and after a change. */
public record EditPreview(
  String filePath,
  String before,
  String after
) {
}
