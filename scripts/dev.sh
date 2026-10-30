#!/usr/bin/env bash
# Build the x86_64 dev APK and deploy it to the shared Windows emulator.
set -euo pipefail
cd "$(dirname "$0")/.."

SERIAL="${JEDFLIX_SERIAL:-emulator-5554}"
APK="dist/jedflix-android-tv-1.10.4-x86_64.apk"
PACKAGE="com.stremio.one"
ACTIVITY="${PACKAGE}/com.stremio.tv.MainActivity"
BUILD=1
LAUNCH=1

usage() {
  cat <<'EOF'
Usage: scripts/dev.sh [--no-build] [--no-launch]

  --no-build   Reinstall the existing x86_64 APK without rebuilding
  --no-launch  Install but do not start the app
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
  ./scripts/build.sh x86_64
fi

if [[ ! -f "$APK" ]]; then
  echo "missing $APK; run without --no-build first" >&2
  exit 1
fi

./scripts/adb.sh -s "$SERIAL" devices | tail -n +2 | grep -q . \
  || { echo "no device on $SERIAL; is the Windows emulator running?" >&2; exit 1; }

./scripts/adb.sh -s "$SERIAL" install -r "$APK"

if [[ "$LAUNCH" == 1 ]]; then
  ./scripts/adb.sh -s "$SERIAL" shell am start -W -n "$ACTIVITY"
fi
