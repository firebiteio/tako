package io.firebite.tako.ui;

import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.messages.MessageBusConnection;
import io.firebite.tako.ui.bridge.PageCodec;
import io.firebite.tako.ui.bridge.PageMessage;
import io.firebite.tako.ui.bridge.PageOp;
import io.firebite.tako.ui.bridge.PageRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

/**
 * The chat page in JCEF. The page talks to the IDE through a JS query, the IDE to the page through
 * {@code window.tako.receive}.
 * Messages posted before the page is ready wait for its {@link PageOp.Ready}.
 */
public final class ChatPanel implements Disposable {
  /** The page's script, bundled from {@code webview/src} by the {@code webviewBundle} Gradle task. */
  private static final String APP_SCRIPT = "app.js";

  private final JBCefBrowser browser;
  private final JBCefJSQuery query;
  private final List<String> queue = new ArrayList<>();
  private Consumer<PageRequest> handler = request -> {
  };
  private boolean isReady;

  public ChatPanel(Disposable parent) {
    Disposer.register(parent, this);
    this.browser = new JBCefBrowser();
    Disposer.register(this, this.browser);
    this.query = JBCefJSQuery.create((JBCefBrowserBase) this.browser);
    Disposer.register(this, this.query);
    this.query.addHandler(payload -> {
      this.onPageMessage(payload);
      return null;
    });
    this.browser.getJBCefClient().addRequestHandler(new CefRequestHandlerAdapter() {
      @Override
      public boolean onBeforeBrowse(CefBrowser cefBrowser, CefFrame frame, CefRequest request, boolean isUserGesture,
                                    boolean isRedirect) {
        return frame.isMain() && openExternally(request.getURL());
      }
    }, this.browser.getCefBrowser());
    this.browser.getJBCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
      @Override
      public boolean onBeforePopup(CefBrowser cefBrowser, CefFrame frame, String targetUrl, String targetFrameName) {
        openExternally(targetUrl);
        return true;
      }
    }, this.browser.getCefBrowser());
    this.browser.loadHTML(this.buildPage());

    MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect(this);
    connection.subscribe(LafManagerListener.TOPIC, (LafManagerListener) source -> this.postTheme());
    connection.subscribe(EditorColorsManager.TOPIC, (EditorColorsListener) scheme -> this.postTheme());
  }

  public JComponent getComponent() {
    return this.browser.getComponent();
  }

  public void setHandler(Consumer<PageRequest> handler) {
    this.handler = handler;
  }

  /** Safe from any thread. */
  public void post(JsonObject message) {
    String json = message.toString();
    synchronized (this.queue) {
      if (!this.isReady) {
        this.queue.add(json);
        return;
      }
    }
    this.execute(json);
  }

  @Override
  public void dispose() {
  }

  private void onPageMessage(String payload) {
    PageRequest request = PageCodec.decode(payload);
    if (request == null) {
      return;
    }
    if (request.op() instanceof PageOp.Ready) {
      this.flushQueue();
    }
    this.handler.accept(request);
  }

  private void flushQueue() {
    List<String> pending;
    synchronized (this.queue) {
      this.isReady = true;
      pending = new ArrayList<>(this.queue);
      this.queue.clear();
    }
    for (String json : pending) {
      this.execute(json);
    }
  }

  private void execute(String json) {
    this.browser.getCefBrowser().executeJavaScript("window.tako.receive(" + json + ");",
      this.browser.getCefBrowser().getURL(), 0);
  }

  /** Opens web pages in the system browser. Shown in the panel they would replace the chat with no way back. */
  private static boolean openExternally(String url) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
      return false;
    }
    BrowserUtil.browse(url);
    return true;
  }

  private void postTheme() {
    this.post(PageCodec.encode(new PageMessage.Theme(WebTheme.css())));
  }

  private String buildPage() {
    String bridge = "window.sendToIde = function(payload) {" + this.query.inject("payload") + "};";
    return PageBundle.fill(resource("index.html"))
      .replace("<html lang=\"en\">", "<html lang=\"" + PageBundle.languageTag() + "\">")
      .replace("/*THEME*/", WebTheme.css())
      .replace("/*STRINGS*/", "window.takoStrings = " + escapeScript(PageBundle.json()) + ";")
      .replace("/*STYLE*/", resource("style.css"))
      .replace("/*BRIDGE*/", bridge)
      .replace("/*APP*/", escapeScript(resource(APP_SCRIPT)));
  }

  private static String escapeScript(String script) {
    return script.replace("</script", "<\\/script");
  }

  private static String resource(String name) {
    try (InputStream stream = ChatPanel.class.getResourceAsStream("/webview/" + name)) {
      if (stream == null) {
        throw new IllegalStateException("Missing webview resource " + name);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read webview resource " + name, e);
    }
  }
}
