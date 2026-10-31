#!/usr/bin/env python3
"""Hide the compact logo while the navigation drawer shows the wordmark."""
from pathlib import Path
import sys

path = Path(sys.argv[1]) / "smali_classes6/com/stremio/tv/views/menu/NavigationMenuKt.smali"
source = path.read_text()

start_marker = "    .line 236\n    sget v1, Lcom/stremio/tv/R$drawable;->ic_stremio_logo:I\n"
end_marker = "    move-object/from16 v1, v17\n"
assert source.count(start_marker) == 1, "Upstream navigation logo layout changed"
start = source.index(start_marker)
end = source.index(end_marker, start) + len(end_marker)
block = source[start:end]
for expected in ('ic_stremio_logo', 'move v3, v11', 'ImageKt;->Image'):
    assert expected in block, f"Upstream navigation logo layout changed: {expected}"

replacement = (
    "    if-eqz v7, :logo_draw_icon\n"
    "\n"
    "    .line 236\n"
    "    move v3, v11\n"
    "    move-object/from16 v1, v10\n"
    "\n"
    "    goto :logo_after_icon\n"
    "\n"
    "    :logo_draw_icon\n"
    + block +
    "\n    :logo_after_icon\n"
)
path.write_text(source[:start] + replacement + source[end:])
print("The compact logo is hidden while the navigation wordmark is expanded")
