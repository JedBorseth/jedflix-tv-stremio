#!/usr/bin/env python3
"""Apply the local Jedflix branding to an apktool --no-src decode."""
import re
import shutil
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
manifest.write_text(
    manifest.read_text()
    .replace('android:label="Stremio Streaming Server"', 'android:label="Jedflix Streaming Server"')
    .replace('android:banner="@mipmap/ic_banner"', 'android:banner="@drawable/tv_banner"')
)

# Brand artwork comes from JedBorseth/jedflix-tv (logo.png and tv_banner.png).
# The checked-in variants are pre-sized/transparent for each Android slot.
for name in ("ic_stremio_logo", "ic_stremio_splash_logo", "ic_stremio_logo_expanded", "ic_banner_foreground"):
    (root / "res/drawable" / f"{name}.xml").unlink(missing_ok=True)
branding = Path(__file__).resolve().parent.parent / "assets" / "branding" / "res"
shutil.copytree(branding, root / "res", dirs_exist_ok=True)
