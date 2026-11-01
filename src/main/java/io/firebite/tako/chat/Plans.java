package io.firebite.tako.chat;

import io.firebite.tako.agent.claude.ClaudeBackend;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** What a plan the agent proposed turns into: a thread implementing it, or a Markdown file. */
public final class Plans {
  /** What the agent of the thread that proposed the plan reads when another thread implements it. */
  public static final String DECLINE_MESSAGE = "The user is implementing this plan in a new thread. Stop here and make no changes.";
  /** The name of a worktree for a plan without a title. */
  static final String UNTITLED = "plan";
  private static final String PLAN_MODE = "plan";
  private static final String DEFAULT_MODE = "default";
  private static final int FILE_NAME_LENGTH = 60;
  private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$");
  /** "Plan:", or "Plan" and a dash between spaces, but not the start of "Plan-based". */
  private static final Pattern PLAN_PREFIX = Pattern.compile("^(?i)plan(?:\\s*:|\\s+[-\\u2013\\u2014])\\s*");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)");
  private static final Set<String> RESERVED_NAMES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
    "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

  private Plans() {
  }

  /**
   * The text of the plan's first Markdown heading outside code blocks, without a leading "Plan:", or null without one.
   * A heading with nothing else, like "# Plan:", is skipped.
   */
  public static @Nullable String title(String plan) {
    String fence = null;
    for (String line : plan.split("\\R", -1)) {
      Matcher fenceMatcher = FENCE.matcher(line);
      if (fenceMatcher.matches()) {
        String run = fenceMatcher.group(1);
        if (fence == null) {
          fence = run;
        } else if (run.charAt(0) == fence.charAt(0) && run.length() >= fence.length()
          && fenceMatcher.group(2).isBlank()) {
          fence = null;
        }
        continue;
      }
      Matcher matcher = HEADING.matcher(line);
      if (fence == null && matcher.matches()) {
        String title = PLAN_PREFIX.matcher(matcher.group(1).trim()).replaceFirst("").trim();
        if (!title.isEmpty()) {
          return title;
        }
      }
    }
    return null;
  }

  /** The prompt a new thread implements the plan with. */
  public static String implementPrompt(String plan) {
    return "Implement this plan:\n\n" + plan.trim();
  }

  /**
   * A file name for the plan, from its title: lower case letters and digits joined by dashes, valid on every OS.
   * {@code plan.md} without a usable title.
   */
  public static String fileName(String plan) {
    String title = title(plan);
    String slug = title == null
      ? ""
      : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (slug.length() > FILE_NAME_LENGTH) {
      slug = slug.substring(0, FILE_NAME_LENGTH).replaceAll("-+$", "");
    }
    if (slug.isEmpty()) {
      slug = UNTITLED;
    } else if (RESERVED_NAMES.contains(slug)) {
      slug = UNTITLED + "-" + slug;
    }
    return slug + ".md";
  }

  /** The plan as a file's text: {@code \n} line ends and one final line end. */
  public static String fileText(String plan) {
    return plan.replace("\r\n", "\n").replace('\r', '\n').stripTrailing() + "\n";
  }

  /**
   * The mode a thread implementing the plan starts in: the planning thread's, but never plan mode. For Claude Code a
   * blank or plan mode becomes its default mode explicitly, since its own settings could start it in plan mode.
   *
   * @param mode        the planning thread's mode, blank for the agent's default
   * @param defaultMode the mode the settings start the agent in, blank for its own default
   */
  public static String implementMode(String agent, String mode, String defaultMode) {
    String effective = mode.isEmpty() ? defaultMode : mode;
    if (ClaudeBackend.ID.equals(agent) && (effective.isEmpty() || PLAN_MODE.equals(effective))) {
      return DEFAULT_MODE;
    }
    return PLAN_MODE.equals(effective) ? "" : mode;
  }
}
