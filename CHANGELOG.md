# Changelog

## [Unreleased]

## [0.2.1] - 2026-11-18

- Fix switching agents right after a switch, which was sometimes refused while the previous switch was still finishing.

## [0.2.0] - 2026-11-18

- A new thread whose agent is not installed or has no API key now shows how to set it up. It links the installer, asks for an API key or switches to the Claude CLI login, and offers the agents that are ready.
- Until you pick the agent of new threads, a new thread lists the agents found on this computer. The one you pick becomes the default.

## [0.1.1] - 2026-11-15

- Fix the composer suggestion popup and context panel crashing on IntelliJ IDEA 2026.2.

## [0.1.0] - 2026-11-15

- Initial release.

[Unreleased]: https://github.com/firebiteio/tako/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/firebiteio/tako/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/firebiteio/tako/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/firebiteio/tako/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/firebiteio/tako/commits/v0.1.0
