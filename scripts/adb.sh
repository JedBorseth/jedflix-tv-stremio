#!/usr/bin/env bash
# Run adb against the shared Windows dev emulator.
#
# The emulator (and its adb server) lives on the Windows host. This script keeps
# an SSH tunnel to that adb server open, then forwards all arguments to the local
# adb client. Use it anywhere you would normally use `adb`.
set -euo pipefail

HOST="${JEDFLIX_DEVICE_HOST:-10.0.0.223}"
USER_NAME="${JEDFLIX_DEVICE_USER:-jedborseth}"
TARGET="${USER_NAME}@${HOST}"
PORT="${JEDFLIX_ADB_TUNNEL_PORT:-15037}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$SDK/platform-tools/adb}"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at $ADB; set ANDROID_HOME" >&2
  exit 1
fi

tunnel_alive() { nc -z 127.0.0.1 "$PORT" >/dev/null 2>&1; }

start_tunnel() {
  pkill -f "ssh .* -L ${PORT}:127.0.0.1:5037 " 2>/dev/null || true
  # A concurrent call may win the race for the port; either way, check the
  # socket afterwards instead of failing the whole adb invocation.
  ssh -f -N \
    -o BatchMode=yes \
    -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=15 \
    -o ServerAliveCountMax=3 \
    -o TCPKeepAlive=yes \
    -L "${PORT}:127.0.0.1:5037" "$TARGET" || true
  for _ in $(seq 1 25); do
    tunnel_alive && return 0
    sleep 0.2
  done
  echo "could not open adb tunnel to $TARGET on port $PORT" >&2
  return 1
}

ensure_remote_adb() {
  ssh -o BatchMode=yes -o ConnectTimeout=8 "$TARGET" "adb start-server" >/dev/null 2>&1 || true
}

tunnel_alive || start_tunnel
ensure_remote_adb

export ADB_SERVER_SOCKET="tcp:127.0.0.1:${PORT}"
exec "$ADB" "$@"
