# Security Policy

CodeTab handles SSH private keys and connects to remote servers, so security reports are taken seriously.

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them privately through [GitHub security advisories](https://github.com/aidsuu/CodeTab/security/advisories/new).

Include a description of the issue, the steps to reproduce it, and the affected version. You can expect an initial response within seven days.

## How CodeTab stores secrets

- Private keys are encrypted with AES-256-GCM using a non-exportable key in the Android Keystore and are excluded from backups.
- Passphrases are kept in memory only and are never written to disk.
- Host profiles and trusted host keys are stored in app-private storage and contain no secrets.
