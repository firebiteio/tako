package io.firebite.tako.chat.git;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/** The prompt that asks an agent for a commit message and pull request texts, and the texts read from its answer. */
public final class GitDrafts {
  public static final int MAX_DIFF = 40_000;
  public static final int MAX_CONVERSATION = 6_000;
  public static final int RECENT_SUBJECTS = 10;
  public static final int BRANCH_SUBJECTS = 30;
  private static final String COMMIT = "commit";
  private static final String TITLE = "title";
  private static final String BODY = "body";

  private GitDrafts() {
  }

  /**
   * @param diff           the changes the commit would record, empty without a commit step
   * @param recentSubjects the repository's newest commit subjects, for its style
   * @param branchSubjects the branch's own commits, which a pull request also describes
   * @param conversation   what the developer and the agent said in the thread
   */
  public static String prompt(boolean commit, boolean pullRequest, String diff, List<String> recentSubjects,
    List<String> branchSubjects, String conversation) {
    StringBuilder prompt = new StringBuilder();
    if (commit && pullRequest) {
      prompt.append("Write a git commit message for the changes below, and a title and description for the pull "
        + "request that will contain the commit.\n");
    } else if (commit) {
      prompt.append("Write a git commit message for the changes below.\n");
    } else {
      prompt.append("Write a title and description for a pull request of the branch whose commits are listed "
        + "below.\n");
    }
    prompt.append("Describe what the change does and why. Do not mention AI, agents or tools. ");
    if (commit) {
      prompt.append("If the recent commit subjects share a style, such as a prefix like \"fix(scope):\", follow it. "
        + "Otherwise write a short imperative subject line under 72 characters. Add a body after a blank line only "
        + "when the change needs explaining. ");
    }
    if (pullRequest) {
      prompt.append("The pull request title is one line. The description is short Markdown: what changed and how it "
        + "was tested, if the conversation says so. ");
    }
    prompt.append("\nAnswer in exactly this format, with nothing before or after:\n");
    if (commit) {
      prompt.append(tag(COMMIT, "the commit message"));
    }
    if (pullRequest) {
      prompt.append(tag(TITLE, "the pull request title")).append(tag(BODY, "the pull request description"));
    }
    if (!recentSubjects.isEmpty()) {
      prompt.append("\nRecent commit subjects:\n");
      for (String subject : recentSubjects) {
        prompt.append("- ").append(subject).append('\n');
      }
    }
    if (!branchSubjects.isEmpty()) {
      prompt.append("\nCommits of this branch:\n");
      for (String subject : branchSubjects) {
        prompt.append("- ").append(subject).append('\n');
      }
    }
    if (!conversation.isBlank()) {
      prompt.append("\nThe conversation that led to the changes:\n").append(cut(conversation, MAX_CONVERSATION, true))
        .append('\n');
    }
    if (!diff.isBlank()) {
      prompt.append("\nChanges:\n").append(cut(diff, MAX_DIFF, false)).append('\n');
    }
    return prompt.toString();
  }

  /**
   * The texts in the answer's tags. Without tags, the whole answer is the commit message, its first line the title
   * and the rest the description. Code fences around the answer or a section are dropped.
   */
  public static Draft parse(String answer) {
    String text = unfence(answer.replace("\r\n", "\n").strip());
    String commit = section(text, COMMIT);
    String title = section(text, TITLE);
    String body = section(text, BODY);
    if (commit == null && title == null && body == null) {
      int newline = text.indexOf('\n');
      return newline < 0
        ? new Draft(text, text, "")
        : new Draft(text, text.substring(0, newline).strip(), text.substring(newline + 1).strip());
    }
    String message = commit == null ? "" : commit;
    if (title == null) {
      int newline = message.indexOf('\n');
      title = newline < 0 ? message : message.substring(0, newline);
    }
    return new Draft(message, firstLine(title), body == null ? "" : body);
  }

  /** The text between {@code <name>} and its closing tag, or the next tag or the end when it has none. */
  private static @Nullable String section(String text, String name) {
    int open = text.indexOf("<" + name + ">");
    if (open < 0) {
      return null;
    }
    int start = open + name.length() + 2;
    int close = text.indexOf("</" + name + ">", start);
    if (close < 0) {
      int next = start;
      close = text.length();
      for (String other : List.of(COMMIT, TITLE, BODY)) {
        int tag = text.indexOf("<" + other + ">", next);
        if (tag >= 0 && tag < close) {
          close = tag;
        }
      }
    }
    return unfence(text.substring(start, close).strip());
  }

  private static String firstLine(String text) {
    String stripped = text.strip();
    int newline = stripped.indexOf('\n');
    return (newline < 0 ? stripped : stripped.substring(0, newline)).strip();
  }

  /** The text inside a code fence that wraps all of it. */
  private static String unfence(String text) {
    if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
      return text;
    }
    int firstLineEnd = text.indexOf('\n');
    if (firstLineEnd < 0) {
      return text;
    }
    return text.substring(firstLineEnd + 1, text.length() - 3).strip();
  }

  private static String tag(String name, String content) {
    return "<" + name + ">\n" + content + "\n</" + name + ">\n";
  }

  /** The text, or its start or end and a note, in about {@code length} characters. */
  private static String cut(String text, int length, boolean keepsEnd) {
    if (text.length() <= length) {
      return text;
    }
    return keepsEnd ? "[cut]\n" + text.substring(text.length() - length) : text.substring(0, length) + "\n[cut]";
  }
}
