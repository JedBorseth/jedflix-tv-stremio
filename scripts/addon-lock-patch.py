#!/usr/bin/env python3
"""Remove user addon management and reject mutations before native dispatch."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

stage = Path(sys.argv[1])
smali = stage / "smali_classes6"

def replace_method(path, name, body):
    source = path.read_text()
    pattern = rf"(\.method (?:public|private) (?:final )?{re.escape(name)}\([^\n]*\n).*?\.end method"
    source, count = re.subn(pattern, lambda m: m[1] + body + "\n.end method", source, flags=re.S)
    assert count == 1, f"Upstream method changed: {path.name}:{name} ({count})"
    path.write_text(source)

# Promotions are independent of addon navigation and profile permissions. Stop
# fetching the upstream feed, advancing its queue, and rendering its dialogs.
# Disable the install callback too, so an already-created callback is harmless.
announcements = smali / "com/stremio/common/viewmodels/AnnouncementsViewModel.smali"
for name in ("loadAnnouncements", "loadNext", "installAddon"):
    replace_method(announcements, name, """    .locals 0
    return-void""")
replace_method(smali / "com/stremio/tv/views/board/BoardFragment.smali",
               "showAnnouncementDialog", """    .locals 0
    return-void""")

# Playback/discovery empty states must not send users to addon management.
# Apply the fork's wording to every bundled locale, as with its app branding.
managed_messages = {
    "label_install_more_addons": "No more streams are available for this title.",
    "label_err_addon_not_installed": "This content source is unavailable.",
    "label_no_addons": "Content sources are unavailable.",
    "label_no_addons_for_type": "No content sources are available for this type.",
    "label_discover_addon_uninstalled": "This content is no longer available.",
    "label_user_profiles_addons_access_denied": "Content sources are managed by Jedflix.",
}
for path in (stage / "res").glob("values*/strings.xml"):
    source = path.read_text()
    for name, message in managed_messages.items():
        source = re.sub(r'(<string name="' + name + r'"[^>]*>).*?(</string>)',
                        lambda m: m[1] + message + m[2], source, flags=re.S)
    path.write_text(source)

for directory in ("smali_classes5", "smali_classes9"):
    for path in (stage / directory / "com/stremio/translations").glob("*StringsClass.smali"):
        for name, message in managed_messages.items():
            replace_method(path, "get" + name[0].upper() + name[1:],
                           '    .locals 1\n    const-string v0, "' + message + '"\n    return-object v0')

api = smali / "com/stremio/common/api/StremioApi.smali"
for name in ("installAddon-gIAlu-s", "uninstallAddon-gIAlu-s", "upgradeAddon-gIAlu-s",
             "syncAddons-IoAF18A", "traktInstallAddon-IoAF18A"):
    replace_method(api, name, """    .locals 1
    invoke-static {}, Lcom/jedflix/tv/ManagedAddons;->rejected()Ljava/lang/Object;
    move-result-object v0
    return-object v0""")
for name in ("installAddonForProfile", "uninstallAddonForProfile"):
    replace_method(api, name, """    .locals 0
    invoke-static {}, Lcom/jedflix/tv/ManagedAddons;->logBlocked()V
    return-void""")

# UI permission checks cover profile editors and addon detail controls too.
for relative in ("com/stremio/core/models/UserProfile.smali",
                 "com/stremio/common/viewmodels/state/ManageProfilesState.smali"):
    replace_method(smali / relative, "getCanManageAddons", """    .locals 1
    const/4 v0, 0x0
    return v0""")

# Remove the tab even during loading, when there may be no selected profile.
menu = smali / "com/stremio/tv/views/menu/NavigationMenuKt.smali"
source = menu.read_text()
pattern = r"    if-nez v10, :cond_14\n.*?    :cond_14"
source, count = re.subn(pattern, "    :cond_14", source, flags=re.S)
assert count == 1 and "getLabel_stremio_tv_nav_addons()" not in source
menu.write_text(source)

# Defense at the last managed boundary before JNI, including all-profile and
# per-profile mutations and remote addon imports/exports. Media loading remains.
core = smali / "com/stremio/core/Core.smali"
source = core.read_text()
needle = ".method public final dispatch(Lcom/stremio/core/runtime/msg/Action;Lcom/stremio/core/Field;)V\n    .locals 7\n"
assert source.count(needle) == 1
source = source.replace(needle, needle + """
    invoke-static {p1}, Lcom/jedflix/tv/ManagedAddons;->blocked(Lcom/stremio/core/runtime/msg/Action;)Z
    move-result v0
    if-eqz v0, :jedflix_dispatch_allowed
    invoke-static {}, Lcom/jedflix/tv/ManagedAddons;->logBlocked()V
    return-void
    :jedflix_dispatch_allowed
""")
core.write_text(source)

# Native initialization reads this JSON storage boundary. Applying the owner
# preset on reads and writes handles existing installs and fresh guest profiles.
storage = smali / "com/stremio/common/storage/CoreStorage.smali"
source = storage.read_text()
needle = "    move-result-object p1\n\n    invoke-direct {v0, p1}, Lcom/stremio/core/Storage$Result$Ok;-><init>(Ljava/lang/Object;)V"
assert source.count(needle) == 1
source = source.replace(needle, """    move-result-object v2
    invoke-static {p1, v2}, Lcom/jedflix/tv/ManagedAddonConfig;->normalize(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p1

    invoke-direct {v0, p1}, Lcom/stremio/core/Storage$Result$Ok;-><init>(Ljava/lang/Object;)V""")
needle = ".method public set(Ljava/lang/String;Ljava/lang/String;)Lcom/stremio/core/Storage$Result;\n    .locals 1\n"
assert source.count(needle) == 1
source = source.replace(needle, needle + """
    invoke-static {p1, p2}, Lcom/jedflix/tv/ManagedAddonConfig;->normalize(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p2
""")
storage.write_text(source)

helper = smali / "com/jedflix/tv/ManagedAddons.smali"
helper.parent.mkdir(parents=True, exist_ok=True)
helper.write_text(""".class public final Lcom/jedflix/tv/ManagedAddons;
.super Ljava/lang/Object;

.method public static logBlocked()V
    .locals 2
    const-string v0, "JedflixAddons"
    const-string v1, "User addon mutation blocked by managed policy"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method

.method public static rejected()Ljava/lang/Object;
    .locals 2
    invoke-static {}, Lcom/jedflix/tv/ManagedAddons;->logBlocked()V
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "Content sources are managed by Jedflix"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V
    invoke-static {v0}, Lkotlin/ResultKt;->createFailure(Ljava/lang/Throwable;)Ljava/lang/Object;
    move-result-object v0
    return-object v0
.end method

.method private static isAddonArgs(Ljava/lang/Object;)Z
    .locals 2
    if-eqz p0, :allowed
    invoke-virtual {p0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;
    move-result-object v0
    const-string v1, "Addon"
    invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    return v0
    :allowed
    const/4 v0, 0x0
    return v0
.end method

.method public static blocked(Lcom/stremio/core/runtime/msg/Action;)Z
    .locals 2
    if-eqz p0, :allowed
    invoke-virtual {p0}, Lcom/stremio/core/runtime/msg/Action;->getCtx()Lcom/stremio/core/runtime/msg/ActionCtx;
    move-result-object v0
    if-eqz v0, :profiles
    invoke-virtual {v0}, Lcom/stremio/core/runtime/msg/ActionCtx;->getArgs()Lcom/stremio/core/runtime/msg/ActionCtx$Args;
    move-result-object v0
    invoke-static {v0}, Lcom/jedflix/tv/ManagedAddons;->isAddonArgs(Ljava/lang/Object;)Z
    move-result v1
    if-nez v1, :blocked
    :profiles
    invoke-virtual {p0}, Lcom/stremio/core/runtime/msg/Action;->getProfilesManager()Lcom/stremio/core/runtime/msg/ActionProfilesManager;
    move-result-object v0
    if-eqz v0, :allowed
    invoke-virtual {v0}, Lcom/stremio/core/runtime/msg/ActionProfilesManager;->getArgs()Lcom/stremio/core/runtime/msg/ActionProfilesManager$Args;
    move-result-object v0
    invoke-static {v0}, Lcom/jedflix/tv/ManagedAddons;->isAddonArgs(Ljava/lang/Object;)Z
    move-result v1
    if-nez v1, :blocked
    :allowed
    const/4 v0, 0x0
    return v0
    :blocked
    const/4 v0, 0x1
    return v0
.end method
""")

ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"
ET.register_namespace("android", ANDROID[1:-1])
ET.register_namespace("app", APP[1:-1])
manifest_path = stage / "AndroidManifest.xml"
manifest = ET.parse(manifest_path)
removed = 0
for activity in manifest.getroot().find("application").findall("activity"):
    for intent in list(activity.findall("intent-filter")):
        if any("addons/" in str(node.attrib) or "manifest.json" in str(node.attrib)
               for node in intent.findall("data")):
            activity.remove(intent)
            removed += 1
assert removed == 3, f"Upstream addon intent filters changed: {removed}"
manifest.write(manifest_path, encoding="utf-8", xml_declaration=True)

nav_path = stage / "res/navigation/main_navigation.xml"
navigation = ET.parse(nav_path)
redirected = 0
for fragment in navigation.getroot().findall("fragment"):
    if fragment.get(ANDROID + "id") in ("@id/addons", "@id/addon_details"):
        fragment.set(ANDROID + "name", "com.stremio.tv.views.board.BoardFragment")
        fragment.set(ANDROID + "label", "@string/label_stremio_tv_nav_home")
        for child in list(fragment):
            fragment.remove(child)
        redirected += 1
assert redirected == 2
navigation.write(nav_path, encoding="utf-8", xml_declaration=True)
print("Removed addon promotions/UI/routes and blocked facade + native-dispatch addon mutations")
