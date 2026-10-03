package io.firebite.tako.agent.claude.transcript;

import com.google.gson.JsonObject;
import io.firebite.tako.json.Json;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the lines appended to a transcript since the last read. A partial last line waits for its newline. */
public final class TranscriptTail {
  private final Path file;
  private long offset;
  private byte[] pending = new byte[0];

  public TranscriptTail(Path file) {
    this.file = file;
  }

  public synchronized List<JsonObject> poll() throws IOException {
    List<JsonObject> lines = new ArrayList<>();
    if (!Files.isRegularFile(this.file)) {
      return lines;
    }
    byte[] chunk;
    try (RandomAccessFile reader = new RandomAccessFile(this.file.toFile(), "r")) {
      long length = reader.length();
      if (length <= this.offset) {
        return lines;
      }
      reader.seek(this.offset);
      chunk = new byte[(int) (length - this.offset)];
      reader.readFully(chunk);
      this.offset = length;
    }
    byte[] data = new byte[this.pending.length + chunk.length];
    System.arraycopy(this.pending, 0, data, 0, this.pending.length);
    System.arraycopy(chunk, 0, data, this.pending.length, chunk.length);
    int start = 0;
    for (int i = 0; i < data.length; i++) {
      if (data[i] == '\n') {
        JsonObject line = Json.parseObject(new String(data, start, i - start, StandardCharsets.UTF_8));
        if (line != null) {
          lines.add(line);
        }
        start = i + 1;
      }
    }
    this.pending = new byte[data.length - start];
    System.arraycopy(data, start, this.pending, 0, this.pending.length);
    return lines;
  }
}
