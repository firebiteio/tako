package io.firebite.tako.settings;

import static org.junit.Assert.assertEquals;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import org.junit.Test;

public class ChatSettingsTest {

  @Test
  public void readsSettingsSavedBeforeTheOtherAgents() throws Exception {
    ChatSettings.Data data = XmlSerializer.deserialize(JDOMUtil.load("""
      <TakoSettings>
        <option name="executablePath" value="C:/bin/claude.exe" />
        <option name="authMode" value="Claude CLI login" />
        <option name="model" value="opus" />
        <option name="permissionMode" value="plan" />
        <option name="effort" value="high" />
      </TakoSettings>"""), ChatSettings.Data.class);

    assertEquals("C:/bin/claude.exe", data.claudePath);
    assertEquals(AuthMode.CLI_LOGIN, data.authMode);
    assertEquals("opus", data.claudeModel);
    assertEquals("plan", data.claudeMode);
    assertEquals("high", data.claudeEffort);
    assertEquals("claude", data.defaultAgent);
  }

  @Test
  public void writesClaudeOptionsUnderTheirOldNames() {
    ChatSettings.Data data = new ChatSettings.Data();
    data.claudeModel = "sonnet";
    data.codexModel = "gpt";

    String xml = JDOMUtil.write(XmlSerializer.serialize(data));

    assertEquals(true, xml.contains("name=\"model\" value=\"sonnet\""));
    assertEquals(true, xml.contains("name=\"codexModel\" value=\"gpt\""));
  }
}
