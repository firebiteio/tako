package io.firebite.tako.ui.bridge.message.workspace;

import io.firebite.tako.ide.project.ProjectIcons;
import org.jetbrains.annotations.Nullable;

/** @param primary the IDE project, which cannot be removed */
public record ProjectItem(
  String path,
  String name,
  boolean collapsed,
  boolean primary,
  @Nullable ProjectIcons.Icon icon,
  @Nullable ProjectIcons.Icon iconDark
) {
}
