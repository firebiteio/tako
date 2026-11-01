<p align="center"><img src="docs/tako.svg" width="128" alt="Tako"></p>

# Tako

Tako puts coding agents in an IntelliJ tool window. It starts the agent CLI you already have installed and shows the conversation as a chat, with tool calls as cards and file edits in the IDE's diff viewer.

It works with:

- Claude Code (`claude`)
- Codex (`codex app-server`)
- Any agent that speaks the [Agent Client Protocol](https://agentclientprotocol.com), such as Gemini CLI (`gemini --acp`) or OpenCode (`opencode acp`)

## Features

- Answers stream in as the agent writes them
- Before an edit, the permission prompt can open the change in the IDE diff viewer
- After each turn you get a diff of every file the agent touched
- Comment on lines in those diffs, or in any file, and send the comments to the agent together
- Rewind a thread to before one of your prompts and edit it, with or without putting the files back (Claude Code and Codex)
- Threads are grouped by project. Archive them, or resume a session you started in the agent's CLI
- Pin, rename, snooze or mark threads unread from their menu. A thread whose agent finished or asked something while you looked elsewhere gets a dot until you open it. Optionally archive threads automatically once their pull request is merged or after some days without activity (Settings > Tools > Tako)
- Each thread has its own model, mode and effort
- Type `@` to mention a file or another thread, paste images, or send the editor selection
- Fix Problems sends a file's inspection results to the agent
- For Claude Code, a context meter and a usage view with cost and tokens per session
- Optional parallel runs: one prompt goes to several agents or models at once, each in its own git worktree. Compare what each changed, cost and took, then apply the best one

## Requirements

- An IntelliJ-based IDE, 2026.2 or newer
- At least one agent CLI, either on your `PATH` or with its path set in the settings

## Usage

Open the Tako tool window on the right, click New Tako Chat, pick an agent and type.

In the editor or project view, right-click and choose Tako > Add to Tako (`Ctrl+Alt+Shift+K`) to attach the selection or file. Fix Problems with Tako is in the same menu.

To comment on the agent's changes, select lines in a diff Tako opened, right-click and choose Comment for Tako. Type the comment and press Enter. It lands in the composer of the thread that made the change, with the file, the lines and the code, so you can collect several and send them in one prompt. Sending only comments asks the agent to address them. Declining a proposed edit sends the comments made in its diff as the reason, if the agent reads one. In a file's editor the same command is under Tako, and the comment goes to the current thread.

Settings are under Settings > Tools > Tako. The top page sets the agent for new threads. Each agent has its own page below it for the executable and the default model, mode and effort. ACP agents go in a table, one name and command line per row.

### Rewind

Hover over a prompt you sent and click the rewind button. Rewind conversation makes the agent forget that prompt and everything after it, and puts the prompt back into the box to edit. Rewind conversation and files also puts the files in the thread's folder back to how they were when the prompt was sent, your own changes since then included.

For the files, Tako snapshots the thread's folder in git each time you send a prompt while the agent is idle. The snapshots are commits under `refs/tako/checkpoints/<thread>`, outside your branches and index, and are deleted when the thread is closed. Ignored files are not part of them. Before it restores files, Tako keeps the state it replaces under `refs/tako/backup/<thread>`. Without a git repository only the conversation can be rewound.

Claude Code continues in a copy of its session. The old session stays in the session history. Codex reverts the thread in place, which needs a Codex version with `thread/revert`. ACP agents can't rewind.

### Parallel runs

Turn on Parallel runs under Settings > Tools > Tako. A new thread's composer then has a runs picker. Choose how many runs to start, and optionally a different agent and model for each. Sending creates one git worktree per run from the last commit, in a `<repo>-tako` folder beside the repository, and one thread per run, grouped in the sidebar. Uncommitted changes are not copied into the worktrees. Each worktree is a fresh checkout without dependencies or build output; a `.worktreesetup` file sets them up, see below.

Local config can come along: list gitignored files like `.env` or `.mcp.json` in a `.worktreeinclude` file in the repository root, in `.gitignore` syntax, as for Claude Code. Tako copies them into every worktree it creates, for parallel runs and for threads an agent starts in a new worktree. Tracked files and files the worktree already has are left alone. Folders git ignores as a whole, like `build`, are only searched when a pattern names them or a path in them.

To install dependencies or build in each new worktree, put shell commands in a `.worktreesetup` file in the repository root, one per line, e.g. `npm ci`. Lines starting with `#` are comments. Tako runs them in order in the worktree's top folder before it starts the agent, with `cmd.exe` on Windows and `/bin/sh` elsewhere, and shows their progress and output above the composer. Each line runs in its own shell, so `cd` does not carry over. `TAKO_WORKTREE` and `TAKO_REPOSITORY` hold the paths of the worktree and of the main checkout. The worktree's own copy of the file is used if it has one, else the main checkout's, so the file can be committed or gitignored. When a command fails, or the setup takes more than 20 minutes, the prompt waits: Retry runs the file again, Start without setup sends the prompt anyway. Stop cancels the setup. Tako only runs the file in trusted projects. Keep what the commands create gitignored, or it counts as the run's changes.

The group's compare button lists each run's changed files, lines, cost, tokens and time. Diff opens a run's changes in the IDE diff viewer. Apply puts a run's changes into your checkout as uncommitted changes, then removes all the bench's worktrees, branches and threads. Discard removes them without applying anything.

### Signing in

Codex and ACP agents use their own CLI login. Run `codex login`, for example.

Claude Code is different. Tako passes it an Anthropic API key, which it keeps in the IDE password safe. [Anthropic doesn't allow](https://code.claude.com/docs/en/agent-sdk/overview#get-started) third-party tools to offer a claude.ai subscription login without its approval. There is a Claude CLI login option, but you use it at your own risk.

## Building

Gradle needs JDK 17 or newer to start. It downloads JDK 25 for the build on its own.

```sh
./gradlew buildPlugin   # plugin zip in build/distributions
./gradlew runIde        # sandbox IDE with the plugin
./gradlew check         # tests, Checkstyle, formatting
./gradlew verifyPlugin  # JetBrains Plugin Verifier
./gradlew spotlessApply # format Java and Kotlin
```

The build downloads the IntelliJ IDEA version in `gradle.properties` (`platformVersion`). To build against an IDE you already have, set `tako.platformLocalPath` in `~/.gradle/gradle.properties` to its folder.

### Nightly builds

Every push to `master` builds the plugin and replaces the [nightly pre-release](https://github.com/firebiteio/tako/releases/tag/nightly) with the new zip. Install it with Settings > Plugins > Install Plugin from Disk. Nightly builds are not signed.

### Releasing

Push a tag like `v0.2.0`. The release workflow builds that version, runs the checks, signs the plugin, publishes it to the JetBrains Marketplace and creates a GitHub release with the signed zip. It needs these repository secrets:

- `PUBLISH_TOKEN`, a JetBrains Marketplace token
- `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` for [plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)

Publishing can only update a plugin that already exists on the Marketplace, so upload the first version by hand.

## License

Copyright 2026 The Tako Authors. Licensed under the [Apache License 2.0](LICENSE).
