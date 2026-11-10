#!/usr/bin/env python3
"""Replace the Discover route and nav tab with the Jedflix Live TV guide."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: live-tv-guide-patch.py <decoded-stage>")

stage = Path(sys.argv[1])
smali6 = stage / "smali_classes6"
discover = smali6 / "com/stremio/tv/views/discover/DiscoverFragment.smali"
menu = smali6 / "com/stremio/tv/views/menu/NavigationMenuKt.smali"


def require(path: Path, needle: str, expected: int = 1) -> None:
    source = path.read_text()
    found = source.count(needle)
    assert found == expected, (
        f"Upstream Live TV guide seam changed: {path}: {needle[:120]!r} "
        f"(expected {expected}, found {found})"
    )


def replace_method(path: Path, name: str, body: str) -> None:
    source = path.read_text()
    pattern = rf"(\.method (?:public|private) (?:final )?{re.escape(name)}\([^\n]*\n).*?\.end method"
    updated, count = re.subn(
        pattern, lambda m: m[1] + body + "\n.end method", source, flags=re.S
    )
    assert count == 1, f"Upstream method changed: {path.name}:{name} ({count})"
    path.write_text(updated)


def edit(path: Path, before: str, after: str) -> None:
    require(path, before, 1)
    path.write_text(path.read_text().replace(before, after, 1))


UPSTREAM_MARKERS = [
    (discover, ".super Landroidx/fragment/app/Fragment;"),
    (discover, ".method public onCreate(Landroid/os/Bundle;)V"),
    (
        discover,
        "invoke-virtual {p1, v0, v1}, Landroidx/activity/OnBackPressedDispatcher;->addCallback(Landroidx/lifecycle/LifecycleOwner;Landroidx/activity/OnBackPressedCallback;)V",
    ),
    (discover, ".method public onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V"),
    (discover, "sget p2, Lcom/stremio/tv/R$id;->discover_picker:I"),
    (
        discover,
        ".method public onResume()V\n    .locals 3\n\n    .line 107\n    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onResume()V\n\n    .line 108\n    invoke-direct {p0}, Lcom/stremio/tv/views/discover/DiscoverFragment;->updateFocus()V",
    ),
    (menu, "sget v35, Lcom/stremio/tv/R$id;->discover:I"),
    (menu, "sget v37, Lcom/stremio/tv/R$drawable;->discover:I"),
    (menu, "sget v38, Lcom/stremio/tv/R$drawable;->discover_outline:I"),
]

for marker_path, marker in UPSTREAM_MARKERS:
    require(marker_path, marker, 1)

require(discover, ".method public onCreateView(", 0)
require(discover, ".method public onPause()V", 0)
require(discover, ".method public onDestroyView()V", 0)

# Bypass DiscoverFragment.onCreate so Field.DISCOVER, DiscoverViewModel.loadCatalog,
# and DiscoverFragment$backPressedCallback$1 are never registered or triggered.
replace_method(
    discover,
    "onCreate",
    """    .locals 0

    invoke-super {p0, p1}, Landroidx/fragment/app/Fragment;->onCreate(Landroid/os/Bundle;)V

    return-void""",
)

# Bypass DiscoverFragment.onViewCreated so picker/rows views and DiscoverState
# flow collectors never touch the Live TV guide layout.
replace_method(
    discover,
    "onViewCreated",
    """    .locals 1

    const-string v0, "view"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-super {p0, p1, p2}, Landroidx/fragment/app/Fragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V

    invoke-static {p0, p1}, Lcom/jedflix/tv/LiveTvGuide;->attach(Ljava/lang/Object;Landroid/view/View;)V

    return-void""",
)

# Replace DiscoverFragment.onResume so it resumes the Live TV guide instead of
# calling updateFocus() on the uninitialized picker/rowsView properties.
replace_method(
    discover,
    "onResume",
    """    .locals 0

    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onResume()V

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvGuide;->resume(Ljava/lang/Object;)V

    return-void""",
)

# Override onCreateView so fragment_discover.xml (and its child FragmentContainerViews
# for MetaPreviewFragment and CatalogRowsFragment) is never inflated.
lifecycle_methods = """.method public onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;
    .locals 1

    const-string v0, "inflater"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {p1}, Landroid/view/LayoutInflater;->getContext()Landroid/content/Context;

    move-result-object v0

    invoke-static {p0, v0}, Lcom/jedflix/tv/LiveTvGuide;->create(Ljava/lang/Object;Landroid/content/Context;)Landroid/view/View;

    move-result-object v0

    return-object v0
.end method

.method public onPause()V
    .locals 0

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvGuide;->pause(Ljava/lang/Object;)V

    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onPause()V

    return-void
.end method

.method public onDestroyView()V
    .locals 0

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvGuide;->destroy(Ljava/lang/Object;)V

    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onDestroyView()V

    return-void
.end method
"""

discover.write_text(discover.read_text().rstrip() + "\n\n" + lifecycle_methods)

# Update the left navigation menu tab for @id/discover to use the Live TV label
# and the bundled TV channel icons (channels / channels_outline).
tab_before = """    .line 286
    new-instance v34, Lcom/stremio/tv/views/menu/Tab;

    .line 287
    sget v35, Lcom/stremio/tv/R$id;->discover:I

    .line 288
    invoke-interface {v0}, Lcom/stremio/translations/Strings;->getLabel_stremio_tv_nav_discover()Ljava/lang/String;

    move-result-object v36

    .line 289
    sget v37, Lcom/stremio/tv/R$drawable;->discover:I

    .line 290
    sget v38, Lcom/stremio/tv/R$drawable;->discover_outline:I

    .line 286
    invoke-direct/range {v34 .. v39}, Lcom/stremio/tv/views/menu/Tab;-><init>(ILjava/lang/String;IILandroidx/compose/ui/focus/FocusRequester;)V"""

tab_after = """    .line 286
    new-instance v34, Lcom/stremio/tv/views/menu/Tab;

    .line 287
    sget v35, Lcom/stremio/tv/R$id;->discover:I

    .line 288
    invoke-interface {v0}, Lcom/stremio/translations/Strings;->getLabel_stremio_tv_nav_discover()Ljava/lang/String;

    move-result-object v36

    const-string v36, "Live TV"

    .line 289
    sget v37, Lcom/stremio/tv/R$drawable;->channels:I

    .line 290
    sget v38, Lcom/stremio/tv/R$drawable;->channels_outline:I

    .line 286
    invoke-direct/range {v34 .. v39}, Lcom/stremio/tv/views/menu/Tab;-><init>(ILjava/lang/String;IILandroidx/compose/ui/focus/FocusRequester;)V"""

edit(menu, tab_before, tab_after)

# Rename Discover strings in XML resources and Compose translation classes across all locales.
live_tv_labels = {
    "label_stremio_tv_nav_discover": "Live TV",
    "label_discover": "Live TV",
}

xml_patched = 0
for path in (stage / "res").glob("values*/strings.xml"):
    source = path.read_text()
    updated = source
    for name, label in live_tv_labels.items():
        updated = re.sub(
            r'(<string name="' + name + r'"[^>]*>).*?(</string>)',
            lambda m, text=label: m[1] + text + m[2],
            updated,
            flags=re.S,
        )
    if updated != source:
        path.write_text(updated)
        xml_patched += 1

assert xml_patched >= 50, f"Expected to patch Discover XML strings across locales, patched {xml_patched}"
require(stage / "res/values/strings.xml", '<string name="label_stremio_tv_nav_discover">Live TV</string>', 1)

smali_translations_patched = 0
for directory in ("smali_classes5", "smali_classes9"):
    translations_dir = stage / directory / "com/stremio/translations"
    if not translations_dir.exists():
        continue
    for path in translations_dir.glob("*StringsClass.smali"):
        for name, label in live_tv_labels.items():
            method_name = "get" + name[0].upper() + name[1:]
            replace_method(
                path,
                method_name,
                f'    .locals 1\n    const-string v0, "{label}"\n    return-object v0',
            )
        smali_translations_patched += 1

assert smali_translations_patched >= 1, "No Compose translation classes found to patch"

RESULT_MARKERS = [
    (discover, "Lcom/jedflix/tv/LiveTvGuide;->create(Ljava/lang/Object;Landroid/content/Context;)Landroid/view/View;", 1),
    (discover, "Lcom/jedflix/tv/LiveTvGuide;->attach(Ljava/lang/Object;Landroid/view/View;)V", 1),
    (discover, "Lcom/jedflix/tv/LiveTvGuide;->resume(Ljava/lang/Object;)V", 1),
    (discover, "Lcom/jedflix/tv/LiveTvGuide;->pause(Ljava/lang/Object;)V", 1),
    (discover, "Lcom/jedflix/tv/LiveTvGuide;->destroy(Ljava/lang/Object;)V", 1),
    (discover, "Lkotlinx/coroutines/flow/FlowKt;->launchIn(", 0),
    (discover, "Landroidx/activity/OnBackPressedDispatcher;->addCallback(", 0),
    (menu, "Lcom/stremio/tv/R$drawable;->channels:I", 1),
    (menu, "Lcom/stremio/tv/R$drawable;->channels_outline:I", 1),
    (menu, "Lcom/stremio/tv/R$drawable;->discover:I", 0),
    (menu, "Lcom/stremio/tv/R$drawable;->discover_outline:I", 0),
]

for marker in RESULT_MARKERS:
    require(*marker)

print("Replaced Discover with Jedflix Live TV guide")
