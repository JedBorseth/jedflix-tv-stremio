#!/usr/bin/env python3
"""Compile the on-device Home discovery helpers and run their host tests.

The verifier is deliberately self-contained: it uses the real org.json jar and
the Android platform jar, compiles the six discovery helpers plus a generated
test-only JedflixTmdbConfig, then runs every scripts/tests/*Test.java class.

It never reads .local/tmdb-api-key. The generated config contains a harmless
placeholder so the build-time key path is type-checked without exposing the
real key.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JSON_VERSION = "20240303"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
JSON_URL = (
    "https://repo.maven.apache.org/maven2/org/json/json/"
    f"{JSON_VERSION}/json-{JSON_VERSION}.jar"
)
JSON_JAR = ROOT / ".local" / "tools" / f"json-{JSON_VERSION}.jar"
TEST_API_KEY = "0123456789abcdef0123456789abcdef"

HELPERS = [
    "scripts/android/DiscoveryRules.java",
    "scripts/android/DiscoverySignals.java",
    "scripts/android/DiscoveryFeed.java",
    "scripts/android/TmdbCatalogClient.java",
    "scripts/android/JedPicksClient.java",
    "scripts/android/TmdbCatalogMapper.java",
    "scripts/android/HomeDiscovery.java",
]

PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)


def fail(message: str) -> None:
    raise SystemExit(f"verify-discovery: error: {message}")


def run(command: list[str], what: str, timeout: int | None = None) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as error:
        rendered = " ".join(command)
        fail(f"{what} timed out after {timeout}s\n  command: {rendered}")
        raise AssertionError("unreachable") from error
    if result.returncode != 0:
        rendered = " ".join(command)
        detail = result.stdout.strip() or "(no output)"
        fail(f"{what} failed\n  command: {rendered}\n{indent(detail)}")
    return result.stdout


def indent(text: str) -> str:
    return "\n".join(f"  {line}" for line in text.splitlines())


def sdk_jar() -> Path:
    candidates: list[Path] = []
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name, "").strip()
        if value:
            candidates.append(Path(value))
    candidates.append(Path.home() / "Library" / "Android" / "sdk")

    seen: set[Path] = set()
    for sdk in candidates:
        if sdk in seen:
            continue
        seen.add(sdk)
        preferred = sdk / "platforms" / "android-36" / "android.jar"
        if preferred.is_file():
            return preferred
        platforms = sdk / "platforms"
        if not platforms.is_dir():
            continue
        installed = []
        for directory in platforms.glob("android-*"):
            jar = directory / "android.jar"
            if jar.is_file():
                try:
                    number = int(directory.name.split("-", 1)[1])
                except (IndexError, ValueError):
                    continue
                installed.append((number, jar))
        if installed:
            installed.sort()
            return installed[-1][1]

    searched = ", ".join(str(path) for path in sorted(seen))
    fail(
        "Android platform jar not found. Set ANDROID_HOME to an SDK with "
        f"platforms/android-36/android.jar. Searched: {searched}"
    )
    raise AssertionError("unreachable")


def json_jar() -> Path:
    if not JSON_JAR.is_file():
        JSON_JAR.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading org.json {JSON_VERSION} to {JSON_JAR.relative_to(ROOT)}")
        try:
            with urllib.request.urlopen(JSON_URL, timeout=60) as response:
                payload = response.read()
            JSON_JAR.write_bytes(payload)
        except Exception as error:
            fail(f"could not download {JSON_URL} to {JSON_JAR}: {error}")

    digest = hashlib.sha256(JSON_JAR.read_bytes()).hexdigest()
    if digest != JSON_SHA256:
        fail(
            f"checksum mismatch for {JSON_JAR.relative_to(ROOT)}: {digest}. "
            "Delete the jar and rerun to fetch the pinned copy."
        )
    return JSON_JAR


def test_sources() -> list[Path]:
    sources = sorted((ROOT / "scripts" / "tests").glob("*Test.java"))
    if len(sources) < 4:
        fail(
            "expected at least four host tests in scripts/tests/*Test.java, "
            f"found {len(sources)}"
        )
    return sources


def class_name(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    match = PACKAGE.search(text)
    if not match:
        fail(f"{path.relative_to(ROOT)} has no package declaration")
    return f"{match.group(1)}.{path.stem}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--keep-temp",
        action="store_true",
        help="keep the temporary compile directory for inspection",
    )
    args = parser.parse_args()

    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        fail("javac and java must be on PATH")

    android = sdk_jar()
    json = json_jar()
    helpers = [ROOT / name for name in HELPERS]
    for helper in helpers:
        if not helper.is_file():
            fail(f"missing helper source: {helper.relative_to(ROOT)}")
    tests = test_sources()

    print(f"Android platform: {android}")
    print(f"org.json: {json.relative_to(ROOT)} (sha256 verified)")
    print(f"Tests: {len(tests)}")

    temporary = tempfile.mkdtemp(prefix="jedflix-discovery-verify-")
    if args.keep_temp:
        print(f"Temporary directory: {temporary}")
    try:
        root = Path(temporary)
        classes = root / "classes"
        helper = root / "generated" / "com" / "jedflix" / "tv"
        classes.mkdir(parents=True)
        helper.mkdir(parents=True)

        config = helper / "JedflixTmdbConfig.java"
        config.write_text(
            "package com.jedflix.tv;\n"
            "public final class JedflixTmdbConfig {\n"
            f'    public static final String API_KEY = "{TEST_API_KEY}";\n'
            "}\n",
            encoding="utf-8",
        )

        classpath = os.pathsep.join([str(json), str(android)])
        compile_helpers = [
            javac,
            "--release",
            "8",
            "-Xlint:-options",
            "-classpath",
            classpath,
            "-d",
            str(classes),
            *[str(path) for path in helpers],
            str(config),
        ]
        print("Compiling discovery helpers against the Android API jar")
        run(compile_helpers, "helper compilation")

        compile_tests = [
            javac,
            "--release",
            "8",
            "-Xlint:-options",
            "-classpath",
            os.pathsep.join([str(classes), classpath]),
            "-d",
            str(classes),
            *[str(path) for path in tests],
        ]
        print("Compiling host tests against the real org.json")
        run(compile_tests, "test compilation")

        runtime = os.pathsep.join([str(classes), classpath])
        for source in tests:
            name = class_name(source)
            print(f"Running {source.relative_to(ROOT)}")
            output = run([java, "-cp", runtime, name], f"{name}", timeout=180)
            for line in output.rstrip().splitlines():
                print(f"  {line}")

        print(f"verify-discovery: OK ({len(tests)} host tests)")
        return 0
    finally:
        if not args.keep_temp:
            shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
