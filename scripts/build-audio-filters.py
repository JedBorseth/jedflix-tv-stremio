#!/usr/bin/env python3
"""Build the small FFmpeg 8.1 libavfilter replacement for bundled mpv.

Only libavfilter is shipped. Temporary avutil/swresample builds supply link-time
metadata; the result is checked against the actual upstream APK dependencies.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".local/audio-filters"
FFMPEG_URL = "https://ffmpeg.org/releases/ffmpeg-8.1.tar.xz"
FFMPEG_SHA = "b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a"
NDK_URL = "https://dl.google.com/android/repository/android-ndk-r28c-darwin.dmg"
NDK_SHA = "a7a2513d4c229f814e1a3bc528fe04ed0807b63f57a6d21f09d75e8362e44a20"
NDK_REVISION = "28.2.13676358"
FILTERS = ["acompressor", "alimiter", "volume", "aformat", "aresample", "pan", "anull"]
ABIS = {
    "armeabi-v7a": ("arm", "arm-linux-androideabi", "31048580", "e18a5405bb53e12a903ff477457318b8f8d3c8e1995e0a4c3ff5d8893d1ccf0b"),
    "x86_64": ("x86_64", "x86_64-linux-android", "34194308", "3a9f86646ba18f4f11073dfbb01e10b1d4019e78d89ba58ec3de79f36f6cfc8c"),
    "arm64-v8a": ("arm64", "aarch64-linux-android", "33145732", "caaf4faba423a47c3174eda08b94057956ce32af616c6377b3485f4f5dec993c"),
}


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def checked(path: Path, expected: str) -> None:
    actual = sha(path)
    if actual != expected:
        raise RuntimeError(f"SHA-256 mismatch for {path}: {actual} != {expected}")


def fetch(url: str, path: Path, expected: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        print(f"Downloading {url}", flush=True)
        temp = path.with_suffix(path.suffix + ".part")
        urllib.request.urlretrieve(url, temp)
        checked(temp, expected)
        temp.rename(path)
    checked(path, expected)


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def output(args) -> str:
    return subprocess.check_output([str(a) for a in args], text=True)


def ndk_root() -> Path:
    explicit = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if explicit:
        root = Path(explicit).resolve()
    else:
        if platform.system() != "Darwin":
            raise RuntimeError("Automatic NDK bootstrap supports macOS. Set ANDROID_NDK_HOME to official NDK r28c on other hosts.")
        image = CACHE / "downloads/android-ndk-r28c-darwin.dmg"
        fetch(NDK_URL, image, NDK_SHA)
        mount = CACHE / "ndk-mount"
        mount.mkdir(parents=True, exist_ok=True)
        roots = list(mount.glob("*.app/Contents/NDK"))
        if not roots:
            run(["hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount, image])
            roots = list(mount.glob("*.app/Contents/NDK"))
        if len(roots) != 1:
            raise RuntimeError(f"Cannot find NDK in {mount}")
        root = roots[0]
    properties = (root / "source.properties").read_text()
    if not re.search(r"Pkg.Revision\s*=\s*" + re.escape(NDK_REVISION) + r"\s*$", properties, re.M):
        raise RuntimeError(f"Use pinned NDK {NDK_REVISION}; got {properties!r}")
    return root


def toolchain(ndk: Path) -> Path:
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(platform.system())
    if not host:
        raise RuntimeError("Run on macOS or Linux with NDK r28c")
    return ndk / "toolchains/llvm/prebuilt" / host


def source_tree() -> Path:
    archive = CACHE / "downloads/ffmpeg-8.1.tar.xz"
    fetch(FFMPEG_URL, archive, FFMPEG_SHA)
    source = CACHE / "src/ffmpeg-8.1"
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            tar.extractall(source.parent, filter="data")
    return source


def original_libraries(abi: str, dest: Path) -> tuple[Path, dict]:
    label, _, build_id, checksum = ABIS[abi]
    apk = ROOT / f".local/upstream/stremio-android-tv-1.10.4-{label}.apk"
    url = f"https://dl.strem.io/android/v1.10.4-androidTV/com.stremio.one-1.10.4-{build_id}-{abi}.apk"
    fetch(url, apk, checksum)
    dest.mkdir(parents=True, exist_ok=True)
    hashes = {}
    with zipfile.ZipFile(apk) as z:
        for lib in ["libavfilter.so", "libavutil.so", "libswresample.so"]:
            data = z.read(f"lib/{abi}/{lib}")
            if b"FFmpeg version n8.1\0" not in data:
                raise RuntimeError(f"{abi}/{lib} is not the expected FFmpeg n8.1")
            path = dest / lib
            if not path.exists() or path.read_bytes() != data:
                path.write_bytes(data)
            hashes[lib] = sha(path)
    return apk, {"apk_url": url, "apk_sha256": checksum, "libraries_sha256": hashes}


def symbols(readelf: Path, path: Path) -> tuple[set[str], set[str]]:
    defined, undefined = set(), set()
    for line in output([readelf, "--dyn-syms", "--wide", path]).splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        if fields[4] not in ("GLOBAL", "WEAK") or fields[5] not in ("DEFAULT", "PROTECTED"):
            continue
        name = fields[7].replace("@@", "@")
        if fields[6] == "UND":
            if fields[4] != "WEAK":
                undefined.add(name)
        else:
            defined.add(name)
    return defined, undefined


def validate(abi: str, library: Path, deps: Path, tc: Path) -> dict:
    readelf = tc / "bin/llvm-readelf"
    old_exports, _ = symbols(readelf, deps / "libavfilter.so")
    exports, imports = symbols(readelf, library)
    missing_exports = old_exports - exports
    if missing_exports:
        raise RuntimeError(f"Replacement drops/version-changes original exports: {sorted(missing_exports)}")
    if not any(x.endswith("@LIBAVFILTER_11") for x in exports):
        raise RuntimeError("Replacement must retain LIBAVFILTER_11 symbol versions")
    dynamic = output([readelf, "-dW", library])
    needed = re.findall(r"\(NEEDED\).*?\[([^]]+)\]", dynamic)
    if "Library soname: [libavfilter.so]" not in dynamic:
        raise RuntimeError("Unexpected SONAME")
    providers: set[str] = set()
    for dep in needed:
        if dep in ("libavutil.so", "libswresample.so"):
            provider = deps / dep
        elif dep in ("libc.so", "libm.so", "libdl.so"):
            provider = tc / "sysroot/usr/lib" / ABIS[abi][1] / "23" / dep
        else:
            raise RuntimeError(f"Unapproved dependency {dep}")
        providers.update(symbols(readelf, provider)[0])
    missing = imports - providers
    if missing:
        raise RuntimeError(f"Undefined imports absent from original FFmpeg/API23 libraries: {sorted(missing)}")
    segments = output([readelf, "-lW", library])
    alignments = [int(line.split()[-1], 16) for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    if not alignments or any(x < 16384 for x in alignments):
        raise RuntimeError(f"ELF segments are not 16 KB aligned: {alignments}")
    header = output([readelf, "-h", library])
    machine = {"x86_64": "Advanced Micro Devices X86-64", "arm64-v8a": "AArch64", "armeabi-v7a": "ARM"}[abi]
    elf_class = "ELF32" if abi == "armeabi-v7a" else "ELF64"
    if not re.search(r"Machine:\s*" + re.escape(machine) + r"\s*$", header, re.M) or elf_class not in header:
        raise RuntimeError(f"Unexpected ELF machine for {abi}")
    return {"original_exports_retained": len(old_exports), "resolved_imports": len(imports),
            "needed": needed, "load_alignments": alignments, "symbol_version": "LIBAVFILTER_11",
            "android_api": 23, "abi": abi}


def configure_args(abi: str, tc: Path, source: Path) -> list[str]:
    target = compiler_target(abi)
    b = tc / "bin"
    return [str(source / "configure"), "--target-os=android", "--enable-cross-compile",
            "--arch=" + {"x86_64": "x86_64", "arm64-v8a": "aarch64", "armeabi-v7a": "arm"}[abi],
            "--cpu=" + ("armv7-a" if abi == "armeabi-v7a" else "generic"),
            f"--cc={b}/{target}23-clang", f"--cxx={b}/{target}23-clang++",
            f"--ar={b}/llvm-ar", f"--nm={b}/llvm-nm", f"--ranlib={b}/llvm-ranlib", f"--strip={b}/llvm-strip",
            "--disable-autodetect", "--disable-everything", "--disable-programs", "--disable-doc",
            "--disable-avcodec", "--disable-avformat", "--disable-avdevice", "--disable-swscale", "--disable-network",
            "--disable-static", "--enable-shared", "--enable-avfilter", "--enable-swresample",
            "--enable-gpl", "--enable-version3", "--disable-x86asm", "--extra-ldflags=-Wl,-z,max-page-size=16384",
            "--enable-filter=" + ",".join(FILTERS)]


def compiler_target(abi: str) -> str:
    # NDK names the ARM compiler armv7a but the matching sysroot arm.
    return "armv7a-linux-androideabi" if abi == "armeabi-v7a" else ABIS[abi][1]


def compile_probe(abi: str, tc: Path, source: Path, build: Path, dest: Path) -> None:
    # Link directly to the existing APK's dependencies, never the temporary builds.
    run([tc / f"bin/{compiler_target(abi)}23-clang", "-O2", "-std=c11", "-D_DEFAULT_SOURCE",
         "-Wno-deprecated-declarations", "-I" + str(source), "-I" + str(build),
         ROOT / "scripts/native/audio-filter-probe.c", "-L" + str(dest), "-L" + str(dest / "original"),
         "-lavfilter", "-lavutil", "-lswresample", "-lm", "-Wl,-z,max-page-size=16384",
         "-Wl,-rpath,$ORIGIN", "-o", dest / "audio-filter-probe"])


def build_audio(abi: str, force: bool) -> None:
    dest = CACHE / abi
    dest.mkdir(parents=True, exist_ok=True)
    _, upstream = original_libraries(abi, dest / "original")
    ndk = ndk_root()
    tc = toolchain(ndk)
    source = source_tree()
    build = CACHE / "build" / abi
    build.mkdir(parents=True, exist_ok=True)
    configure = configure_args(abi, tc, source)
    inputs = {"ffmpeg_url": FFMPEG_URL, "ffmpeg_sha256": FFMPEG_SHA, "ndk_revision": NDK_REVISION,
              "ndk_macos_url": NDK_URL, "ndk_macos_sha256": NDK_SHA, "upstream": upstream,
              "filters": FILTERS, "configure": configure,
              "recipe_sha256": sha(Path(__file__)), "probe_source_sha256": sha(ROOT / "scripts/native/audio-filter-probe.c")}
    manifest_path = dest / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    library = dest / "libavfilter.so"
    probe = dest / "audio-filter-probe"
    cached = (not force and manifest.get("inputs") == inputs and library.exists() and probe.exists()
              and manifest.get("libavfilter_sha256") == sha(library) and manifest.get("probe_sha256") == sha(probe))
    if not cached:
        print(f"Configuring FFmpeg audio filters for {abi}; logs in {CACHE}", flush=True)
        with (CACHE / f"configure-{abi}.log").open("w") as log:
            run(configure, cwd=build, stdout=log, stderr=subprocess.STDOUT)
        with (CACHE / f"make-{abi}.log").open("w") as log:
            run(["make", "-j" + str(min(os.cpu_count() or 4, 8)), "libavfilter/libavfilter.so"],
                cwd=build, stdout=log, stderr=subprocess.STDOUT)
        shutil.copyfile(build / "libavfilter/libavfilter.so", library)
        run([tc / "bin/llvm-strip", "--strip-unneeded", library])
        compile_probe(abi, tc, source, build, dest)
    validation = validate(abi, library, dest / "original", tc)
    manifest = {"inputs": inputs, "validation": validation,
                "libavfilter_sha256": sha(library), "probe_sha256": sha(probe),
                "license": "GPL-3.0-or-later", "ffmpeg_source_changes": "none"}
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"cached": cached, "library": str(library), "manifest": str(manifest_path),
                      "probe": str(probe), "validation": validation}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=[*ABIS, "arm", "arm64"], required=True)
    parser.add_argument("--force", action="store_true", help="reconfigure/rebuild instead of reusing a checked cached result")
    args = parser.parse_args()
    abi = {"arm": "armeabi-v7a", "arm64": "arm64-v8a"}.get(args.abi, args.abi)
    CACHE.mkdir(parents=True, exist_ok=True)
    with (CACHE / f"build-{abi}.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        build_audio(abi, args.force)


if __name__ == "__main__":
    main()
