#!/usr/bin/env python3
"""Apply the local Jedflix branding to an apktool --no-src decode."""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in (root / "res").glob("values*/strings.xml"):
    text = path.read_text()
    text = re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)', r'\1Jedflix\2', text)
    for name in ("label_about_stremio", "label_restart_required_message", "label_external_opening_app", "label_external_opening_youtube"):
        text = re.sub(r'(<string name="' + name + r'"[^>]*>)(.*?)(</string>)',
                      lambda m: m[1] + m[2].replace("Stremio", "Jedflix") + m[3], text)
    path.write_text(text)

manifest = root / "AndroidManifest.xml"
manifest.write_text(manifest.read_text().replace('android:label="Stremio Streaming Server"', 'android:label="Jedflix Streaming Server"'))

# Code-native vector artwork. Keep resource names/IDs because compiled code uses them.
def vector(width, height, body):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
 android:width="{width}dp" android:height="{height}dp"
 android:viewportWidth="{width}" android:viewportHeight="{height}">{body}</vector>'''

def path(data, color="#FFFFFF", stroke=False):
    if stroke:
        return f'<path android:pathData="{data}" android:fillColor="#00000000" android:strokeColor="{color}" android:strokeWidth="6" android:strokeLineCap="square" android:strokeLineJoin="round"/>'
    return f'<path android:pathData="{data}" android:fillColor="{color}"/>'

mark = path("M18,16 H90 V92 H18 Z", "#E53945") + path("M60,32 V62 Q60,76 46,76 Q32,76 32,62", stroke=True)
icon = vector(108, 108, mark)
word = "M32,43 V67 Q32,81 18,81 Q6,81 6,69 M72,43 H48 V81 H72 M48,62 H68 M88,81 V43 H100 Q120,43 120,62 Q120,81 100,81 Z M138,81 V43 H162 M138,62 H158 M180,43 V81 H204 M222,43 V81 M242,43 L268,81 M268,43 L242,81"
wordmark = vector(320, 120, '<group android:translateX="22">' + path(word, stroke=True) + '</group>')
for name in ("ic_stremio_logo.xml", "ic_stremio_splash_logo.xml"):
    (root / "res/drawable" / name).write_text(icon)
for name in ("ic_stremio_logo_expanded.xml", "ic_banner_foreground.xml"):
    (root / "res/drawable" / name).write_text(wordmark)
