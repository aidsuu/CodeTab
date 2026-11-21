#!/usr/bin/env bash
# Runs the Rust end-to-end tests against a throwaway, unprivileged sshd.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"
SSHD="${SSHD:-/usr/sbin/sshd}"
PORT="${PORT:-$((20000 + RANDOM % 20000))}"
DIR="$(mktemp -d "${TMPDIR:-/tmp}/jangkau-e2e.XXXXXX")"
cleanup() { [[ -f "$DIR/sshd.pid" ]] && kill "$(cat "$DIR/sshd.pid")" 2>/dev/null || true; rm -rf "$DIR"; }
trap cleanup EXIT

ssh-keygen -q -t ed25519 -N '' -f "$DIR/host_key"
ssh-keygen -q -t ed25519 -N '' -C jangkau-e2e -f "$DIR/client_key"
cp "$DIR/client_key.pub" "$DIR/authorized_keys"
chmod 700 "$DIR"; chmod 600 "$DIR/authorized_keys"
cat > "$DIR/sshd_config" <<CFG
Port $PORT
ListenAddress 127.0.0.1
HostKey $DIR/host_key
PidFile $DIR/sshd.pid
AuthorizedKeysFile $DIR/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AllowTcpForwarding yes
StrictModes no
UsePAM no
Subsystem sftp internal-sftp
LogLevel ERROR
CFG
"$SSHD" -f "$DIR/sshd_config" -E "$DIR/sshd.log"
for _ in $(seq 50); do [[ -f "$DIR/sshd.pid" ]] && break; sleep 0.1; done
echo "test sshd on 127.0.0.1:$PORT (pid $(cat "$DIR/sshd.pid"))"

export JANGKAU_E2E_PORT="$PORT" JANGKAU_E2E_USER="$(id -un)" JANGKAU_E2E_KEY="$DIR/client_key" JANGKAU_E2E_DIR="$DIR"
cd "$ROOT/core"
cargo test --test sshd_e2e -- --nocapture --test-threads=1 "$@" || { echo "--- sshd.log"; cat "$DIR/sshd.log"; exit 1; }
