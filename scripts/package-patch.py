#!/usr/bin/env python3
"""Change Android identity without renaming Java/JNI classes."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

stage = Path(sys.argv[1])
package = sys.argv[2]
assert re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", package)
old = "com.stremio.one"
A = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", A[1:-1])
path = stage / "AndroidManifest.xml"
tree = ET.parse(path)
root = tree.getroot()
root.set("package", package)
for element in root.iter():
    authority = element.get(A + "authorities")
    if authority:
        element.set(A + "authorities", authority.replace(old, package))
# The old-package handoff APK must be able to find the separate Jedflix app.
queries = root.find("queries")
if queries is None:
    queries = ET.SubElement(root, "queries")
ET.SubElement(queries, "package", {A + "name": "com.jedflix.stremio"})
ET.SubElement(root.find("application"), "activity", {
    A + "name": "com.jedflix.tv.PackageMigrationActivity", A + "exported": "false",
    A + "screenOrientation": "landscape"})
tree.write(path, encoding="utf-8", xml_declaration=True)
if package == old:
    # The handoff must not initialize a second native engine or streaming server.
    subprocess.run([sys.executable, Path(__file__).with_name("compat-manifest.py"), stage], check=True)
search = stage / "res/xml/searchable.xml"
search.write_text(search.read_text().replace(old + ".content", package + ".content"))
# UriMatcher is compiled independently of the manifest and searchable resource.
provider = stage / "smali_classes5/com/stremio/tv/providers/MediaContentProvider.smali"
source = provider.read_text()
assert source.count('"' + old + '.content"') == 2, "Upstream search authority changed"
provider.write_text(source.replace('"' + old + '.content"', '"' + package + '.content"'))
build = stage / "smali_classes5/com/stremio/tv/BuildConfig.smali"
source = build.read_text()
assert source.count('"' + old + '"') == 1, "Upstream application ID changed"
build.write_text(source.replace('"' + old + '"', '"' + package + '"'))
# Each Android app can keep its streaming service alive in the background. Never
# let Jedflix's proxy/subtitles use Stremio's loopback server when both are open.
server = stage / "assets/server.js"
source = server.read_text()
assert "port=11470" in source and ".listen(12470)" in source, "Upstream server ports changed"
server.write_text(source.replace("11470", "11480").replace("11474", "11484").replace("12470", "12480"))
api = stage / "smali_classes6/com/stremio/common/api/StreamingServerApi.smali"
source = api.read_text()
assert "127.0.0.1:11470" in source, "Upstream proxy URL changed"
api.write_text(source.replace("127.0.0.1:11470", "127.0.0.1:11480"))
print("Applied Android package and provider identity:", package)
