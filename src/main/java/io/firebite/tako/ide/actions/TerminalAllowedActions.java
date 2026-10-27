package io.firebite.tako.ide.actions;

import com.intellij.terminal.frontend.view.TerminalAllowedActionsProvider;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Lets the Add to Tako shortcut through while the terminal, which sends other keys to the shell, has focus. */
public final class TerminalAllowedActions implements TerminalAllowedActionsProvider {

  @Override
  public @NotNull List<String> getActionIds() {
    return List.of("Tako.AddToChat");
  }
}
