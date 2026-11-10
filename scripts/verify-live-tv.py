#!/usr/bin/env python3
"""Compile the pure-Java live TV schedule helper and run its host tests.

Unlike the discovery verifier this script does not need the Android platform
jar: LiveTvSchedule depends only on Java 8 and org.json. It reuses the pinned
org.json downloader/checksum from verify-discovery.py, then compiles the helper
plus every future scripts/tests/LiveTv*Test.java file.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPERS = [
    "scripts/android/LiveTvSchedule.java",
    "scripts/android/LiveTvStreamRules.java",
]
TEST_GLOB = "LiveTv*Test.java"
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)


def fail(message: str) -> None:
    raise SystemExit(f"verify-live-tv: error: {message}")


def indent(text: str) -> str:
    return "\n".join(f"  {line}" for line in text.splitlines())


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


def json_jar() -> Path:
    """Reuse the real, pinned org.json jar loader from verify-discovery.py."""
    verifier = ROOT / "scripts" / "verify-discovery.py"
    if not verifier.is_file():
        fail(f"missing verifier to load org.json from: {verifier.relative_to(ROOT)}")
    spec = importlib.util.spec_from_file_location("jedflix_verify_discovery", str(verifier))
    if spec is None or spec.loader is None:
        fail(f"could not load {verifier.relative_to(ROOT)}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    loader = getattr(module, "json_jar", None)
    if loader is None:
        fail(f"{verifier.relative_to(ROOT)} does not expose json_jar()")
    return loader()


def test_sources() -> list[Path]:
    sources = sorted((ROOT / "scripts" / "tests").glob(TEST_GLOB))
    if not sources:
        fail(f"expected at least one scripts/tests/{TEST_GLOB} test")
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

    json = json_jar()
    helpers = [ROOT / name for name in HELPERS]
    for helper in helpers:
        if not helper.is_file():
            fail(f"missing helper source: {helper.relative_to(ROOT)}")
    tests = test_sources()

    print(f"org.json: {json.relative_to(ROOT)} (sha256 verified)")
    print(f"Tests: {len(tests)}")

    temporary = tempfile.mkdtemp(prefix="jedflix-live-tv-verify-")
    if args.keep_temp:
        print(f"Temporary directory: {temporary}")
    try:
        root = Path(temporary)
        classes = root / "classes"
        classes.mkdir(parents=True)

        compile_all = [
            javac,
            "--release",
            "8",
            "-Xlint:-options",
            "-classpath",
            str(json),
            "-d",
            str(classes),
            *[str(path) for path in helpers],
            *[str(path) for path in tests],
        ]
        print("Compiling live TV schedule and host tests against the real org.json")
        run(compile_all, "compilation")

        runtime = os.pathsep.join([str(classes), str(json)])
        for source in tests:
            name = class_name(source)
            print(f"Running {source.relative_to(ROOT)}")
            output = run([java, "-cp", runtime, name], f"{name}", timeout=180)
            for line in output.rstrip().splitlines():
                print(f"  {line}")

        print(f"verify-live-tv: OK ({len(tests)} host tests)")
        return 0
    finally:
        if not args.keep_temp:
            shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
