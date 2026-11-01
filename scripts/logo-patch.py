#!/usr/bin/env python3
"""Keep the compact logo at the same size and position when the drawer expands."""
from pathlib import Path
import sys

path = Path(sys.argv[1]) / "smali_classes6/com/stremio/tv/views/menu/NavigationMenuKt.smali"
source = path.read_text()

# Upstream draws the compact icon on every state, then overlays the expanded
# wordmark (ic_stremio_logo_expanded) when the drawer is open. Keep the compact
# icon alone so both states render identical artwork at the same 35dp size and
# top-start position. The FadeInOut content is hidden, not removed, so no layout
# changes are needed.
expanded_visibility = (
    "    if-eqz v7, :cond_a\n"
    "\n"
    "    .line 240\n"
    "    sget-object v2, Landroidx/compose/ui/unit/LayoutDirection;->Ltr:Landroidx/compose/ui/unit/LayoutDirection;\n"
    "\n"
    "    if-ne v8, v2, :cond_a\n"
    "\n"
    "    move/from16 v3, v20\n"
)
assert source.count(expanded_visibility) == 1, "Upstream navigation logo layout changed"
assert source.count("sget v1, Lcom/stremio/tv/R$drawable;->ic_stremio_logo:I") == 1, "Upstream navigation logo layout changed"
assert "const/16 v2, 0x23\n" in source, "Upstream navigation logo size changed"

source = source.replace(
    expanded_visibility,
    expanded_visibility.replace(
        "    move/from16 v3, v20\n",
        "    const/4 v3, 0x0\n",
    ),
)
path.write_text(source)
print("The expanded navigation drawer keeps the compact logo")
