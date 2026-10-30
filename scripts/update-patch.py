#!/usr/bin/env python3
"""Replace stock About with Jedflix settings; declare the private APK provider."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

stage = Path(sys.argv[1])
about = stage / 'smali_classes6/com/stremio/tv/views/settings/sections/AboutSectionKt.smali'
source = about.read_text()
start = source.index('.method public static final AboutSection(')
end = source.index('.end method', start) + len('.end method')
source = source[:start] + '''.method public static final AboutSection(Lcom/stremio/core/models/StreamingServer;Landroidx/compose/runtime/Composer;I)V
    .locals 0
    invoke-static {p1}, Lcom/jedflix/tv/AboutSettings;->Content(Landroidx/compose/runtime/Composer;)V
    return-void
.end method''' + source[end:]
about.write_text(source)
application = stage / 'smali_classes5/com/stremio/tv/StremioApplication.smali'
source = application.read_text()
needle = '    invoke-static {p0}, Lcom/jedflix/tv/CometConfig;->init(Landroid/content/Context;)V'
assert source.count(needle) == 1
application.write_text(source.replace(needle, needle + '\n    invoke-static {p0}, Lcom/jedflix/tv/JedflixUpdates;->init(Landroid/app/Application;)V'))

A = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])
path = stage / 'AndroidManifest.xml'
tree = ET.parse(path)
root = tree.getroot()
ET.SubElement(root, 'uses-permission', {A+'name': 'android.permission.REQUEST_INSTALL_PACKAGES'})
app = root.find('application')
ET.SubElement(app, 'activity', {A+'name': 'com.jedflix.tv.UpdateActivity', A+'exported': 'false',
    A+'theme': '@android:style/Theme.Material.NoActionBar', A+'label': 'Jedflix updates'})
provider = ET.SubElement(app, 'provider', {A+'name': 'androidx.core.content.FileProvider',
    A+'authorities': root.get('package')+'.updates', A+'exported': 'false', A+'grantUriPermissions': 'true'})
ET.SubElement(provider, 'meta-data', {A+'name': 'android.support.FILE_PROVIDER_PATHS', A+'resource': '@xml/jedflix_update_paths'})
tree.write(path, encoding='utf-8', xml_declaration=True)
(stage / 'res/xml/jedflix_update_paths.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<paths><cache-path name="updates" path="updates/"/></paths>''')
print('Added Jedflix About, startup checks, and private update installer')
