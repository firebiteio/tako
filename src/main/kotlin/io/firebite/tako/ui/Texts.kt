package io.firebite.tako.ui

import com.intellij.DynamicBundle
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat's texts from `messages/TakoPageBundle.properties`, in the IDE's language. Without a fallback, an English IDE
 * gets the English texts even when the system language has a translation.
 */
private val bundle: ResourceBundle = ResourceBundle.getBundle("messages.TakoPageBundle", DynamicBundle.getLocale(),
  ChatPanel::class.java.classLoader, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))

/** The locale the texts are in, for dates and lists. */
val textLocale: Locale = DynamicBundle.getLocale()

private val formats = ConcurrentHashMap<String, MessageFormat>()

/** The text of a key. Keys are literal so tests can find them. Arguments follow [MessageFormat]. */
fun t(key: String, vararg args: Any): String {
  if (args.isEmpty()) {
    return bundle.getString(key)
  }
  val format = formats.computeIfAbsent(key) { MessageFormat(bundle.getString(it), textLocale) }
  return synchronized(format) { format.format(args) }
}
