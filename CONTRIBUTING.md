# Contributing to CodeTab

Thanks for your interest in improving CodeTab.

## Reporting bugs and requesting features

Open an issue using one of the templates. For bugs, include the app version, the tablet model and Android version, and the steps to reproduce. Never include private keys, passphrases, or host names you do not want to make public.

## Development setup

Follow [Building from source](README.md#building-from-source) to build the Rust core and the app.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change.
3. Add or update tests for the code you change. Logic that does not touch the UI belongs in the Rust core with unit tests.
4. Make sure all checks pass.

```bash
cargo fmt --manifest-path core/Cargo.toml --check
```

```bash
cargo test --lib --manifest-path core/Cargo.toml
```

```bash
./gradlew testDebugUnitTest
```

5. If you change the UI, update the screenshots and include them in the pull request.
6. Add an entry under `Unreleased` in [CHANGELOG.md](CHANGELOG.md).

## Code style

- Kotlin follows the official Kotlin coding conventions.
- Rust code is formatted with `rustfmt`.
- Prefer clear names over comments. Comment only what the code cannot say.
- Do not commit generated files. `scripts/build-core.sh` regenerates the native libraries and the Kotlin bindings.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
