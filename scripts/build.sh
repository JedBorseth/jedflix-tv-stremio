#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
mkdir -p .local/tools .local/upstream dist
# Decoding and signing use shared paths. Direct builds and dev builds must not
# overwrite each other's staging trees when several threads share this checkout.
if [[ "${JEDFLIX_BUILD_LOCK_HELD:-0}" != 1 ]]; then
  BUILD_LOCK=".local/apk-build.lock"
  waited=0
  while ! mkdir "$BUILD_LOCK" 2>/dev/null; do
    if [[ "$waited" -ge 600 ]]; then
      echo "another build has held $BUILD_LOCK for 10 minutes; check whether it is still running" >&2
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  trap 'rmdir "$BUILD_LOCK" 2>/dev/null || true' EXIT
  export JEDFLIX_BUILD_LOCK_HELD=1
fi
ARCH="${1:-${ARCH:-arm64}}"
case "$ARCH" in
  arm|armeabi-v7a) LABEL=arm; ABI=armeabi-v7a; BUILD_ID=31048580; SHA=e18a5405bb53e12a903ff477457318b8f8d3c8e1995e0a4c3ff5d8893d1ccf0b ;;
  arm64|arm64-v8a) LABEL=arm64; ABI=arm64-v8a; BUILD_ID=33145732; SHA=caaf4faba423a47c3174eda08b94057956ce32af616c6377b3485f4f5dec993c ;;
  x86_64|x86-64) LABEL=x86_64; ABI=x86_64; BUILD_ID=34194308; SHA=3a9f86646ba18f4f11073dfbb01e10b1d4019e78d89ba58ec3de79f36f6cfc8c ;;
  *) echo "usage: $0 [arm|arm64|x86_64]" >&2; exit 2 ;;
esac
UPSTREAM=".local/upstream/stremio-android-tv-1.10.4-$LABEL.apk"
fetch() { if [[ ! -f "$2" ]]; then curl -fL "$1" -o "$2"; fi; }
fetch "https://dl.strem.io/android/v1.10.4-androidTV/com.stremio.one-1.10.4-$BUILD_ID-$ABI.apk" "$UPSTREAM"
fetch 'https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar' .local/tools/apktool.jar
printf '%s\n' "$SHA  $UPSTREAM" 'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423  .local/tools/apktool.jar' | shasum -a 256 -c -
java -jar .local/tools/apktool.jar d --force --no-src "$UPSTREAM" -o .local/decoded
python3 scripts/rebrand.py .local/decoded
java -jar .local/tools/apktool.jar d --force --no-res "$UPSTREAM" -o .local/theme-code
rm .local/decoded/classes5.dex .local/decoded/classes6.dex
cp -R .local/theme-code/smali_classes5 .local/decoded/smali_classes5
cp -R .local/theme-code/smali_classes6 .local/decoded/smali_classes6
python3 scripts/theme.py .local/decoded
python3 scripts/logo-patch.py .local/decoded
java -jar .local/tools/apktool.jar b .local/decoded -o .local/jedflix-unsigned.apk
"$BUILD_TOOLS/zipalign" -P 16 -f 4 .local/jedflix-unsigned.apk .local/jedflix-aligned.apk
if [[ ! -f .local/jedflix-local.jks ]]; then
  keytool -genkeypair -keystore .local/jedflix-local.jks -storepass android -keypass android -alias jedflix -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Jedflix Local Build'
fi
"$BUILD_TOOLS/apksigner" sign --ks .local/jedflix-local.jks --ks-pass pass:android --key-pass pass:android --out .local/jedflix-rebrand.apk .local/jedflix-aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose .local/jedflix-rebrand.apk
"$BUILD_TOOLS/zipalign" -c -P 16 4 .local/jedflix-rebrand.apk

# Every distributed fork build uses the owner-managed sources and suppresses
# upstream addon promotions. The rebrand above is only the build input.
python3 scripts/spike-build.py --lock-addons
cp dist/jedflix-managed-addons.apk "dist/jedflix-android-tv-1.10.4-$LABEL.apk"
