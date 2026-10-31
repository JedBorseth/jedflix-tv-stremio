#!/usr/bin/env python3
"""Expose only the migration screen in the original-package handoff APK."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", A[1:-1])
path = Path(sys.argv[1]) / "AndroidManifest.xml"
tree = ET.parse(path)
assert tree.getroot().get("package") == "com.stremio.one"
app = tree.getroot().find("application")
app.set(A + "name", "android.app.Application")
app.set(A + "label", "Jedflix migration")
for child in list(app):
    if child.tag in {"activity", "activity-alias", "service", "receiver"} or (
        child.tag == "provider" and child.get(A + "name") != "androidx.core.content.FileProvider"
    ):
        app.remove(child)
activity = ET.SubElement(app, "activity", {
    A + "name": "com.jedflix.tv.PackageMigrationActivity", A + "exported": "true",
    A + "screenOrientation": "landscape", A + "launchMode": "singleTask"})
intent = ET.SubElement(activity, "intent-filter")
ET.SubElement(intent, "action", {A + "name": "android.intent.action.MAIN"})
for category in ["android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"]:
    ET.SubElement(intent, "category", {A + "name": category})
tree.write(path, encoding="utf-8", xml_declaration=True)
