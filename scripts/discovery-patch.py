#!/usr/bin/env python3
"""Wire Home discovery into the staged Stremio TV bytecode."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: discovery-patch.py <decoded-stage>")
stage = Path(sys.argv[1])

smali5 = stage / "smali_classes5"
smali6 = stage / "smali_classes6"
application = smali5 / "com/stremio/tv/StremioApplication.smali"
board = smali6 / "com/stremio/tv/views/board/BoardFragment.smali"
collector = smali6 / "com/stremio/tv/views/board/BoardFragment$onViewCreated$1.smali"
view_model = smali6 / "com/stremio/common/viewmodels/BoardViewModel.smali"
catalog_rows = smali6 / "com/stremio/common/viewmodels/CatalogRowsViewModel.smali"
storage = smali6 / "com/stremio/common/storage/CoreStorage.smali"
preview = smali6 / "com/stremio/common/viewmodels/MetaPreviewViewModel.smali"


def require(path, needle, expected=1):
    source = path.read_text()
    found = source.count(needle)
    assert found == expected, (
        f"Upstream discovery seam changed: {path}: {needle[:120]!r} "
        f"(expected {expected}, found {found})"
    )


def edit(path, before, after):
    require(path, before)
    path.write_text(path.read_text().replace(before, after, 1))


UPSTREAM_MARKERS = [
    (application, ".method public onCreate()V"),
    (application, "invoke-static {p0}, Lcom/jedflix/tv/CometConfig;->init(Landroid/content/Context;)V"),
    (board, ".method public onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V"),
    (board, "invoke-direct {p0}, Lcom/stremio/tv/views/board/BoardFragment;->getBoardViewModel()Lcom/stremio/common/viewmodels/BoardViewModel;"),
    (collector, "check-cast v0, Lcom/stremio/common/viewmodels/state/BoardState;"),
    (view_model, ".field private final _state:Lkotlinx/coroutines/flow/MutableStateFlow;"),
    (view_model, "invoke-direct {v0, v2, v2, v1, v2}, Lcom/stremio/common/viewmodels/state/BoardState;-><init>(Ljava/util/List;Ljava/util/List;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"),
    (catalog_rows, ".method private final loadCatalogRows(Lcom/stremio/common/viewmodels/state/MetaRow;)V\n    .locals 5"),
    (catalog_rows, ".method private final loadNextPage(Lcom/stremio/common/viewmodels/state/MetaRow;Ljava/lang/Object;)V\n    .locals 4"),
    (storage, ".method public constructor <init>(Landroid/content/SharedPreferences;)V"),
    (storage, "iput-object p1, p0, Lcom/stremio/common/storage/CoreStorage;->sharedPreferences:Landroid/content/SharedPreferences;"),
]

for marker_path, marker in UPSTREAM_MARKERS:
    require(marker_path, marker)

attach_needle = """    invoke-direct {p0}, Lcom/stremio/tv/views/board/BoardFragment;->getBoardViewModel()Lcom/stremio/common/viewmodels/BoardViewModel;

    move-result-object p1

    invoke-virtual {p1}, Lcom/stremio/common/viewmodels/BoardViewModel;->getState()Lkotlinx/coroutines/flow/StateFlow;"""
edit(board, attach_needle, """    invoke-direct {p0}, Lcom/stremio/tv/views/board/BoardFragment;->getBoardViewModel()Lcom/stremio/common/viewmodels/BoardViewModel;

    move-result-object p1

    invoke-static {p1}, Lcom/jedflix/tv/HomeDiscovery;->attach(Ljava/lang/Object;)V

    invoke-virtual {p1}, Lcom/stremio/common/viewmodels/BoardViewModel;->getState()Lkotlinx/coroutines/flow/StateFlow;""")

state_needle = """    check-cast v0, Lcom/stremio/common/viewmodels/state/BoardState;

    invoke-static {}, Lkotlin/coroutines/intrinsics/IntrinsicsKt;->getCOROUTINE_SUSPENDED()Ljava/lang/Object;"""
edit(collector, state_needle, """    check-cast v0, Lcom/stremio/common/viewmodels/state/BoardState;

    invoke-static {v0}, Lcom/jedflix/tv/HomeDiscovery;->onState(Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    check-cast v0, Lcom/stremio/common/viewmodels/state/BoardState;

    invoke-static {}, Lkotlin/coroutines/intrinsics/IntrinsicsKt;->getCOROUTINE_SUSPENDED()Ljava/lang/Object;""")

edit(collector, "    const/16 v8, 0xe", """    invoke-static {v4}, Lcom/jedflix/tv/HomeDiscovery;->rowDefaults(Ljava/lang/Object;)I

    move-result v8""")

# Native enrichment retains its library/IMDb updates while keeping the complete
# TMDB artwork and synopsis supplied by local discovery.
preview_result = smali6 / "com/stremio/common/viewmodels/MetaPreviewViewModel$loadFullMeta$1$1.smali"
edit(preview_result, """    invoke-interface {p1, v0}, Lkotlinx/coroutines/flow/MutableStateFlow;->setValue(Ljava/lang/Object;)V""", """    invoke-interface {p1}, Lkotlinx/coroutines/flow/MutableStateFlow;->getValue()Ljava/lang/Object;

    move-result-object v1

    invoke-static {v0, v1}, Lcom/jedflix/tv/HomeDiscovery;->mergeFullPreview(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    invoke-interface {p1, v0}, Lkotlinx/coroutines/flow/MutableStateFlow;->setValue(Ljava/lang/Object;)V""")

publish = """.method public final jedflixSetCatalogs(Ljava/util/List;)V
    .locals 4

    iget-object v0, p0, Lcom/stremio/common/viewmodels/BoardViewModel;->_state:Lkotlinx/coroutines/flow/MutableStateFlow;

    invoke-interface {v0}, Lkotlinx/coroutines/flow/MutableStateFlow;->getValue()Ljava/lang/Object;

    move-result-object v1

    check-cast v1, Lcom/stremio/common/viewmodels/state/BoardState;

    const/4 v2, 0x1

    const/4 v3, 0x0

    invoke-static {v1, v3, p1, v2, v3}, Lcom/stremio/common/viewmodels/state/BoardState;->copy$default(Lcom/stremio/common/viewmodels/state/BoardState;Ljava/util/List;Ljava/util/List;ILjava/lang/Object;)Lcom/stremio/common/viewmodels/state/BoardState;

    move-result-object v1

    invoke-interface {v0, v1}, Lkotlinx/coroutines/flow/MutableStateFlow;->setValue(Ljava/lang/Object;)V

    return-void
.end method
"""
require(view_model, ".method public final jedflixSetCatalogs(Ljava/util/List;)V", 0)
view_model.write_text(view_model.read_text().rstrip() + "\n\n" + publish)

on_resume = """.method public onResume()V
    .locals 0

    invoke-super {p0}, Lcom/stremio/tv/views/FocusableFragment;->onResume()V

    invoke-static {p0}, Lcom/jedflix/tv/HomeDiscovery;->onResume(Ljava/lang/Object;)V

    return-void
.end method
"""
on_pause = """.method public onPause()V
    .locals 0

    invoke-static {p0}, Lcom/jedflix/tv/HomeDiscovery;->onPause(Ljava/lang/Object;)V

    invoke-super {p0}, Lcom/stremio/tv/views/FocusableFragment;->onPause()V

    return-void
.end method
"""

board_source = board.read_text()
for signature, hook, method in (
        (".method public onResume()V", "Lcom/jedflix/tv/HomeDiscovery;->onResume", on_resume),
        (".method public onPause()V", "Lcom/jedflix/tv/HomeDiscovery;->onPause", on_pause)):
    if signature in board_source:
        assert hook in board_source, (
            f"Upstream BoardFragment already defines {signature} without the Jedflix hook"
        )
    else:
        board_source = board_source.rstrip() + "\n\n" + method
        board.write_text(board_source)
        board_source = board.read_text()

lifecycle_init = "    invoke-static {p0}, Lcom/jedflix/tv/CometConfig;->init(Landroid/content/Context;)V"
edit(application, lifecycle_init, lifecycle_init + """

    invoke-static {p0}, Lcom/jedflix/tv/HomeDiscovery;->init(Landroid/content/Context;)V""")

storage_init = "    iput-object p1, p0, Lcom/stremio/common/storage/CoreStorage;->sharedPreferences:Landroid/content/SharedPreferences;"
edit(storage, storage_init, storage_init + """

    invoke-static {p1}, Lcom/jedflix/tv/DiscoverySignals;->attach(Landroid/content/SharedPreferences;)V""")

load_catalog_needle = """.method private final loadCatalogRows(Lcom/stremio/common/viewmodels/state/MetaRow;)V
    .locals 5
"""
edit(catalog_rows, load_catalog_needle, load_catalog_needle + """
    invoke-static {p1}, Lcom/jedflix/tv/HomeDiscovery;->isSyntheticRow(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :jedflix_load_catalog_rows

    return-void

    :jedflix_load_catalog_rows
""")

load_next_needle = """.method private final loadNextPage(Lcom/stremio/common/viewmodels/state/MetaRow;Ljava/lang/Object;)V
    .locals 4
"""
edit(catalog_rows, load_next_needle, load_next_needle + """
    invoke-static {p1}, Lcom/jedflix/tv/HomeDiscovery;->isSyntheticRow(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :jedflix_load_next_page

    return-void

    :jedflix_load_next_page
""")

RESULT_MARKERS = [
    (application, "Lcom/jedflix/tv/HomeDiscovery;->init(Landroid/content/Context;)V"),
    (board, "Lcom/jedflix/tv/HomeDiscovery;->attach(Ljava/lang/Object;)V"),
    (board, "Lcom/jedflix/tv/HomeDiscovery;->onResume(Ljava/lang/Object;)V"),
    (board, "Lcom/jedflix/tv/HomeDiscovery;->onPause(Ljava/lang/Object;)V"),
    (collector, "Lcom/jedflix/tv/HomeDiscovery;->onState(Ljava/lang/Object;)Ljava/lang/Object;"),
    (view_model, ".method public final jedflixSetCatalogs(Ljava/util/List;)V"),
    (view_model, "Lkotlinx/coroutines/flow/MutableStateFlow;->setValue(Ljava/lang/Object;)V"),
    (storage, "Lcom/jedflix/tv/DiscoverySignals;->attach(Landroid/content/SharedPreferences;)V"),
    (catalog_rows, "Lcom/jedflix/tv/HomeDiscovery;->isSyntheticRow(Ljava/lang/Object;)Z", 2),
]

for marker in RESULT_MARKERS:
    require(*marker)

print("Added Home discovery wiring")
