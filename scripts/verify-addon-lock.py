#!/usr/bin/env python3
"""Run a separate instrumentation APK against the managed-addon build."""
from pathlib import Path
import os
import subprocess
import argparse
import re
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
parser = argparse.ArgumentParser()
parser.add_argument("--fresh-test", action="store_true")
parser.add_argument("--comet-test", action="store_true", help="Test Comet save/restart in the isolated fresh-test package")
parser.add_argument("--qr-test", action="store_true", help="Test QR pairing with synthetic keys in the isolated fresh-test package")
parser.add_argument("--account-test", action="store_true", help="Test account validation and encrypted identity caching using mock responses")
args = parser.parse_args()
assert not args.comet_test or args.fresh_test, "Comet credential tests require the disposable --fresh-test package"
assert not args.qr_test or args.fresh_test, "QR tests require the disposable --fresh-test package"
assert not args.account_test or args.fresh_test, "Account tests require disposable storage"
assert sum([args.account_test, args.qr_test, args.comet_test]) <= 1, "Run verification suites separately"
probe = "RdAccountProbe" if args.account_test else "RdPairingProbe" if args.qr_test else "AddonLockProbe"
target_package = "com.jedflix.presettest" if args.fresh_test else "com.stremio.one"
target_apk = ".local/jedflix-fresh-addon-test.apk" if args.fresh_test else "dist/jedflix-managed-addons.apk"
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
tools = Path(os.environ.get("BUILD_TOOLS", str(sdk / "build-tools/36.0.0")))
android = sdk / "platforms/android-36/android.jar"
build = ROOT / ".local/addon-lock-probe"
classes = build / "classes"
dex = build / "dex"
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(exist_ok=True)
manifest = build / "AndroidManifest.xml"
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.jedflix.addonlockprobe">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:label="Jedflix addon policy test"/>
<instrumentation android:name="com.jedflix.test.{probe}" android:targetPackage="{target_package}"/>
</manifest>''')

def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)

run("javac", "-source", "8", "-target", "8", "-bootclasspath", android,
    "-d", classes, f"scripts/android/{probe}.java")
run(tools / "d8", "--lib", android, "--min-api", "26", "--output", dex, *classes.rglob("*.class"))
unsigned = build / "unsigned.apk"
run(tools / "aapt2", "link", "-I", android, "--manifest", manifest, "-o", unsigned)
with ZipFile(unsigned, "a") as archive:
    archive.write(dex / "classes.dex", "classes.dex")
aligned = build / "aligned.apk"
run(tools / "zipalign", "-f", "4", unsigned, aligned)
signed = build / "probe.apk"
run(tools / "apksigner", "sign", "--ks", ".local/jedflix-local.jks", "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--out", signed, aligned)
adb = sdk / "platform-tools/adb"
run(adb, "install", "-r", target_apk)
run(adb, "install", "-r", signed)
try:
    for phase in (["save", "ui", "restored"] if args.comet_test else [None]):
        if phase == "ui":
            # Run the real UI without target-process instrumentation: Android
            # force-stops every package process if instrumentation is killed.
            run(adb, "shell", "am", "force-stop", target_package)
            run(adb, "shell", "am", "start", "-W", "-n", target_package + "/com.stremio.tv.MainActivity")
            def snapshot():
                run(adb, "shell", "uiautomator", "dump", "/sdcard/jedflix-comet-test.xml", capture_output=True)
                return ET.fromstring(run(adb, "shell", "cat", "/sdcard/jedflix-comet-test.xml", capture_output=True, text=True).stdout)
            def tap(text):
                for node in snapshot().iter("node"):
                    if node.get("text") != text: continue
                    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
                    if bottom > top and top >= 0 and bottom <= 1080:
                        run(adb, "shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
                        return True
                return False
            opened = False
            for _ in range(5):
                run(adb, "shell", "input", "keyevent", "21")
                if tap("Settings"):
                    opened = True
                    break
            assert opened, "Settings navigation unavailable"
            assert any(tap("Real-Debrid") for _ in range(3)), "Real-Debrid tab unavailable"
            before = run(adb, "shell", "pidof", target_package, capture_output=True, text=True).stdout.strip()
            clicked = False
            for _ in range(5):
                if tap("Save and restart"):
                    clicked = True
                    break
                run(adb, "shell", "input", "swipe", "1300", "960", "1300", "300", "350")
            assert clicked, "Save button unavailable"
            deadline = time.monotonic() + 25
            restarted = False
            while time.monotonic() < deadline:
                process = subprocess.run([str(adb), "shell", "pidof", target_package], capture_output=True, text=True)
                activity = run(adb, "shell", "dumpsys", "activity", "activities", capture_output=True, text=True).stdout
                resumed = any(target_package in line and "com.stremio.tv.MainActivity" in line
                    and ("mResumedActivity" in line or "topResumedActivity" in line) for line in activity.splitlines())
                if process.stdout.strip() and process.stdout.strip() != before and resumed:
                    restarted = True
                    break
                time.sleep(0.5)
            assert restarted, "Save did not automatically reopen Jedflix"
            print("PASS: Save button restarted Jedflix automatically")
            continue
        command = [adb, "shell", "am", "instrument", "-w"]
        if phase:
            run(adb, "shell", "am", "force-stop", target_package)
            command += ["-e", "cometPhase", phase]
        result = run(*command, f"com.jedflix.addonlockprobe/com.jedflix.test.{probe}",
                     capture_output=True, text=True)
        suffix = "fresh" if args.fresh_test else "upgrade"
        if phase: suffix += "-comet-" + phase
        if args.qr_test: suffix += "-qr"
        if args.account_test: suffix += "-account"
        (ROOT / f".local/addon-lock-instrumentation-{suffix}.txt").write_text(result.stdout + result.stderr)
        print(result.stdout)
        count = re.search(r"result=PASS: (\d+) " + ("account" if args.account_test else "QR pairing" if args.qr_test else "addon policy") + " checks", result.stdout)
        assert count and int(count[1]) >= (20 if args.qr_test or args.account_test else 35) and "INSTRUMENTATION_CODE: -1" in result.stdout, "Instrumentation did not pass"
finally:
    run(adb, "uninstall", "com.jedflix.addonlockprobe")
    if args.fresh_test:
        run(adb, "uninstall", target_package)
