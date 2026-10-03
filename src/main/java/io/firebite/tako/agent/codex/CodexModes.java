package io.firebite.tako.agent.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentInfo;
import io.firebite.tako.json.Json;
import java.util.ArrayList;
import java.util.List;

/** Codex's approval policy and sandbox, combined into the modes its own CLI offers. */
final class CodexModes {
  static final String DEFAULT = "auto";

  private CodexModes() {
  }

  static List<Mode> modes() {
    return List.of(
      new Mode("read-only", TakoBundle.message("mode.codex.read.only"),
        TakoBundle.message("mode.codex.read.only.description"),
        "on-request", "read-only"),
      new Mode(DEFAULT, TakoBundle.message("mode.codex.auto"), TakoBundle.message("mode.codex.auto.description"),
        "on-request",
        "workspace-write"),
      new Mode("full-access", TakoBundle.message("mode.codex.full.access"),
        TakoBundle.message("mode.codex.full.access.description"), "never", "danger-full-access"));
  }

  /** The mode of that value, else {@link #DEFAULT}. */
  static Mode find(String value) {
    List<Mode> modes = modes();
    Mode fallback = modes.get(1);
    for (Mode mode : modes) {
      if (mode.value().equals(value)) {
        return mode;
      }
    }
    return fallback;
  }

  static List<AgentInfo.Mode> options() {
    List<AgentInfo.Mode> options = new ArrayList<>();
    for (Mode mode : modes()) {
      options.add(new AgentInfo.Mode(mode.value(), mode.label(), mode.description()));
    }
    return options;
  }

  /**
   * @param approvalPolicy when Codex asks before acting
   * @param sandbox        what its commands may touch
   */
  record Mode(
    String value,
    String label,
    String description,
    String approvalPolicy,
    String sandbox
  ) {

    /** The {@code SandboxPolicy} a turn takes. */
    JsonObject sandboxPolicy() {
      return switch (this.sandbox) {
        case "read-only" -> {
          JsonObject policy = Json.of("type", "readOnly");
          policy.addProperty("networkAccess", false);
          yield policy;
        }
        case "danger-full-access" -> Json.of("type", "dangerFullAccess");
        default -> {
          JsonObject policy = Json.of("type", "workspaceWrite");
          policy.add("writableRoots", new JsonArray());
          policy.addProperty("networkAccess", false);
          policy.addProperty("excludeTmpdirEnvVar", false);
          policy.addProperty("excludeSlashTmp", false);
          yield policy;
        }
      };
    }
  }
}
