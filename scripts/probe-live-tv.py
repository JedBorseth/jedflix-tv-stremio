#!/usr/bin/env python3
"""Build the read-only Live TV instrumentation probe.

By default this only builds and signs the probe APK. Pass --run-device to
install the probe on the configured device (the scripts/adb.sh default target),
run it with `am instrument -w`, and uninstall it. The target app is never
rebuilt, reinstalled, or otherwise modified, though Android may restart the
target process when instrumentation starts.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
PROBE_CLASS = "com.jedflix.test.LiveTvProbe"
PROBE_PACKAGE = "com.jedflix.livetvprobe"
TARGET_PACKAGE = "com.jedflix.stremio"
MIN_CHECKS = 216
KEYSTORE = ROOT / ".local" / "jedflix-local.jks"


def fail(message: str) -> None:
    raise SystemExit(f"probe-live-tv: error: {message}")


def indent(text: str) -> str:
    return "\n".join(f"  {line}" for line in text.splitlines())


def run(command: list[object], what: str, timeout: int | None = None) -> str:
    rendered = [str(part) for part in command]
    try:
        result = subprocess.run(
            rendered,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        fail(f"{what} timed out\n  command: {' '.join(rendered)}")
    if result.returncode != 0:
        detail = result.stdout.strip() or "(no output)"
        fail(f"{what} failed\n  command: {' '.join(rendered)}\n{indent(detail)}")
    return result.stdout


def discovery_helpers():
    """Reuse the Android SDK and platform-jar discovery from probe-discovery."""
    path = ROOT / "scripts" / "probe-discovery.py"
    if not path.is_file():
        fail(f"missing helper script {path.relative_to(ROOT)}")
    spec = importlib.util.spec_from_file_location("jedflix_probe_discovery", str(path))
    if spec is None or spec.loader is None:
        fail(f"could not load {path.relative_to(ROOT)}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_probe(android: Path, tools: Path) -> Path:
    if not KEYSTORE.is_file():
        fail(f"missing signing key {KEYSTORE}")

    build = ROOT / ".local" / "live-tv-probe"
    shutil.rmtree(build, ignore_errors=True)
    classes = build / "classes"
    dex = build / "dex"
    classes.mkdir(parents=True)
    dex.mkdir(parents=True)

    manifest = build / "AndroidManifest.xml"
    manifest.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{PROBE_PACKAGE}">\n'
        '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>\n'
        '<application android:label="Jedflix live TV probe"/>\n'
        f'<instrumentation android:name="{PROBE_CLASS}" '
        f'android:targetPackage="{TARGET_PACKAGE}"/>\n'
        "</manifest>\n",
        encoding="utf-8",
    )

    source = ROOT / "scripts" / "android" / "LiveTvProbe.java"
    run(
        [
            "javac",
            "-source", "8",
            "-target", "8",
            "-Xlint:-options",
            "-bootclasspath", android,
            "-d", classes,
            source,
        ],
        "probe compilation",
    )

    class_files = sorted(classes.rglob("*.class"))
    if not class_files:
        fail("probe compilation produced no class files")
    run(
        [tools / "d8", "--lib", android, "--min-api", "26", "--output", dex]
        + class_files,
        "probe dexing",
    )

    unsigned = build / "unsigned.apk"
    run(
        [tools / "aapt2", "link", "-I", android, "--manifest", manifest,
         "-o", unsigned],
        "probe manifest link",
    )

    classes_dex = dex / "classes.dex"
    if not classes_dex.is_file():
        fail("d8 did not produce classes.dex")
    with ZipFile(unsigned, "a") as archive:
        archive.write(classes_dex, "classes.dex")

    aligned = build / "aligned.apk"
    run([tools / "zipalign", "-f", "4", unsigned, aligned], "probe alignment")

    signed = build / "probe.apk"
    run(
        [
            tools / "apksigner", "sign",
            "--ks", KEYSTORE,
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--out", signed,
            aligned,
        ],
        "probe signing",
    )
    run([tools / "apksigner", "verify", signed], "probe signature verification")
    return signed


def run_on_device(signed: Path) -> None:
    adb = ROOT / "scripts" / "adb.sh"
    log = ROOT / ".local" / "live-tv-probe-instrumentation.txt"
    try:
        install = run([adb, "install", "-r", signed], "probe install")
        print(install, end="")

        result = subprocess.run(
            [str(adb), "shell", "am", "instrument", "-w",
             f"{PROBE_PACKAGE}/{PROBE_CLASS}"],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=300,
        )
        output = result.stdout
        log.write_text(output, encoding="utf-8")
        print(output, end="")

        if "INSTRUMENTATION_CODE: -1" not in output:
            fail("instrumentation did not report success "
                 "(INSTRUMENTATION_CODE: -1)\n" + indent(output))
        match = re.search(r"result=PASS: (\d+) live TV checks", output)
        if not match:
            fail("missing live TV PASS result\n" + indent(output))
        checks = int(match.group(1))
        if checks < MIN_CHECKS:
            fail(f"only {checks} live TV checks passed (expected at least {MIN_CHECKS})")
        print(f"probe-live-tv: OK ({checks} checks); log: {log.relative_to(ROOT)}")
    except subprocess.TimeoutExpired:
        fail("instrumentation timed out after 300 seconds")
    finally:
        cleanup = subprocess.run(
            [str(adb), "uninstall", PROBE_PACKAGE],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        if cleanup.returncode != 0:
            print(
                f"probe-live-tv: warning: could not uninstall {PROBE_PACKAGE}: "
                f"{cleanup.stdout.strip()}",
                file=sys.stderr,
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--run-device",
        action="store_true",
        help=(
            "Install the probe on the scripts/adb.sh default target, run it, "
            "and uninstall it. Without this flag only the probe APK is built. "
            "Instrumentation may restart the target app process."
        ),
    )
    args = parser.parse_args()

    helpers = discovery_helpers()
    sdk = helpers.sdk_root()
    tools = helpers.build_tools(sdk)
    android = helpers.platform_jar(sdk)
    print(f"Android platform: {android}")
    print(f"Build tools: {tools}")

    signed = build_probe(android, tools)
    print(f"Probe APK: {signed.relative_to(ROOT)}")

    if not args.run_device:
        print("Built only; pass --run-device to install and run it on the "
              "scripts/adb.sh default target.")
        return 0

    run_on_device(signed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
