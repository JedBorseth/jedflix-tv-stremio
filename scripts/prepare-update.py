#!/usr/bin/env python3
"""Validate signed APKs and write the manifest consumed by Jedflix's updater.

Does not publish anything. Upload the resulting APKs and the generated update manifest
together as assets of the stable GitHub release printed by this command.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def prepare(apks, output, notes, compat=False):
    config = json.loads((ROOT / "assets/update-config.json").read_text())
    expected_package = "com.stremio.one" if compat else config["packageName"]
    prefix = "jedflix" if compat else config["artifactPrefix"]
    manifest_name = "jedflix-update.json" if compat else config["manifestName"]
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    tools = Path(os.environ.get("BUILD_TOOLS", str(sdk / "build-tools/36.0.0")))
    artifacts = {}
    identity = None
    manifest = None
    output.mkdir(parents=True, exist_ok=True)
    for apk in apks:
        signed = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
        subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
        certs = sorted(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", signed))
        if not certs:
            raise ValueError(f"Cannot identify signing certificate: {apk}")
        badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
        package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
        if not package:
            raise ValueError(f"Cannot read APK identity: {apk}")
        name, code, version = package.groups()
        if name != expected_package or int(code) <= 34194308 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
            raise ValueError(f"Not a versioned Jedflix release: {apk}")
        xml = subprocess.check_output([str(tools / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], text=True)
        if name != "com.stremio.one" and re.search(r'="com\.stremio\.one\.', xml):
            raise ValueError(f"APK still declares or references a stock Stremio permission/provider identity: {apk}")
        if "android.permission.REQUEST_INSTALL_PACKAGES" not in badging:
            raise ValueError(f"APK has no self-update permission: {apk}")
        minimum = int(re.search(r"(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging).group(1))
        current = (name, int(code), version, minimum, certs)
        if identity is not None and identity != current:
            raise ValueError("All APKs must have the same package, version, minimum SDK, and signing certificates")
        identity = current
        with zipfile.ZipFile(apk) as archive:
            abis = {p.split('/')[1] for p in archive.namelist() if p.startswith('lib/') and p.endswith('.so')}
        if len(abis) != 1 or not abis <= {"arm64-v8a", "x86_64"}:
            raise ValueError(f"Expected one supported ABI: {apk}: {abis}")
        abi = abis.pop()
        if abi in artifacts:
            raise ValueError(f"Duplicate architecture: {abi}")
        filename = f"{prefix}-{version}-{abi}.apk"
        with apk.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        artifacts[abi] = {"url": f"https://github.com/{config['repository']}/releases/download/v{version}/{filename}",
                          "size": apk.stat().st_size, "sha256": digest}
        destination = output / filename
        if destination.resolve() != apk.resolve():
            shutil.copyfile(apk, destination)
        manifest = {"schemaVersion": 1, "packageName": name, "versionCode": int(code),
                    "versionName": version, "minSdk": minimum, "notes": notes, "artifacts": artifacts}
    destination = output / manifest_name
    destination.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Prepared {destination} for {config['repository']} stable release v{identity[2]} ({', '.join(artifacts)})")
    print("Publish the manifest and all listed APKs together. Keep the signing keystore backed up.")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    parser.add_argument('--notes', type=Path, help='Plain-text release notes file')
    parser.add_argument('--compat', action='store_true', help='Prepare the original package handoff manifest for existing updaters')
    args = parser.parse_args()
    prepare(args.apks, args.output, args.notes.read_text() if args.notes else '', args.compat)
