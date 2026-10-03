package io.firebite.tako.ui;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.Color;
import java.awt.Font;

/** The IDE's look as CSS custom properties, so the page follows the theme and editor scheme. */
final class WebTheme {
  /** Categorical chart colors in fixed order, validated for color-vision deficiency on light and dark surfaces. */
  private static final String[] SERIES_LIGHT = {"#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300",
    "#4a3aa7", "#e34948"};
  private static final String[] SERIES_DARK = {"#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300",
    "#9085e9", "#e66767"};

  private WebTheme() {
  }

  static String css() {
    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
    Color background = UIUtil.getPanelBackground();
    Color foreground = UIUtil.getLabelForeground();
    Color editorBackground = scheme.getDefaultBackground();
    Font uiFont = UIUtil.getLabelFont();
    boolean isDark = !JBColor.isBright();

    StringBuilder css = new StringBuilder(":root{");
    css.append("color-scheme:").append(isDark ? "dark" : "light").append(';');
    variable(css, "bg", background);
    variable(css, "fg", foreground);
    variable(css, "muted", UIUtil.getContextHelpForeground());
    variable(css, "border", JBColor.border());
    variable(css, "editor-bg", editorBackground);
    variable(css, "editor-fg", scheme.getDefaultForeground());
    variable(css, "link", JBUI.CurrentTheme.Link.Foreground.ENABLED);
    variable(css, "accent", JBUI.CurrentTheme.Focus.focusColor());
    variable(css, "selection", UIUtil.getListSelectionBackground(true));
    variable(css, "selection-fg", UIUtil.getListSelectionForeground(true));
    variable(css, "input-bg", UIUtil.getTextFieldBackground());
    variable(css, "error", JBColor.RED);
    variable(css, "ok", isDark ? new Color(0x5FB865) : new Color(0x2E8B3A));
    variable(css, "warn", isDark ? new Color(0xD9A343) : new Color(0xA86B00));
    variable(css, "added", isDark ? new Color(0x294436) : new Color(0xE6F4EA));
    variable(css, "removed", isDark ? new Color(0x4A2B2B) : new Color(0xFCE8E6));
    variable(css, "diff-add", isDark ? new Color(0x4ADE80) : new Color(0x16A34A));
    variable(css, "diff-del", isDark ? new Color(0xF87171) : new Color(0xDC2626));
    variable(css, "card", isDark ? blend(background, Color.WHITE, 0.04) : Color.WHITE);
    variable(css, "message", isDark ? blend(background, Color.WHITE, 0.06) : blend(background, Color.BLACK, 0.05));
    variable(css, "hover", isDark ? blend(background, Color.WHITE, 0.07) : blend(background, Color.BLACK, 0.05));
    variable(css, "soft-border", isDark ? blend(background, Color.WHITE, 0.09) : blend(background, Color.BLACK, 0.1));
    String[] series = isDark ? SERIES_DARK : SERIES_LIGHT;
    for (int i = 0; i < series.length; i++) {
      css.append("--series-").append(i + 1).append(':').append(series[i]).append(';');
    }
    Color primary = JBUI.CurrentTheme.Button.defaultButtonColorStart();
    variable(css, "primary", primary);
    variable(css, "primary-fg", JBUI.CurrentTheme.Button.defaultButtonForeground());
    highlight(css, scheme, "hl-keyword", DefaultLanguageHighlighterColors.KEYWORD);
    highlight(css, scheme, "hl-string", DefaultLanguageHighlighterColors.STRING);
    highlight(css, scheme, "hl-number", DefaultLanguageHighlighterColors.NUMBER);
    highlight(css, scheme, "hl-comment", DefaultLanguageHighlighterColors.LINE_COMMENT);
    highlight(css, scheme, "hl-function", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    highlight(css, scheme, "hl-class", DefaultLanguageHighlighterColors.CLASS_NAME);
    highlight(css, scheme, "hl-constant", DefaultLanguageHighlighterColors.CONSTANT);
    highlight(css, scheme, "hl-meta", DefaultLanguageHighlighterColors.METADATA);
    css.append("--font:'").append(uiFont.getFamily()).append("',system-ui,sans-serif;");
    css.append("--font-size:").append(uiFont.getSize()).append("px;");
    css.append("--mono:'").append(scheme.getEditorFontName()).append("',monospace;");
    css.append("--mono-size:").append(scheme.getEditorFontSize()).append("px;");
    return css.append('}').toString();
  }

  private static void highlight(StringBuilder css, EditorColorsScheme scheme, String name, TextAttributesKey key) {
    TextAttributes attributes = scheme.getAttributes(key);
    Color color = attributes == null ? null : attributes.getForegroundColor();
    variable(css, name, color != null ? color : scheme.getDefaultForeground());
  }

  private static void variable(StringBuilder css, String name, Color color) {
    css.append("--").append(name).append(':').append(hex(color)).append(';');
  }

  private static Color blend(Color base, Color overlay, double amount) {
    return new Color(
      (int) Math.round(base.getRed() + (overlay.getRed() - base.getRed()) * amount),
      (int) Math.round(base.getGreen() + (overlay.getGreen() - base.getGreen()) * amount),
      (int) Math.round(base.getBlue() + (overlay.getBlue() - base.getBlue()) * amount));
  }

  private static String hex(Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
  }
}
