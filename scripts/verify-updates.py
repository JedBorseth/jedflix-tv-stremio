#!/usr/bin/env python3
"""On-device updater checks. Requires an installed older Jedflix and a newer x86_64 candidate.

--phase install opens the real installer flow; confirm it with the TV remote.
The self-update terminates instrumentation. Run --phase after to check persistence.
Only the probe APK is installed here; the app candidate goes through its own updater.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate', type=Path, default=Path('dist/jedflix-1.0.1-x86_64.apk'))
parser.add_argument('--phase', choices=['checks', 'prepare', 'install', 'after'], default='checks')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
tools = Path(os.environ.get('BUILD_TOOLS', str(sdk / 'build-tools/36.0.0')))
android = sdk / 'platforms/android-36/android.jar'
work = ROOT / '.local/update-probe'
for folder in ['api', 'classes', 'dex', 'stubs/com/jedflix/tv']:
    (work / folder).mkdir(parents=True, exist_ok=True)


def run(*command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


run('python3', 'scripts/prepare-update.py', args.candidate, '--output', work / 'release')
manifest = work / 'release/jedflix-update.json'
config = json.loads((ROOT / 'assets/update-config.json').read_text())
stubs = work / 'stubs/com/jedflix/tv'
(stubs / 'JedflixBuild.java').write_text('package com.jedflix.tv; public class JedflixBuild {'
    f'public static final String VERSION_NAME="{config["versionName"]}", REPOSITORY="{config["repository"]}"; }}')
(stubs / 'JedflixUpdates.java').write_text('package com.jedflix.tv; public class JedflixUpdates {'
    'public boolean busy; public UpdateClient.Release release; public java.io.File apk; public String status;'
    'public static JedflixUpdates get(android.content.Context c) { return null; } public void cancel() {} public void updateNow() {} public void rememberReady() {} public boolean restoreReady() { return false; } }')
run('javac', '--release', '8', '-classpath', android, '-d', work / 'api',
    *stubs.glob('*.java'), 'scripts/android/UpdateClient.java', 'scripts/android/stubs/androidx/core/content/FileProvider.java')
run('javac', '--release', '8', '-classpath', os.pathsep.join(map(str, [android, work / 'api'])),
    '-d', work / 'classes', 'scripts/android/UpdateProbe.java')
run(tools / 'd8', '--lib', android, '--classpath', work / 'api', '--min-api', '26',
    '--output', work / 'dex', *(work / 'classes').rglob('*.class'))
probe_manifest = work / 'AndroidManifest.xml'
probe_manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.jedflix.updateprobe">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:label="Jedflix update verification"/>
<instrumentation android:name="com.jedflix.tv.UpdateProbe" android:targetPackage="com.stremio.one"/>
</manifest>''')
unsigned = work / 'unsigned.apk'
run(tools / 'aapt2', 'link', '-I', android, '--manifest', probe_manifest, '-o', unsigned)
with zipfile.ZipFile(unsigned, 'a') as archive:
    archive.write(work / 'dex/classes.dex', 'classes.dex')
run(tools / 'zipalign', '-f', '4', unsigned, work / 'aligned.apk')
run(tools / 'apksigner', 'sign', '--ks', '.local/jedflix-local.jks', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', work / 'probe.apk', work / 'aligned.apk')
wrong_key = work / 'wrong-key.jks'
if not wrong_key.exists():
    run('keytool', '-genkeypair', '-keystore', wrong_key, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'wrong-key', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=Updater Negative Test')
wrong_signer = work / 'wrong-signer.apk'
run(tools / 'apksigner', 'sign', '--ks', wrong_key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', wrong_signer, args.candidate)
adb = ROOT / 'scripts/adb.sh'
serial = os.environ.get('JEDFLIX_SERIAL', 'emulator-5554')
# Match the deployment lock used by scripts/dev.sh; never overlap installs.
lock = ROOT / '.local/dev-deploy.lock'
try:
    lock.mkdir()
except FileExistsError:
    raise SystemExit('Another deploy is active; retry after it finishes')
try:
    run(adb, '-s', serial, 'install', '-r', work / 'probe.apk')
finally:
    lock.rmdir()
for local, remote in [(args.candidate, 'candidate.apk'), (manifest, 'manifest.json'), (wrong_signer, 'wrong-signer.apk')]:
    run(adb, '-s', serial, 'push', local, '/data/local/tmp/jedflix-update-' + remote)
result = run(adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'phase', args.phase,
    '-e', 'candidate', '/data/local/tmp/jedflix-update-candidate.apk',
    '-e', 'manifest', '/data/local/tmp/jedflix-update-manifest.json',
    '-e', 'wrongSigner', '/data/local/tmp/jedflix-update-wrong-signer.apk',
    'com.jedflix.updateprobe/com.jedflix.tv.UpdateProbe', capture_output=True, text=True)
log = result.stdout + result.stderr
(work / f'{args.phase}.log').write_text(log)
print(log)
if args.phase != 'install' and 'PASS:' not in log:
    raise SystemExit('Updater verification failed')
