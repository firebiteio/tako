package io.firebite.tako.ide.context;

import io.firebite.tako.chat.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.Nullable;

/** A comment on lines of a file or diff, as prompt context that tells the agent what was commented on. */
public final class ReviewComment {
  static final String CHANGED_NOTE = "The file has changed since this diff was shown, so these lines may have moved.";
  static final String GONE_NOTE = "The file no longer exists.";
  private static final int LABEL_COMMENT = 40;

  private ReviewComment() {
  }

  /**
   * The editor's lines from {@code first} to {@code last}, 0-based and in either order, with their numbers in the two
   * versions. Lines in neither version are left out.
   *
   * @param toBefore the line's 0-based number in the old version, -1 when it is not in it
   * @param toAfter  the line's 0-based number in the new version, -1 when it is not in it
   */
  public static List<Line> lines(int first, int last, IntUnaryOperator toBefore, IntUnaryOperator toAfter,
    IntFunction<String> textOf) {
    List<Line> lines = new ArrayList<>();
    for (int line = Math.min(first, last); line <= Math.max(first, last); line++) {
      int before = toBefore.applyAsInt(line);
      int after = toAfter.applyAsInt(line);
      if (before >= 0 || after >= 0) {
        String text = textOf.apply(line);
        lines.add(new Line(before + 1, after + 1, text.endsWith("\r") ? text.substring(0, text.length() - 1) : text));
      }
    }
    return lines;
  }

  /**
   * The comment with the lines it is on, numbered in the new version when any of them is in it. Lines of both versions
   * show as a diff. Null for a blank comment or without lines.
   *
   * @param note a last line for the agent, such as {@link #note}
   */
  public static @Nullable ContextItem item(ReviewTarget target, List<Line> lines, String comment,
    @Nullable String note) {
    String trimmed = comment.strip();
    if (trimmed.isEmpty() || lines.isEmpty()) {
      return null;
    }
    Range range = Range.of(lines);
    ReviewTarget.Version version = isOld(lines) ? target.before() : target.after();
    StringBuilder header = new StringBuilder("Review comment on ").append(target.path())
      .append(range.start() == range.end() ? ", line " : ", lines ").append(range);
    if (version.description() != null) {
      header.append(" (").append(version.description()).append(')');
    }
    boolean isMixed = range.isMixed();
    if (isMixed) {
      header.append(", as a diff: - only in the old version, + only in the new one");
    }
    String code = code(lines, isMixed);
    if (code.length() > IdeContext.MAX_EXCERPT) {
      header.append(" (first ").append(IdeContext.MAX_EXCERPT).append(" of ").append(code.length())
        .append(" characters)");
      int cut = IdeContext.MAX_EXCERPT;
      if (Character.isHighSurrogate(code.charAt(cut - 1))) {
        cut--;
      }
      code = code.substring(0, cut);
    }
    String fence = IdeContext.fence(code);
    StringBuilder text = new StringBuilder(header).append(":\n").append(fence)
      .append(isMixed ? "diff" : language(target.path())).append('\n').append(code).append('\n').append(fence)
      .append("\nComment: ").append(trimmed);
    if (note != null) {
      text.append('\n').append(note);
    }
    String label = place(target, lines) + ": " + Texts.cut(trimmed.replaceAll("\\s+", " "), LABEL_COMMENT);
    return ContextItem.create(ContextItem.Kind.REVIEW, label, text.toString());
  }

  /** The file's name and the lines, such as {@code Foo.java:12-14}, numbered as {@link #item} numbers them. */
  public static String place(ReviewTarget target, List<Line> lines) {
    String name = target.path().substring(target.path().lastIndexOf('/') + 1);
    return lines.isEmpty() ? name : name + ":" + Range.of(lines);
  }

  /**
   * The item is a review comment made in the diff of an edit a permission prompt proposes.
   *
   * @param path the edited file, null for an edit to any file. Compared ignoring case and separators.
   */
  public static boolean isOnProposedEdit(ContextItem item, @Nullable String path) {
    if (item.kind() != ContextItem.Kind.REVIEW) {
      return false;
    }
    int newline = item.text().indexOf('\n');
    String header = newline < 0 ? item.text() : item.text().substring(0, newline);
    boolean isProposed = header.contains(" (" + ReviewTarget.PROPOSED_BEFORE + ")")
      || header.contains(" (" + ReviewTarget.PROPOSED_AFTER + ")");
    if (!isProposed || path == null) {
      return isProposed;
    }
    String prefix = "Review comment on " + path.replace('\\', '/') + ", line";
    return header.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  /** None of the lines is in the new version, so the comment is on the old one. */
  public static boolean isOld(List<Line> lines) {
    for (Line line : lines) {
      if (line.after() > 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * What the agent should know about a version that is a file on disk: whether it still is what the diff shows.
   *
   * @param shown  the version as the diff shows it
   * @param exists the file exists
   * @param onDisk the file's text, null when it could not be read
   */
  public static @Nullable String note(String shown, boolean exists, @Nullable String onDisk) {
    if (!exists) {
      return shown.isEmpty() ? null : GONE_NOTE;
    }
    if (onDisk == null || normalized(onDisk).equals(normalized(shown))) {
      return null;
    }
    return CHANGED_NOTE;
  }

  private static String normalized(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String code(List<Line> lines, boolean isDiff) {
    StringBuilder code = new StringBuilder();
    for (Line line : lines) {
      if (!code.isEmpty()) {
        code.append('\n');
      }
      if (isDiff) {
        code.append(line.after() == 0 ? '-' : line.before() == 0 ? '+' : ' ');
      }
      code.append(line.text());
    }
    return code.toString();
  }

  private static String language(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * A commented line.
   *
   * @param before its 1-based number in the old version, 0 when it is not in it
   * @param after  its 1-based number in the new version, 0 when it is not in it
   */
  public record Line(
    int before,
    int after,
    String text
  ) {
  }

  /**
   * The first and last line numbers, in the new version when any line is in it, else in the old one.
   *
   * @param isMixed some lines are only in the old version and some in the new one
   */
  private record Range(
    int start,
    int end,
    boolean isMixed
  ) {
    static Range of(List<Line> lines) {
      if (isOld(lines)) {
        return new Range(lines.get(0).before(), lines.get(lines.size() - 1).before(), false);
      }
      int start = 0;
      int end = 0;
      boolean hasOldOnly = false;
      for (Line line : lines) {
        if (line.after() > 0) {
          start = start == 0 ? line.after() : start;
          end = line.after();
        } else {
          hasOldOnly = true;
        }
      }
      return new Range(start, end, hasOldOnly);
    }

    @Override
    public String toString() {
      return this.start == this.end ? String.valueOf(this.start) : this.start + "-" + this.end;
    }
  }
}
