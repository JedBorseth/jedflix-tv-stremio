#!/usr/bin/env bash
# Build the dev APK and deploy it to the configured device.
set -euo pipefail
cd "$(dirname "$0")/.."

# Machine-local device preference, e.g. a TV on the LAN. Gitignored.
_jedflix_target_set="${JEDFLIX_ADB_TARGET+x}"
_jedflix_target_override="${JEDFLIX_ADB_TARGET-}"
# shellcheck source=/dev/null
[[ -f .local/device.env ]] && . .local/device.env
if [[ "$_jedflix_target_set" == x ]]; then
  export JEDFLIX_ADB_TARGET="$_jedflix_target_override"
fi
unset _jedflix_target_set _jedflix_target_override

SERIAL="${JEDFLIX_SERIAL:-${JEDFLIX_ADB_TARGET:-emulator-5554}}"
# Match the build to the device unless the caller pins an architecture.
ARCH="${JEDFLIX_ARCH:-}"
if [[ -z "$ARCH" ]]; then
  ABILIST="$(./scripts/adb.sh -s "$SERIAL" shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r' || true)"
  case "${ABILIST%%,*}" in
    *arm64*) ARCH=arm64 ;;
    *armeabi*) ARCH=arm ;;
    *x86_64*) ARCH=x86_64 ;;
    *) ARCH=x86_64 ;;
  esac
fi
case "$ARCH" in
  x86_64|arm64|arm) ;;
  *) echo "JEDFLIX_ARCH must be x86_64, arm64, or arm" >&2; exit 2 ;;
esac
APK="dist/jedflix-android-tv-1.10.4-$ARCH.apk"
PACKAGE="com.jedflix.stremio"
ACTIVITY="${PACKAGE}/com.stremio.tv.MainActivity"
BUILD=1
LAUNCH=1

usage() {
  cat <<'EOF'
Usage: scripts/dev.sh [--no-build] [--no-launch]

  --no-build   Reinstall the existing APK for the detected architecture without rebuilding
  --no-launch  Install but do not start the app

Environment: JEDFLIX_ADB_TARGET=host:port for a LAN device (or .local/device.env),
JEDFLIX_SERIAL to pin a serial, JEDFLIX_ARCH to pin x86_64, arm64, or arm.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-launch) LAUNCH=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

mkdir -p .local
LOCK=".local/dev-deploy.lock"
waited=0
while ! mkdir "$LOCK" 2>/dev/null; do
  if [[ "$waited" -ge 600 ]]; then
    echo "another deploy has held $LOCK for 10 minutes; remove it if stale" >&2
    exit 1
  fi
  echo "another deploy is in progress; waiting..." >&2
  sleep 2
  waited=$((waited + 2))
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

if [[ "$BUILD" == 1 ]]; then
  ./scripts/build.sh "$ARCH"
fi

if [[ ! -f "$APK" ]]; then
  echo "missing $APK; run without --no-build first" >&2
  exit 1
fi

./scripts/adb.sh -s "$SERIAL" devices | tail -n +2 | grep -q . \
  || { echo "no device on $SERIAL; is the selected device running?" >&2; exit 1; }

./scripts/adb.sh -s "$SERIAL" install -r "$APK"

if [[ "$LAUNCH" == 1 ]]; then
  ./scripts/adb.sh -s "$SERIAL" shell am start -W -n "$ACTIVITY"
fi
