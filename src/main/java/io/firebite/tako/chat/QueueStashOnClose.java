package io.firebite.tako.chat;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import org.jetbrains.annotations.NotNull;

/** Puts every thread's queued prompts in the prompt stash while the closing project's services still work. */
public final class QueueStashOnClose implements ProjectCloseListener {

  @Override
  public void projectClosing(@NotNull Project project) {
    ChatService service = project.getServiceIfCreated(ChatService.class);
    if (service != null) {
      service.stashQueued();
    }
  }
}
