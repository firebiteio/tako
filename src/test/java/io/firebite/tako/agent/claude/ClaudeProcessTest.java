package io.firebite.tako.agent.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.FakeExecutable;
import io.firebite.tako.agent.claude.protocol.CliMessage;
import io.firebite.tako.json.Json;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ClaudeProcessTest {
  private static final long TIMEOUT_SECONDS = 20;

  private static ClaudeProcess startFake(Recorder recorder) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path classes = FakeExecutable.classes(FakeClaude.class);
    ClaudeLaunch launch = new ClaudeLaunch(
      List.of(java.toString(), "-cp", classes.toString(), FakeClaude.class.getName()),
      Map.copyOf(System.getenv()));
    return ClaudeProcess.start(launch, Path.of("."), recorder);
  }

  private static CliMessage nextOfType(Recorder recorder, CliMessage.Type type) throws InterruptedException {
    while (true) {
      CliMessage message = recorder.messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (message == null) {
        throw new AssertionError("No " + type + " message");
      }
      if (message.type() == type) {
        return message;
      }
    }
  }

  @Test
  public void initializeResolvesWithTheControlResponse() throws Exception {
    Recorder recorder = new Recorder();
    ClaudeProcess claude = startFake(recorder);

    JsonObject response = claude.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertEquals("compact", Json.string(Json.array(response, "commands").get(0).getAsJsonObject(), "name"));
    claude.destroy();
  }

  @Test
  public void permissionRoundTripReachesTheCli() throws Exception {
    Recorder recorder = new Recorder();
    ClaudeProcess claude = startFake(recorder);

    claude.sendUserText("write a.txt");
    String request = recorder.controlRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals("perm_1 Write", request);
    JsonObject allow = Json.of("behavior", "allow");
    claude.respond("perm_1", allow);

    assertEquals("allowed", Json.string(nextOfType(recorder, CliMessage.Type.RESULT).json(), "result"));
    claude.destroy();
  }

  @Test
  public void errorResponseFailsTheRequest() throws Exception {
    Recorder recorder = new Recorder();
    ClaudeProcess claude = startFake(recorder);

    try {
      claude.request(Json.of("subtype", "bogus")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      throw new AssertionError("Expected failure");
    } catch (ExecutionException e) {
      assertTrue(e.getCause().getMessage().contains("bogus"));
    }
    claude.destroy();
  }

  @Test
  public void closingStdinReportsExitWithStderr() throws Exception {
    Recorder recorder = new Recorder();
    ClaudeProcess claude = startFake(recorder);

    claude.destroy();

    assertTrue(recorder.exited.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  private static final class Recorder implements ClaudeProcessListener {
    final BlockingQueue<CliMessage> messages = new LinkedBlockingQueue<>();
    final BlockingQueue<String> controlRequests = new LinkedBlockingQueue<>();
    final CountDownLatch exited = new CountDownLatch(1);
    volatile String stderr = "";

    @Override
    public void onMessage(CliMessage message) {
      this.messages.add(message);
    }

    @Override
    public void onPermissionRequest(String requestId, JsonObject request) {
      this.controlRequests.add(requestId + " " + Json.string(request, "tool_name"));
    }

    @Override
    public void onExit(int exitCode, String stderrTail) {
      this.stderr = stderrTail;
      this.exited.countDown();
    }
  }
}
