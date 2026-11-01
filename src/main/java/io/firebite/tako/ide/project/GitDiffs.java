package io.firebite.tako.ide.project;

import io.firebite.tako.ide.project.Worktrees.GitException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What a checkout changed, read from git for the diff viewer: its uncommitted changes, its branch against the default
 * branch, and single commits. Untracked files count as uncommitted changes. Paths are relative to the repository's top
 * folder.
 */
public final class GitDiffs {
  public static final int COMMIT_LIMIT = 50;
  /** Larger files are not loaded, like {@code FileUtilRt.LARGE_FOR_CONTENT_LOADING}. */
  public static final long MAX_FILE_BYTES = 20L << 20;
  /** {@link Sources#uncommitted} when git could not list the uncommitted changes. */
  public static final int UNKNOWN = -1;
  private static final String REMOTE_HEAD = "refs/remotes/origin/HEAD";
  private static final String REMOTES = "refs/remotes/";
  private static final String HEADS = "refs/heads/";
  private static final List<String> DEFAULT_BRANCHES = List.of("main", "master");
  private static final char FIELD = '\u001f';
  private static final char RECORD = '\u001e';

  private GitDiffs() {
  }

  /** Reads the checkout {@code directory} is in. Call off the EDT. */
  public static Sources sources(Path directory, Map<String, String> environment) throws GitException {
    Path topLevel = Worktrees.topLevel(directory, environment);
    String head = resolve(topLevel, environment, "HEAD");
    int uncommitted;
    try {
      uncommitted = uncommittedFiles(topLevel, head, environment).size();
    } catch (GitException e) {
      uncommitted = UNKNOWN;
    }
    if (head == null) {
      return new Sources(uncommitted, BranchState.UNBORN, null, null, 0, List.of());
    }
    String name = probe(topLevel, environment, "symbolic-ref", "--short", "-q", "HEAD");
    Base base = findBase(topLevel, name, environment);
    String mergeBase = base == null ? null : probe(topLevel, environment, "merge-base", base.sha(), head);
    if (base == null || mergeBase == null) {
      return new Sources(uncommitted, BranchState.NO_BASE, null, null, 0, commits(topLevel, head, environment));
    }
    int count = Integer.parseInt(Worktrees.git(topLevel, environment, "rev-list", "--count",
      mergeBase + ".." + head).trim());
    if (count == 0) {
      return new Sources(uncommitted, BranchState.NO_OWN_COMMITS, base.name(), null, 0,
        commits(topLevel, head, environment));
    }
    String headShort = shortSha(topLevel, head, environment);
    Branch branch = new Branch(base.name(), mergeBase, shortSha(topLevel, mergeBase, environment), head,
      name == null ? headShort : name + " (" + headShort + ")");
    return new Sources(uncommitted, BranchState.OK, base.name(), branch, count,
      commits(topLevel, mergeBase + ".." + head, environment));
  }

  /** The files the source changed. Call off the EDT. */
  public static Changes changes(Path directory, Source source, Map<String, String> environment) throws GitException {
    Path topLevel = Worktrees.topLevel(directory, environment);
    return switch (source) {
      case Uncommitted uncommitted -> {
        String head = resolve(topLevel, environment, "HEAD");
        yield new Changes(topLevel, head, null, head == null ? null : "HEAD", null,
          uncommittedFiles(topLevel, head, environment));
      }
      case Branch branch -> new Changes(topLevel, branch.mergeBase(), branch.head(),
        branch.base() + " (" + branch.mergeBaseShort() + ")", branch.headName(),
        diff(topLevel, environment, branch.mergeBase(), branch.head()));
      case Commit commit -> {
        String sha = Worktrees.git(topLevel, environment, "rev-parse", "--verify", commit.sha() + "^{commit}").trim();
        String parent = probe(topLevel, environment, "rev-parse", "--verify", "-q", sha + "^1");
        String from = parent != null ? parent : emptyTree(topLevel, environment);
        yield new Changes(topLevel, parent, sha, parent == null ? null : commit.shortSha() + "^", commit.shortSha(),
          diff(topLevel, environment, from, sha));
      }
    };
  }

  /**
   * The file at {@code revision} as git stores it, or only its size when it is larger than {@code limit} bytes or a
   * submodule.
   */
  public static Content content(Path topLevel, String revision, String path, long limit,
    Map<String, String> environment) throws GitException {
    Map<String, String> literal = new HashMap<>(environment);
    literal.put("GIT_LITERAL_PATHSPECS", "1");
    String entry = Worktrees.git(topLevel, literal, "ls-tree", "-l", "-z", "--full-tree", revision, "--", path);
    int tab = entry.indexOf('\t');
    String[] fields = tab < 0 ? new String[0] : entry.substring(0, tab).trim().split("\\s+");
    if (fields.length < 4) {
      // Fails with git's own message, such as that the path is not in the commit.
      Worktrees.git(topLevel, literal, "cat-file", "-e", revision + ":" + path);
      throw new GitException(revision + ":" + path);
    }
    if ("commit".equals(fields[1])) {
      return new Content(null, 0, true);
    }
    if (!"blob".equals(fields[1])) {
      throw new GitException(revision + ":" + path + " is a " + fields[1]);
    }
    long size = Long.parseLong(fields[3]);
    if (size > limit) {
      return new Content(null, size, false);
    }
    return new Content(Worktrees.gitBytes(topLevel, environment, "cat-file", "blob", fields[2]), size, false);
  }

  /**
   * The branch {@code currentBranch} came from: what {@code origin/HEAD} points to, else {@code origin/main},
   * {@code origin/master}, then a local {@code main} or {@code master} other than the current branch. Null when there is
   * none.
   */
  static @Nullable Base findBase(Path directory, @Nullable String currentBranch, Map<String, String> environment) {
    List<String> candidates = new ArrayList<>();
    String remoteHead = probe(directory, environment, "symbolic-ref", "-q", REMOTE_HEAD);
    if (remoteHead != null) {
      candidates.add(remoteHead);
    }
    for (String name : DEFAULT_BRANCHES) {
      candidates.add(REMOTES + "origin/" + name);
    }
    for (String name : DEFAULT_BRANCHES) {
      if (!name.equals(currentBranch)) {
        candidates.add(HEADS + name);
      }
    }
    for (String ref : candidates) {
      String sha = resolve(directory, environment, ref);
      if (sha != null) {
        String name = ref.startsWith(REMOTES) ? ref.substring(REMOTES.length()) : ref.substring(HEADS.length());
        return new Base(name, sha);
      }
    }
    return null;
  }

  /**
   * {@code git diff --name-status -z} output, with renames and copies read as old path then new path. A path listed
   * twice, as unmerged files can be, is kept once.
   */
  static List<Change> parseNameStatus(String output) {
    String[] parts = output.split("\0");
    List<Change> changes = new ArrayList<>();
    int i = 0;
    while (i < parts.length) {
      String status = parts[i++];
      if (status.isEmpty() || i >= parts.length) {
        continue;
      }
      char letter = status.charAt(0);
      Change change;
      if ((letter == 'R' || letter == 'C') && i + 1 < parts.length) {
        change = new Change(letter, parts[i + 1], parts[i]);
        i += 2;
      } else {
        change = new Change(letter, parts[i++], null);
      }
      if (changes.isEmpty() || !changes.getLast().path().equals(change.path())) {
        changes.add(change);
      }
    }
    return changes;
  }

  /** {@code git log} output with fields split by {@code \x1f} and records ended by {@code \x1e}. */
  static List<Commit> parseLog(String output) {
    List<Commit> commits = new ArrayList<>();
    for (String record : output.split(String.valueOf(RECORD))) {
      int start = 0;
      while (start < record.length() && (record.charAt(start) == '\n' || record.charAt(start) == '\r')) {
        start++;
      }
      String[] fields = record.substring(start).split(String.valueOf(FIELD), -1);
      if (fields.length < 4) {
        continue;
      }
      long seconds;
      try {
        seconds = Long.parseLong(fields[2]);
      } catch (NumberFormatException e) {
        continue;
      }
      commits.add(new Commit(fields[0], fields[1], fields[3], seconds * 1000));
    }
    return commits;
  }

  private static List<Change> uncommittedFiles(Path topLevel, @Nullable String head, Map<String, String> environment)
    throws GitException {
    try (Worktrees.MarkedIndex index = Worktrees.MarkedIndex.of(topLevel, environment)) {
      String base = head != null ? head : emptyTree(topLevel, environment);
      return parseNameStatus(Worktrees.git(topLevel, index.environment(), "diff", "--no-ext-diff", "--name-status",
        "-z", "-M", base));
    }
  }

  private static List<Change> diff(Path topLevel, Map<String, String> environment, String from, String to)
    throws GitException {
    return parseNameStatus(Worktrees.git(topLevel, environment, "diff", "--no-ext-diff", "--name-status", "-z", "-M",
      from, to));
  }

  private static List<Commit> commits(Path topLevel, String range, Map<String, String> environment)
    throws GitException {
    return parseLog(Worktrees.git(topLevel, environment, "log", "--no-show-signature", "-n",
      String.valueOf(COMMIT_LIMIT), "--format=%H%x1f%h%x1f%ct%x1f%s%x1e", range, "--"));
  }

  private static String emptyTree(Path topLevel, Map<String, String> environment) throws GitException {
    return Worktrees.git(topLevel, environment, "hash-object", "-t", "tree", "--stdin").trim();
  }

  private static String shortSha(Path topLevel, String sha, Map<String, String> environment) throws GitException {
    return Worktrees.git(topLevel, environment, "rev-parse", "--short", sha).trim();
  }

  /** The commit {@code ref} names, or null when it names none. */
  private static @Nullable String resolve(Path directory, Map<String, String> environment, String ref) {
    String sha = probe(directory, environment, "rev-parse", "--verify", "-q", ref + "^{commit}");
    return sha == null || sha.isEmpty() ? null : sha;
  }

  /** The trimmed output of a lookup, or null when git finds nothing, which it reports by failing. */
  private static @Nullable String probe(Path directory, Map<String, String> environment, String... arguments) {
    try {
      return Worktrees.git(directory, environment, arguments).trim();
    } catch (GitException e) {
      return null;
    }
  }

  /**
   * What the checkout has to diff.
   *
   * @param uncommitted how many files differ from HEAD, untracked ones included, or {@link #UNKNOWN}
   * @param base the default branch's name, like {@code origin/main}, when the branch has one
   * @param branch the branch's commits since {@code base}, only when it has some
   * @param branchCommits how many commits the branch has since it left {@code base}
   * @param commits the branch's own commits, newest first, or HEAD's newest when it has none of its own; at most
   *   {@link #COMMIT_LIMIT}
   */
  public record Sources(
    int uncommitted,
    BranchState branchState,
    @Nullable String base,
    @Nullable Branch branch,
    int branchCommits,
    List<Commit> commits
  ) {
  }

  public enum BranchState {
    OK,
    UNBORN,
    NO_BASE,
    NO_OWN_COMMITS
  }

  /** What a diff window shows. */
  public sealed interface Source permits Uncommitted, Branch, Commit {
  }

  /** The working tree and index against HEAD, untracked files included. */
  public record Uncommitted() implements Source {
  }

  /**
   * The branch's commits since it left its base branch, without uncommitted changes. Holds the commits as read, so the
   * diff shows what the menu said even when the branch moved since.
   *
   * @param base the base branch's name, like {@code origin/main}
   * @param headName the branch and its short commit, or only the commit on a detached HEAD
   */
  public record Branch(
    String base,
    String mergeBase,
    String mergeBaseShort,
    String head,
    String headName
  ) implements Source {
  }

  /** One commit against its first parent. */
  public record Commit(
    String sha,
    String shortSha,
    String subject,
    long timeMillis
  ) implements Source {
  }

  /**
   * @param status git's status letter, like {@code M}, {@code A}, {@code D} or {@code R}
   * @param oldPath the path before a rename or copy
   */
  public record Change(
    char status,
    String path,
    @Nullable String oldPath
  ) {
  }

  /**
   * The files of a source and the two sides they compare.
   *
   * @param beforeRevision the commit before, null for nothing (before the first commit)
   * @param afterRevision the commit after, null for the working tree
   * @param beforeName a name for the before side, null when it is empty
   * @param afterName a name for the after side, null for the working tree
   */
  public record Changes(
    Path topLevel,
    @Nullable String beforeRevision,
    @Nullable String afterRevision,
    @Nullable String beforeName,
    @Nullable String afterName,
    List<Change> files
  ) {
  }

  /** @param bytes the file, null when it is larger than the limit or a submodule */
  public record Content(
    @Nullable byte[] bytes,
    long size,
    boolean isSubmodule
  ) {
  }

  /** @param name the short name, like {@code origin/main} */
  record Base(
    String name,
    String sha
  ) {
  }
}
