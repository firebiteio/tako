package io.firebite.tako.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.Nullable;

public final class ApiKeyStore {
  private static final CredentialAttributes ATTRIBUTES = new CredentialAttributes(
    CredentialAttributesKt.generateServiceName("Tako", "ANTHROPIC_API_KEY"));

  private ApiKeyStore() {
  }

  public static @Nullable String load() {
    String key = PasswordSafe.getInstance().getPassword(ATTRIBUTES);
    return key == null || key.isBlank() ? null : key;
  }

  public static void save(@Nullable String key) {
    PasswordSafe.getInstance().setPassword(ATTRIBUTES, key == null || key.isBlank() ? null : key.trim());
  }
}
