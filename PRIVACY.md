# Privacy

Tako collects no data. It has no telemetry or analytics and sends nothing to Firebite or anyone else.

Tako starts the agent CLIs you set up, such as Claude Code, Codex or an ACP agent, on your machine. Your prompts, the files and selections you attach, and the agent's tool output go to that CLI. The CLI sends them to its provider under the provider's own privacy policy.

If you enter an Anthropic API key, Tako keeps it in the IDE password safe and passes it only to the Claude Code CLI.

For commits and pull requests Tako runs `git`, `gh` or `glab` with your own login. To give agents its thread tools, Tako runs a local server on 127.0.0.1 that other machines can't reach.

Threads, settings and rewind snapshots (`refs/tako/*` in your repository) stay on your machine.
