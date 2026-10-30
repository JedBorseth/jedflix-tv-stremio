#!/usr/bin/env python3
"""Build the experimental guest/player probe separately from the regular APK."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import argparse
import json
import hashlib
import urllib.request
import re

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
parser = argparse.ArgumentParser()
parser.add_argument("--lock-addons", action="store_true", help="Remove user addon management and reject mutations")
parser.add_argument("--fresh-test", action="store_true", help="Build an isolated package for fresh-install verification")
args = parser.parse_args()
assert not args.fresh_test or args.lock_addons
output = "dist/jedflix-managed-addons.apk" if args.lock_addons else "dist/jedflix-feasibility-spike.apk"
if args.fresh_test:
    output = ".local/jedflix-fresh-addon-test.apk"
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
BUILD_TOOLS = Path(os.environ.get("BUILD_TOOLS", str(SDK / "build-tools/36.0.0")))

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

if not Path(".local/decoded/apktool.yml").exists():
    raise SystemExit("Run ./scripts/build.sh first to fetch, verify, and rebrand the upstream APK")

qr_library = Path(".local/tools/zxing-core-3.5.3.jar")
if args.lock_addons:
    if not qr_library.exists():
        urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar", qr_library)
    if hashlib.sha256(qr_library.read_bytes()).hexdigest() != "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82":
        raise SystemExit("ZXing checksum mismatch; remove .local/tools/zxing-core-3.5.3.jar and retry")

with tempfile.TemporaryDirectory(prefix="spike-stage-", dir=".local") as temporary:
    stage = Path(temporary) / "decoded"
    shutil.copytree(".local/decoded", stage)
    # Reuse the regular build's themed Compose palette before applying the probe.
    if not (stage / "smali_classes6").exists():
        raise SystemExit("Run ./scripts/build.sh again to prepare themed bytecode")
    run("python3", "scripts/spike-patch.py", stage)
    if args.lock_addons:
        shutil.copyfile("assets/zxing-LICENSE.txt", stage / "assets/zxing-LICENSE.txt")
        # Compose translations live in DEX 5 and 9; patch their addon upsells
        # alongside XML strings. The regular build prepares this pinned decode.
        shutil.copytree(".local/theme-code/smali_classes9", stage / "smali_classes9")
        (stage / "classes9.dex").unlink()
        run("python3", "scripts/addon-lock-patch.py", stage)
        run("python3", "scripts/comet-settings-patch.py", stage)
        run("python3", "scripts/update-patch.py", stage)
        update_config = json.loads(Path("assets/update-config.json").read_text())
        version_name = os.environ.get("JEDFLIX_VERSION_NAME", update_config["versionName"])
        version_code = int(os.environ.get("JEDFLIX_VERSION_CODE", update_config["versionCode"]))
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version_name) or not 34194308 < version_code <= 2100000000:
            raise SystemExit("Jedflix needs a dotted release version and a versionCode above both upstream APKs")
        metadata = stage / "apktool.yml"
        text = re.sub(r"(?m)^  versionCode: .+$", f"  versionCode: {version_code}", metadata.read_text())
        text = re.sub(r"(?m)^  versionName: .+$", f"  versionName: {version_name}", text)
        metadata.write_text(text)
        # The helper has no dependency on upstream app classes; keep it in a
        # separate DEX so the native libraries and other DEX files stay intact.
        helper = Path(temporary) / "helper"
        classes = helper / "classes"
        dex = helper / "dex"
        classes.mkdir(parents=True)
        dex.mkdir()
        presets = json.loads(Path("assets/managed-addons.json").read_text())
        assert [a["manifest"]["id"] for a in presets] == ["com.linvo.cinemeta", "comet.elfhosted.com"]
        generated = helper / "ManagedAddonPreset.java"
        generated.write_text("package com.jedflix.tv; public final class ManagedAddonPreset {"
            + "public static final String ADDONS = " + json.dumps(json.dumps(presets, separators=(",", ":"))) + ";"
            + "public static final String DEFAULT_SETTINGS = " + json.dumps(Path("assets/default-player-settings.json").read_text()) + ";}")
        build_config = helper / "JedflixBuild.java"
        build_config.write_text("package com.jedflix.tv; public final class JedflixBuild {"
            + "public static final String VERSION_NAME = " + json.dumps(version_name) + ";"
            + "public static final long VERSION_CODE = " + str(version_code) + "L;"
            + "public static final String REPOSITORY = " + json.dumps(update_config["repository"]) + ";}")
        android = SDK / "platforms/android-36/android.jar"
        stubs = helper / "stubs"
        stubs.mkdir()
        run("javac", "--release", "8", "-classpath", android, "-d", stubs,
            *Path("scripts/android/stubs").rglob("*.java"))
        run("javac", "--release", "8", "-d", classes,
            "-classpath", os.pathsep.join([str(android), str(stubs), str(qr_library)]), "scripts/android/ManagedAddonConfig.java",
            "scripts/android/CometConfig.java", "scripts/android/CometSettings.java",
            "scripts/android/RdKeyPairing.java", "scripts/android/RdKeyPairingDialog.java",
            "scripts/android/RdAccountClient.java",
            "scripts/android/CometRestartActivity.java", "scripts/android/UpdateClient.java",
            "scripts/android/JedflixUpdates.java",
            "scripts/android/AboutSettings.java", generated, build_config)
        run(BUILD_TOOLS / "d8", "--lib", android, "--min-api", "26", "--output", dex, *classes.rglob("*.class"), qr_library)
        shutil.copyfile(dex / "classes.dex", stage / "classes10.dex")
        if args.fresh_test:
            import xml.etree.ElementTree as ET
            manifest_path = stage / "AndroidManifest.xml"
            manifest = ET.parse(manifest_path)
            manifest.getroot().set("package", "com.jedflix.presettest")
            for element in manifest.iter():
                for key, value in list(element.attrib.items()):
                    if key.endswith("}authorities"):
                        element.set(key, value.replace("com.stremio.one", "com.jedflix.presettest"))
            manifest.write(manifest_path, encoding="utf-8", xml_declaration=True)
    run("java", "-jar", ".local/tools/apktool.jar", "b", stage, "-o", ".local/spike-unsigned.apk")
run(BUILD_TOOLS / "zipalign", "-P", "16", "-f", "4", ".local/spike-unsigned.apk", ".local/spike-aligned.apk")
run(BUILD_TOOLS / "apksigner", "sign", "--ks", ".local/jedflix-local.jks",
    "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out",
    output, ".local/spike-aligned.apk")
run(BUILD_TOOLS / "apksigner", "verify", output)
run(BUILD_TOOLS / "zipalign", "-c", "-P", "16", "4", output)
if args.lock_addons and not args.fresh_test:
    abis = [p.name for p in Path(".local/decoded/lib").iterdir() if p.is_dir()]
    assert len(abis) == 1, f"Expected a single-ABI build: {abis}"
    shutil.copyfile(output, f"dist/jedflix-{version_name}-{abis[0]}.apk")
