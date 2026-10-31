#!/usr/bin/env python3
"""Drive Settings → About → Software update on a disposable local TV emulator.

Uses the real updater, permission screen, and Android installer. The selected
package must be an older Jedflix, with the expected release available online or
already verified in its own updater cache. Never uninstalls or clears app data.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--package', default='com.jedflix.stremio', choices=['com.jedflix.stremio', 'com.stremio.one'])
parser.add_argument('--expect-version', required=True)
parser.add_argument('--timeout', type=int, default=180)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
adb = [str(root / 'scripts/adb.sh'), '-s', os.environ.get('JEDFLIX_SERIAL', 'emulator-5554')]


def run(*command):
    return subprocess.check_output(adb + list(command), text=True)


def version():
    match = re.search(r'versionName=([^\s]+)', run('shell', 'dumpsys', 'package', args.package))
    assert match, 'App is not installed'
    return match[1]


def snapshot():
    run('shell', 'uiautomator', 'dump', '/sdcard/jedflix-update-ui.xml')
    return list(ET.fromstring(run('exec-out', 'cat', '/sdcard/jedflix-update-ui.xml')).iter('node'))


def tap(nodes, label):
    for node in nodes:
        if label not in (node.get('text'), node.get('content-desc')):
            continue
        left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
        if right <= left or bottom <= top or top < 0 or bottom > 1080:
            continue
        run('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
        return True
    return False


before = version()
assert before != args.expect_version, 'Expected version is already installed; no update would be tested'
run('shell', 'am', 'force-stop', args.package)
run('shell', 'am', 'start', '-W', '-n', args.package + '/com.stremio.tv.MainActivity')
for _ in range(8):
    nodes = snapshot()
    tap(nodes, 'Later')
    run('shell', 'input', 'keyevent', 'KEYCODE_DPAD_LEFT')
    if tap(snapshot(), 'Settings'):
        break
else:
    raise AssertionError('Cannot open Settings')
for _ in range(5):
    if tap(snapshot(), 'About'):
        break
else:
    raise AssertionError('Cannot open About')
print('PASS: Settings → About is available', flush=True)
deadline = time.monotonic() + args.timeout
while time.monotonic() < deadline:
    if version() == args.expect_version:
        print(f'PASS: {args.package} updated from {before} to {args.expect_version} through its own updater', flush=True)
        break
    nodes = snapshot()
    if any('packageinstaller' in n.get('package', '') for n in nodes):
        failure = next((n.get('text') for n in nodes if n.get('text', '').startswith('App not installed')), None)
        assert failure is None, 'Android installer rejected the update: ' + str(failure)
        if not tap(nodes, 'Update'):
            tap(nodes, 'Install')
    elif any(n.get('package') == 'com.android.tv.settings' for n in nodes):
        switches = [n for n in nodes if n.get('class') == 'android.widget.Switch']
        assert switches, 'Unexpected Android settings screen'
        # Google TV ignores the package URI and lists every eligible app. This
        # disposable fixture leaves only the app under test without permission.
        disabled = [n for n in switches if n.get('checked') != 'true']
        assert len(disabled) <= 1, 'Ambiguous install-permission fixture'
        if disabled:
            switch = disabled[0]
            left, top, right, bottom = map(int, re.findall(r'\d+', switch.get('bounds')))
            run('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
            time.sleep(0.5)
        run('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    else:
        # Changing install permission can kill the app; its Settings screen then
        # returns to General while the updater restores the verified download.
        labels = {n.get('text') for n in nodes}
        if 'About' in labels and 'Software update' not in labels and 'Update now' not in labels:
            tap(nodes, 'About')
            continue
        for label in ('Install ' + args.expect_version, 'Download ' + args.expect_version, 'Update now', 'Check for updates'):
            if tap(nodes, label):
                break
    time.sleep(0.5)
else:
    raise AssertionError('The expected update was not installed')
