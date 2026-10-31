#!/usr/bin/env python3
"""Verify native DSP and real libmpv/Night mode using a separate probe APK.

Install the x86_64 candidate with scripts/dev.sh first. This does not install or
clear the app under test. All device access uses the shared Windows adb wrapper.
"""
import argparse
import array
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import wave
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, default=Path('dist/jedflix-android-tv-1.10.4-x86_64.apk'))
parser.add_argument('--install', action='store_true', help='Install this exact candidate before integration checks, preserving app data')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
build_tools = Path(os.environ.get('BUILD_TOOLS', str(sdk / 'build-tools/36.0.0')))
android = sdk / 'platforms/android-36/android.jar'
adb = ROOT / 'scripts/adb.sh'
serial = os.environ.get('JEDFLIX_SERIAL', 'emulator-5554')
work = ROOT / '.local/audio-probe'
for directory in ['classes', 'dex', 'assets', 'native']:
    (work / directory).mkdir(parents=True, exist_ok=True)


def run(*command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


def device(*command, **kwargs):
    return run(adb, '-s', serial, *command, **kwargs)


def fixture(path, channels):
    # Quiet speech-level tone, loud passages, then abrupt short peaks. Native DSP
    # probe checks actual output amplitudes; these exercise decoder/seek lifecycle.
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(channels)
        output.setsampwidth(2)
        output.setframerate(48000)
        for second in range(24):
            amplitude = 0.025 if second % 6 < 3 else 0.85
            samples = array.array('h')
            for i in range(48000):
                for channel in range(channels):
                    value = amplitude * math.sin(2 * math.pi * (220 + channel * 110) * i / 48000)
                    samples.append(round(value * 32767))
            if sys.byteorder != 'little':
                samples.byteswap()
            output.writeframes(samples.tobytes())


abi = device('shell', 'getprop', 'ro.product.cpu.abi', capture_output=True, text=True).stdout.strip()
with zipfile.ZipFile(args.apk) as archive:
    for name in ['libavfilter.so', 'libavutil.so', 'libswresample.so']:
        (work / 'native' / name).write_bytes(archive.read(f'lib/{abi}/{name}'))
    avformat = archive.read(f'lib/{abi}/libavformat.so')
    # The pinned upstream FFmpeg disables every muxer, including the SPDIF
    # wrapper needed by mpv. Do not claim a physical/encoded route was tested.
    expect_spdif = b'--disable-muxers' not in avformat
probe = ROOT / '.local/audio-filters' / abi / 'audio-filter-probe'
if not probe.exists():
    raise SystemExit(f'Missing {probe}; build this ABI first')
remote = '/data/local/tmp/jedflix-audio-probe'
device('shell', 'mkdir', '-p', remote)
for source in [*sorted((work / 'native').glob('*.so')), probe]:
    device('push', source, remote + '/' + source.name, capture_output=True)
device('shell', 'chmod', '755', remote + '/audio-filter-probe')
native = device('shell', f'LD_LIBRARY_PATH={remote} {remote}/audio-filter-probe', capture_output=True, text=True)
(work / 'native-results.txt').write_text(native.stdout + native.stderr)
print(native.stdout, end='')

for filename, channels in [('night-stereo.wav', 2), ('night-surround.wav', 6)]:
    fixture(work / 'assets' / filename, channels)
run('ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', work / 'assets/night-stereo.wav',
    '-c:a', 'ac3', '-b:a', '192k', '-y', work / 'assets/night-passthrough.ac3')
run('javac', '--release', '8', '-classpath', android, '-d', work / 'classes',
    'scripts/android/NightAudioProbe.java')
run(build_tools / 'd8', '--lib', android, '--min-api', '26', '--output', work / 'dex',
    *sorted((work / 'classes').rglob('*.class')))
manifest = work / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.jedflix.audioprobe">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:label="Jedflix audio verification"/>
<instrumentation android:name="com.jedflix.test.NightAudioProbe" android:targetPackage="com.jedflix.stremio"/>
</manifest>''')
run(build_tools / 'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', work / 'unsigned.apk')
with zipfile.ZipFile(work / 'unsigned.apk', 'a') as archive:
    archive.write(work / 'dex/classes.dex', 'classes.dex')
    for source in sorted((work / 'assets').iterdir()):
        archive.write(source, 'assets/' + source.name, compress_type=zipfile.ZIP_DEFLATED)
run(build_tools / 'zipalign', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
run(build_tools / 'apksigner', 'sign', '--ks', '.local/jedflix-local.jks', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', work / 'probe.apk', work / 'aligned.apk')
lock = ROOT / '.local/dev-deploy.lock'
try:
    lock.mkdir()
except FileExistsError:
    raise SystemExit('Another deploy is active; retry after it finishes')
try:
    if args.install:
        device('install', '-r', args.apk)
    device('install', '-r', work / 'probe.apk')
    result = device('shell', 'am', 'instrument', '-w', '-r',
        '-e', 'expectSpdif', str(expect_spdif).lower(),
        'com.jedflix.audioprobe/com.jedflix.test.NightAudioProbe', capture_output=True, text=True)
    log = result.stdout + result.stderr
    (work / 'integration-results.txt').write_text(log)
    print(log)
    if 'result=PASS:' not in log or 'INSTRUMENTATION_CODE: -1' not in log:
        raise SystemExit('Audio integration verification failed; see .local/audio-probe/integration-results.txt')
    graph = re.search(r'INSTRUMENTATION_RESULT: graph=(.+)', log)
    if not graph:
        raise SystemExit('Probe did not report the installed application graph')
    # Measure the installed Java graph, so the native fixture never silently
    # validates a different preset from the one used in the actual application.
    native = device('shell', f'LD_LIBRARY_PATH={remote} {remote}/audio-filter-probe '
        + shlex.quote(graph[1].strip()), capture_output=True, text=True)
    (work / 'native-results.txt').write_text(native.stdout + native.stderr)
    print(native.stdout, end='')
finally:
    device('uninstall', 'com.jedflix.audioprobe', capture_output=True)
    lock.rmdir()
