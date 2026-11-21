# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/aidsuu/CodeTab/compare/v0.2.3...HEAD
[0.2.3]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.3
[0.2.2]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.2
[0.2.1]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.1
[0.2.0]: https://github.com/aidsuu/CodeTab/releases/tag/v0.2.0
[0.1.0]: https://github.com/aidsuu/CodeTab/releases/tag/v0.1.0
