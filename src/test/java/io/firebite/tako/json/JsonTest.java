package io.firebite.tako.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import org.junit.Test;

public class JsonTest {
  private static final JsonObject OBJECT = Json.parseObject(
    "{\"text\":\"a\",\"count\":7,\"flag\":false,\"list\":[\"x\",1,{\"y\":2},[]],\"nested\":{\"k\":\"v\"}}");

  @Test
  public void parsesArraysAndRejectsOtherText() {
    JsonArray array = Json.parseArray("[1,{\"a\":1}]");
    assertEquals(2, array.size());
    assertNull(Json.parseArray("{\"a\":1}"));
    assertNull(Json.parseArray("[1,"));
    assertNull(Json.parseArray(" "));
    assertNull(Json.parseArray(null));
    assertNull(Json.parseObject("[1]"));
  }

  @Test
  public void castsElementsByShape() {
    assertEquals("v", Json.string(Json.asObject(OBJECT.get("nested")), "k"));
    assertNull(Json.asObject(OBJECT.get("text")));
    assertNull(Json.asObject(null));
    assertEquals("7", Json.asString(OBJECT.get("count")));
    assertNull(Json.asString(OBJECT.get("nested")));
    assertNull(Json.asString(null));
  }

  @Test
  public void readsOnlyMatchingArrayElements() {
    assertEquals(List.of("x", "1"), Json.strings(OBJECT, "list"));
    assertTrue(Json.strings(OBJECT, "missing").isEmpty());
    assertEquals(1, Json.objects(Json.array(OBJECT, "list")).size());
    assertTrue(Json.objects((JsonArray) null).isEmpty());
  }

  @Test
  public void readsOptionalValuesAsNullWhenAbsentOrMistyped() {
    assertEquals(Boolean.FALSE, Json.optionalBool(OBJECT, "flag"));
    assertNull(Json.optionalBool(OBJECT, "missing"));
    assertNull(Json.optionalBool(OBJECT, "nested"));
    assertEquals(Integer.valueOf(7), Json.optionalInt(OBJECT, "count"));
    assertNull(Json.optionalInt(OBJECT, "text"));
    assertNull(Json.optionalInt(null, "count"));
  }

  @Test
  public void objectsByKeyMatchesObjectsOfArray() {
    JsonObject holder = new JsonObject();
    JsonArray array = new JsonArray();
    array.add(new JsonPrimitive(1));
    array.add(new JsonObject());
    holder.add("items", array);
    assertEquals(Json.objects(array), Json.objects(holder, "items"));
    assertFalse(Json.objects(holder, "items").isEmpty());
  }
}
