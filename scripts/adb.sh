#!/usr/bin/env bash
# Run adb against the shared Windows dev emulator, or a LAN device.
#
# The emulator (and its adb server) lives on the Windows host. This script keeps
# an SSH tunnel to that adb server open, then forwards all arguments to the local
# adb client. Use it anywhere you would normally use `adb`.
#
# Set JEDFLIX_ADB_TARGET=host:port to talk to a device that runs its own adb
# daemon, such as a TV on the LAN. `.local/device.env` is sourced first, so a
# machine can keep that preference out of the checkout.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Machine-local device preference, e.g. a TV on the LAN. Gitignored.
_jedflix_target_set="${JEDFLIX_ADB_TARGET+x}"
_jedflix_target_override="${JEDFLIX_ADB_TARGET-}"
# shellcheck source=/dev/null
[[ -f "$ROOT/.local/device.env" ]] && . "$ROOT/.local/device.env"
# An explicitly empty target selects the shared emulator instead of the TV.
if [[ "$_jedflix_target_set" == x ]]; then
  export JEDFLIX_ADB_TARGET="$_jedflix_target_override"
fi
unset _jedflix_target_set _jedflix_target_override

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

# Explicit opt-in for a local AVD when the shared Windows device is offline.
if [[ "$HOST" == local ]]; then
  unset ADB_SERVER_SOCKET
  exec "$ADB" "$@"
fi

# A network device with its own adb daemon (a TV on the LAN) needs no tunnel.
# Set JEDFLIX_ADB_TARGET to host[:port]; it also becomes the default serial.
if [[ -n "${JEDFLIX_ADB_TARGET:-}" ]]; then
  unset ADB_SERVER_SOCKET
  "$ADB" connect "$JEDFLIX_ADB_TARGET" >/dev/null 2>&1 || true
  for arg in "$@"; do
    case "$arg" in
      -s|--serial|-d|-e) exec "$ADB" "$@" ;;
    esac
  done
  exec "$ADB" -s "$JEDFLIX_ADB_TARGET" "$@"
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
