package io.firebite.tako.settings;

import io.firebite.tako.TakoBundle;
import org.jetbrains.annotations.Nls;

public enum AuthMode {
  API_KEY("API key (ANTHROPIC_API_KEY)"),
  CLI_LOGIN("Claude CLI login");

  /** What the settings file stores. It predates translations, so it stays English. */
  private final String stored;

  AuthMode(String stored) {
    this.stored = stored;
  }

  public @Nls String label() {
    return switch (this) {
      case API_KEY -> TakoBundle.message("auth.api.key");
      case CLI_LOGIN -> TakoBundle.message("auth.cli.login");
    };
  }

  @Override
  public String toString() {
    return this.stored;
  }
}
