package io.firebite.tako.ide;

import com.intellij.openapi.application.ApplicationManager;

/** Hands work to the EDT or to a pooled background thread. */
public final class Async {
  private Async() {
  }

  public static void onEdt(Runnable task) {
    ApplicationManager.getApplication().invokeLater(task);
  }

  public static void inBackground(Runnable task) {
    ApplicationManager.getApplication().executeOnPooledThread(task);
  }
}
