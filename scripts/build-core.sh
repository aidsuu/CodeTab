#!/usr/bin/env bash
# Builds the Rust core for Android and the host, then generates the Kotlin bindings.
# Usage: scripts/build-core.sh [--host-only|--android-only]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}"
API=26
MODE="${1:-all}"
cd "$ROOT/core"

if [[ "$MODE" != "--android-only" ]]; then
  echo ">> host release build"
  cargo build --release --lib
  echo ">> kotlin bindings"
  cargo run --release --features bindgen --bin uniffi-bindgen -- generate \
    --library target/release/libjangkau_core.so --language kotlin \
    --out-dir "$ROOT/app/src/main/java" --no-format
fi

if [[ "$MODE" != "--host-only" ]]; then
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  [[ -x "$TC/aarch64-linux-android${API}-clang" ]] || { echo "NDK not found at $NDK (set ANDROID_NDK_HOME)"; exit 1; }
  echo ">> android arm64 build"
  export CC_aarch64_linux_android="$TC/aarch64-linux-android${API}-clang"
  export AR_aarch64_linux_android="$TC/llvm-ar"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
  # 16 KB page alignment for Android 15+.
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
  cargo build --release --lib --target aarch64-linux-android
  mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
  cp target/aarch64-linux-android/release/libjangkau_core.so "$ROOT/app/src/main/jniLibs/arm64-v8a/"
  ls -la "$ROOT/app/src/main/jniLibs/arm64-v8a/"
fi
