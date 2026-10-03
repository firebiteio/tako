package io.firebite.tako.ui.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import io.firebite.tako.agent.AgentEvent;
import io.firebite.tako.ide.context.ContextItem;
import io.firebite.tako.json.Json;
import java.util.List;
import org.junit.Test;

public class PageCodecTest {

  @Test
  public void decodesAThreadOpWithNestedRecords() {
    PageRequest request = PageCodec.decode("{\"op\":\"send\",\"thread\":\"t1\",\"text\":\"hi\",\"turn\":3,"
      + "\"includeActiveFile\":true,\"context\":[{\"id\":\"c1\",\"kind\":\"selection\",\"label\":\"A.java:1-2\","
      + "\"text\":\"x\"}],\"images\":[{\"mediaType\":\"image/png\",\"data\":\"AAAA\",\"name\":\"a.png\"}]}");

    assertEquals("t1", request.thread());
    PageOp.Send send = (PageOp.Send) request.op();
    assertEquals(3, send.turn());
    assertTrue(send.includeActiveFile());
    assertEquals(ContextItem.Kind.SELECTION, send.context().get(0).kind());
    assertEquals("a.png", send.images().get(0).name());
  }

  @Test
  public void omittableFieldsMayBeMissing() {
    PageRequest request = PageCodec.decode("{\"op\":\"turnDiff\",\"thread\":\"t1\",\"turn\":2}");

    assertNull(((PageOp.TurnDiff) request.op()).path());
  }

  @Test
  public void rejectsOpsMissingARequiredField() {
    assertNull(PageCodec.decode("{\"op\":\"setModel\",\"thread\":\"t1\"}"));
  }

  @Test
  public void rejectsUnknownOps() {
    assertNull(PageCodec.decode("{\"op\":\"selfDestruct\"}"));
    assertNull(PageCodec.decode("not json"));
  }

  @Test
  public void encodesTheKindFromTheRecordNameAndLeavesNullsOut() {
    JsonObject json = PageCodec.encode(new PageMessage.ThreadRemoved("t9"));
    assertEquals("threadRemoved", Json.string(json, "kind"));
    assertEquals("t9", Json.string(json, "thread"));

    JsonObject context = PageCodec.encode(new PageMessage.Context(
      new ContextItem("c1", ContextItem.Kind.PROBLEMS, "2 problems", "..."), null), "t1");
    assertEquals("context", Json.string(context, "kind"));
    assertEquals("t1", Json.string(context, "thread"));
    assertEquals("problems", Json.string(Json.object(context, "item"), "kind"));
    assertFalse(context.has("prefill"));
  }

  @Test
  public void tagsAgentEventsWithTheirType() {
    JsonObject json = PageCodec.encode(new PageMessage.Replay(List.of(new AgentEvent.ToolFinished("t1", "ok", false,
      new AgentEvent.Change("a.txt", 2, 1, false)), new AgentEvent.Busy())), "t1");

    JsonObject first = json.getAsJsonArray("events").get(0).getAsJsonObject();
    assertEquals("toolFinished", Json.string(first, "type"));
    assertEquals(2, Json.number(Json.object(first, "change"), "added"));
    assertEquals("busy", Json.string(json.getAsJsonArray("events").get(1).getAsJsonObject(), "type"));
    JsonObject event = PageCodec.encode(new PageMessage.Event(new AgentEvent.Interrupted()), "t1");
    assertEquals("interrupted", Json.string(Json.object(event, "event"), "type"));
  }

  @Test
  public void decodesPermissionAnswers() {
    PageRequest request = PageCodec.decode("{\"op\":\"permission\",\"thread\":\"t1\",\"requestId\":\"r1\","
      + "\"allow\":true,\"interrupt\":false,\"answers\":{\"Which DB?\":\"Postgres\"},\"suggestion\":0}");

    PageOp.Permission permission = (PageOp.Permission) request.op();
    assertEquals("Postgres", permission.answers().get("Which DB?"));
    assertEquals(Integer.valueOf(0), permission.suggestion());
  }
}
