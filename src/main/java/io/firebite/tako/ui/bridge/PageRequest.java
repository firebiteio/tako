package io.firebite.tako.ui.bridge;

import org.jetbrains.annotations.Nullable;

/** One op from the page. @param thread the thread a {@link PageOp.ThreadOp} is for */
public record PageRequest(
  @Nullable String thread,
  PageOp op
) {
}
