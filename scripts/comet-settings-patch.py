#!/usr/bin/env python3
"""Insert an owner-scoped Comet settings section in the pinned TV APK."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

stage = Path(sys.argv[1])
smali = stage / 'smali_classes6'

def edit(path, before, after):
    source = path.read_text()
    assert source.count(before) == 1, f'Upstream Comet settings seam changed: {path}:{before[:90]}'
    path.write_text(source.replace(before, after))

application = stage / 'smali_classes5/com/stremio/tv/StremioApplication.smali'
needle = '    invoke-super {p0}, Landroid/app/Application;->onCreate()V'
edit(application, needle, needle + '''

    invoke-static {p0}, Lcom/jedflix/tv/CometRestartActivity;->isRestartProcess(Landroid/content/Context;)Z
    move-result v0
    if-eqz v0, :jedflix_main_process
    return-void
    :jedflix_main_process
    invoke-static {p0}, Lcom/jedflix/tv/CometConfig;->init(Landroid/content/Context;)V''')

settings = smali / 'com/stremio/tv/views/settings/SettingsPageKt.smali'
needle = '    .line 111\n    new-instance v5, Lkotlin/Pair;'
edit(settings, needle, '''    new-instance v5, Lkotlin/Pair;
    const-string v10, "realdebrid"
    const-string v11, "Real-Debrid"
    invoke-direct {v5, v10, v11}, Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V
    invoke-interface {v6, v5}, Ljava/util/List;->add(Ljava/lang/Object;)Z

''' + needle)
needle = '    :cond_3\n    invoke-virtual {v0}, Ljava/lang/String;->hashCode()I'
edit(settings, needle, '''    :cond_3
    const-string v1, "realdebrid"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :jedflix_standard_section
    const v1, 0x4a524443
    invoke-interface {v6, v1}, Landroidx/compose/runtime/Composer;->startReplaceGroup(I)V
    invoke-static {v6}, Lcom/jedflix/tv/CometSettingsSection;->Content(Landroidx/compose/runtime/Composer;)V
    invoke-interface {v6}, Landroidx/compose/runtime/Composer;->endReplaceGroup()V
    goto/16 :goto_4
    :jedflix_standard_section
    invoke-virtual {v0}, Ljava/lang/String;->hashCode()I''')

helper = smali / 'com/jedflix/tv/CometSettingsSection.smali'
helper.write_text('''.class public final Lcom/jedflix/tv/CometSettingsSection;
.super Ljava/lang/Object;

.method public static Content(Landroidx/compose/runtime/Composer;)V
    .locals 0
    invoke-static {p0}, Lcom/jedflix/tv/CometSettings;->Content(Landroidx/compose/runtime/Composer;)V
    return-void
.end method
''')

# General's first lazy-list item is the Stremio Account section. Keep the
# remaining UI and General settings items, including their original conditions.
general = smali / 'com/stremio/tv/views/settings/sections/GeneralSectionKt.smali'
source = general.read_text()
method = source.index('.method private static final GeneralSection$lambda$7$0(')
start = source.index('    .line 126\n', method)
end = source.index('    if-eqz p0, :cond_0', start)
account_item = source[start:end]
assert account_item.count('LazyListScope;->item$default') == 1
assert 'GeneralSectionKt$$ExternalSyntheticLambda9;' in account_item
edit(general, account_item, '    const/4 v10, 0x1\n\n')

# Reuse the stock Compose text-input row in the account modal, with password
# masking and password keyboard options. Clone it so other settings stay intact.
rows = smali / 'com/stremio/tv/views/settings/composables/rows'
for original in rows.glob('TextInputRowKt*.smali'):
    target = rows / original.name.replace('TextInputRowKt', 'JedflixKeyInputRowKt')
    target.write_text(original.read_text().replace('TextInputRowKt', 'JedflixKeyInputRowKt'))
key_row = rows / 'JedflixKeyInputRowKt.smali'
edit(key_row, '    const v19, 0xbe18', '    const v19, 0xb618')
edit(key_row, '    const/4 v11, 0x0\n\n    const/4 v12, 0x0\n\n    const/4 v13, 0x0\n\n    const/4 v15, 0x0',
    '    new-instance v11, Landroidx/compose/ui/text/input/PasswordVisualTransformation;\n'
    '    invoke-direct {v11}, Landroidx/compose/ui/text/input/PasswordVisualTransformation;-><init>()V\n\n'
    '    const/4 v12, 0x0\n\n    const/4 v13, 0x0\n\n    const/4 v15, 0x0')
edit(key_row, '    const/16 v16, 0x77', '    const/16 v16, 0x73')
edit(key_row, '    const/4 v11, 0x0\n\n    const/4 v13, 0x0\n\n    const/4 v14, 0x0',
    '    const/4 v11, 0x7\n\n    const/4 v13, 0x0\n\n    const/4 v14, 0x0')

# Stock counters reserve only 60 dp for short numbers. Real-Debrid also displays
# "Unlimited" and GB units, so give its cloned counters a wider single-line value.
components = stage / 'smali_classes5/com/stremio/tv/composables'
for original in components.glob('CounterKt*.smali'):
    target = components / original.name.replace('CounterKt', 'JedflixCounterKt')
    target.write_text(original.read_text().replace('CounterKt', 'JedflixCounterKt'))
counter = components / 'JedflixCounterKt.smali'
edit(counter, '    const/16 v7, 0x3c', '    const/16 v7, 0x6e')
edit(counter, '    const v35, 0x1fbbc', '    const v35, 0x19bbc')
edit(counter, '    const/16 v28, 0x0', '    const/16 v28, 0x1')
for original in rows.glob('CounterRowKt*.smali'):
    target = rows / original.name.replace('CounterRowKt', 'JedflixCounterRowKt')
    target.write_text(original.read_text().replace('CounterRowKt', 'JedflixCounterRowKt')
        .replace('/CounterKt;', '/JedflixCounterKt;'))

# Encrypt the native profile as well: Comet's standard URL contains the key.
storage = smali / 'com/stremio/common/storage/CoreStorage.smali'
needle = '''    invoke-static {p1, p2}, Lcom/jedflix/tv/ManagedAddonConfig;->normalize(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p2'''
edit(storage, needle, needle + '''
    invoke-static {p1, p2}, Lcom/jedflix/tv/CometConfig;->protectProfile(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p2''')

# Keep the existing state collector and detect the completed empty result, not
# a transient empty list while source requests are still loading.
collector = smali / 'com/stremio/tv/views/metadetails/streams/StreamsListFragment$onCreate$3.smali'
needle = '    invoke-static {p1}, Lkotlin/ResultKt;->throwOnFailure(Ljava/lang/Object;)V'
edit(collector, needle, needle + '''

    iget-object v1, p0, Lcom/stremio/tv/views/metadetails/streams/StreamsListFragment$onCreate$3;->this$0:Lcom/stremio/tv/views/metadetails/streams/StreamsListFragment;
    invoke-static {v1, v0}, Lcom/jedflix/tv/CometSettings;->updateEmptyState(Ljava/lang/Object;Ljava/lang/Object;)V''')
fragment = smali / 'com/stremio/tv/views/metadetails/streams/StreamsListFragment.smali'
needle = '''    .line 89
    iget-boolean v1, p0, Lcom/stremio/tv/views/metadetails/streams/StreamsListFragment;->isAutoPlayingStream:Z'''
edit(fragment, needle, '''    invoke-static {p0, v0}, Lcom/jedflix/tv/CometSettings;->updateEmptyState(Ljava/lang/Object;Ljava/lang/Object;)V

''' + needle)

ANDROID = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', ANDROID[1:-1])
ET.register_namespace('app', 'http://schemas.android.com/apk/res-auto')
manifest_path = stage / 'AndroidManifest.xml'
manifest = ET.parse(manifest_path)
ET.SubElement(manifest.getroot().find('application'), 'activity', {
    ANDROID + 'name': 'com.jedflix.tv.CometRestartActivity',
    ANDROID + 'exported': 'false', ANDROID + 'process': ':restart',
    ANDROID + 'excludeFromRecents': 'true',
    ANDROID + 'theme': '@android:style/Theme.Material.NoActionBar',
})
manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)
layout = stage / 'res/layout/fragment_meta_details.xml'
tree = ET.parse(layout)
row = next(node for node in tree.iter('LinearLayout') if node.get(ANDROID + 'gravity') == 'bottom')
button = ET.Element('Button', {ANDROID + 'tag': 'jedflix_real_debrid',
    ANDROID + 'text': 'Real-Debrid settings', ANDROID + 'visibility': 'gone',
    ANDROID + 'layout_width': 'wrap_content', ANDROID + 'layout_height': 'wrap_content',
    ANDROID + 'layout_marginEnd': '10dp'})
row.insert(0, button)
tree.write(layout, encoding='utf-8', xml_declaration=True)
print('Added Real-Debrid settings, encrypted configuration, and the no-streams shortcut')
