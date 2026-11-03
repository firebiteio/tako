package io.firebite.tako.ide.project;

import org.jetbrains.annotations.Nullable;

/** Where a repository is hosted, with the CLI Tako asks there and how its pull or merge requests are numbered. */
public enum Forge {
  GITHUB("GitHub", "gh", '#'),
  GITLAB("GitLab", "glab", '!');

  private final String displayName;
  private final String cli;
  private final char sign;

  Forge(String displayName, String cli, char sign) {
    this.displayName = displayName;
    this.cli = cli;
    this.sign = sign;
  }

  /** The forge whose pull or merge requests are numbered like {@code number}, e.g. {@code #12} or {@code !7}. */
  public static @Nullable Forge ofNumber(String number) {
    for (Forge forge : values()) {
      if (!number.isEmpty() && number.charAt(0) == forge.sign) {
        return forge;
      }
    }
    return null;
  }

  /** {@code GitHub} or {@code GitLab}. */
  public String displayName() {
    return this.displayName;
  }

  /** The CLI's executable, {@code gh} or {@code glab}. */
  public String cli() {
    return this.cli;
  }

  /** What a pull or merge request number starts with: {@code #12} on GitHub, {@code !7} on GitLab. */
  public char sign() {
    return this.sign;
  }
}
