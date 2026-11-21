# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-11-21

### Added

- Terminals run inside tmux when the server has it, so shells and running programs survive a dropped connection. Tabs reattach automatically after reconnecting and are restored when the app opens again.
- Automatic reconnection with backoff, triggered by failed latency checks or a change of network, and paused while the device is offline.
- Latency indicator in the status bar, measured with a lightweight SFTP request every five seconds.
- Predictive typing in the terminal. On slow links, typed characters appear immediately and are confirmed when the server echoes them. Input is never shown before the shell has echoed at least once, so passwords stay hidden.
- Mouse wheel scrolling inside tmux and other programs that enable mouse reporting.
- Syntax highlighting for about 60 languages, including JavaScript, TypeScript, HTML, CSS, PHP, Java, Kotlin, C++, C#, Go, Rust, Swift, Ruby, R, Julia, SQL, TOML, Dockerfile, and LaTeX.
- Colored language badges next to file names in the explorer, tabs, and source control.
- `scripts/update-grammars.py` to download the bundled grammars from pinned upstream versions.

### Changed

- SSH keepalive is sent every 10 seconds so a dead connection is noticed within about 30 seconds.

## [0.2.3] - 2026-11-21

### Changed

- The whole interface, including error messages from the SSH core, is now in English.
- Generated keys use `@codetab` in their comment.

## [0.2.2] - 2026-11-21

### Changed

- The app and documentation now describe any remote SSH server instead of HPC clusters only.

## [0.2.1] - 2026-11-21

### Changed

- New app icon and logo.

### Fixed

- Race in the PTY end-to-end test on slow machines.

## [0.2.0] - 2026-11-21

### Added

- Image viewer for PNG, JPEG, GIF, WebP, and BMP with pinch zoom.
- PDF viewer with page navigation and zoom.
- File search in the explorer.
- Option to show or hide dotfiles, hidden by default.
- Font choice between Cascadia Code, JetBrains Mono, and the system font.

### Changed

- Renamed the app to CodeTab with a new icon.
- New dark interface with rounded panels and pill buttons.
- Editor and terminal ligatures are disabled.

## [0.1.0] - 2026-11-21

### Added

- SSH connections with an optional jump host, public key authentication, and trust on first use host keys.
- Ed25519 key generation and import of OpenSSH, PEM, and PuTTY keys, encrypted with the Android Keystore.
- File explorer and editor over SFTP with TextMate syntax highlighting.
- Terminal with tabs, scrollback, and an extra key row.
- Running Python and shell files and submitting SLURM jobs.
- Git panel with status, staging, commit, pull, push, fetch, and diffs.

[Unreleased]: https://github.com/aidsuu/CodeTab/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/aidsuu/CodeTab/releases/tag/v0.3.0
[0.2.3]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.3
[0.2.2]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.2
[0.2.1]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.1
[0.2.0]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.0
[0.1.0]: https://github.com/aidsuu/CodeTab/releases/tag/v0.1.0
