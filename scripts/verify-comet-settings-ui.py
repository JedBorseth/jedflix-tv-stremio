#!/usr/bin/env python3
"""Compare Real-Debrid row geometry and D-pad behavior to the stock settings UI."""
from pathlib import Path
import os
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
adb = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"

def run(*args):
    return subprocess.run([str(adb), *map(str, args)], check=True, capture_output=True, text=True).stdout

def snapshot():
    run("shell", "uiautomator", "dump", "/sdcard/jedflix-comet-layout.xml")
    return ET.fromstring(run("shell", "cat", "/sdcard/jedflix-comet-layout.xml"))

def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds"))))

def find(tree, text):
    return next((n for n in tree.iter("node") if n.get("text") == text), None)

def tap(text):
    node = find(snapshot(), text)
    if node is None: return False
    x1, y1, x2, y2 = bounds(node)
    run("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)
    return True

run("shell", "am", "force-stop", "com.stremio.one")
run("shell", "am", "start", "-W", "-n", "com.stremio.one/com.stremio.tv.MainActivity")
for _ in range(5):
    run("shell", "input", "keyevent", 21)
    if tap("Settings"): break
else: raise AssertionError("Cannot open Settings")
assert tap("General")
general = snapshot()
reference = bounds(find(general, "Language"))
with (ROOT / ".local/comet-reference-general.png").open("wb") as image:
    subprocess.run([str(adb), "exec-out", "screencap", "-p"], check=True, stdout=image)
assert tap("Real-Debrid")
page = snapshot()
key = find(page, "Real-Debrid account")
assert key is not None, "Missing Real-Debrid account row"
actual = bounds(key)
print(f"General row label: {reference}; Real-Debrid row label: {actual}")
assert abs(actual[0] - reference[0]) <= 8, "Real-Debrid rows do not align with the other settings sections"
assert abs((actual[3]-actual[1]) - (reference[3]-reference[1])) <= 8, "Real-Debrid row text differs from the existing settings typography"
assert not any(n.get("class") in ("android.widget.ScrollView", "android.widget.EditText", "android.widget.Spinner") for n in page.iter("node")), "Real-Debrid still embeds a separate form/keyboard focus tree"
print("PASS: Real-Debrid matches the existing settings layout")

def focused_row(tree):
    focused = next((n for n in tree.iter("node") if n.get("focused") == "true"), None)
    if focused is None: return [], None
    x1, y1, x2, y2 = bounds(focused)
    center = (y1+y2)/2
    texts = []
    for node in tree.iter("node"):
        if not node.get("text") or node.get("password") == "true": continue
        left, top, right, bottom = bounds(node)
        if left >= reference[0]-8 and abs((top+bottom)/2-center) <= 8:
            texts.append(node.get("text"))
    return texts, bounds(focused)

# Enter the section from its tab, then move through the same row focus tree as
# every other settings section. No mouse/touch selection inside the form.
for _ in range(3):
    run("shell", "input", "keyevent", 20)
    if "Set up with your phone" in focused_row(snapshot())[0]: break
else: raise AssertionError("D-pad cannot enter the phone setup row")
run("shell", "input", "keyevent", 23)
assert find(snapshot(), "Connecting to Jedflix…") is not None or any(
    "Scan with your phone" in n.get("text", "") for n in snapshot().iter("node")), "QR dialog did not open"
run("shell", "input", "keyevent", 4)
assert "Set up with your phone" in focused_row(snapshot())[0], "Closing QR dialog loses row focus"
for _ in range(3):
    run("shell", "input", "keyevent", 20)
    if "Real-Debrid account" in focused_row(snapshot())[0]: break
else: raise AssertionError("D-pad cannot enter the first settings row")
run("shell", "input", "keyevent", 23)
editor = snapshot()
assert any(n.get("password") == "true" for n in editor.iter("node")), "Key editor must be masked"
run("shell", "input", "keyevent", 4)
if any(n.get("class") == "android.widget.EditText" for n in snapshot().iter("node")):
    run("shell", "input", "keyevent", 4) # dismiss keyboard first when present
assert "Real-Debrid account" in focused_row(snapshot())[0], "Closing the editor loses row focus"

expected = ["Results per resolution", "Maximum file size", "Maximum resolution",
    "Show cached streams only", "Filter low-quality releases", "Hide duplicate streams",
    "Apply configuration", "Remove account"]
for title in expected:
    run("shell", "input", "keyevent", 20)
    tree = snapshot()
    texts, before = focused_row(tree)
    assert title in texts, f"D-pad skipped {title}: focused row labels={texts}"
    if title == "Maximum resolution":
        run("shell", "input", "keyevent", 23)
        assert tap("720p"), "Resolution menu does not expose its choices"
        texts, after = focused_row(snapshot())
        assert title in texts and "720p" in texts, "Resolution selection loses focus or fails to update the row"
    if title == "Show cached streams only":
        run("shell", "input", "keyevent", 23)
        texts, after = focused_row(snapshot())
        assert title in texts and all(abs(a-b) <= 2 for a,b in zip(before, after)), "Toggling a switch moves focus or jumps the layout"
        run("shell", "input", "keyevent", 23) # restore the unsaved value
print("PASS: D-pad visits all rows in order; dialogs restore focus and switches do not move the layout")
