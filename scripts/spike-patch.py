#!/usr/bin/env python3
"""Experimental account bypass and library seam probe; never a production login."""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1]) / "smali_classes6/com/stremio/common"
login = root / "viewmodels/LoginViewModel.smali"
source = login.read_text()
pattern = r"\.method public final createLinkCode\(\)V.*?\.end method"
replacement = """.method public final createLinkCode()V
    .locals 2
    const-string v0, "JedflixSpike"
    const-string v1, "account-link intercepted; entering local guest mode, NOT Jedflix authentication"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    invoke-virtual {p0}, Lcom/stremio/common/viewmodels/LoginViewModel;->loginAsGuest()V
    return-void
.end method"""
source, count = re.subn(pattern, replacement, source, flags=re.S)
assert count == 1, "Upstream login seam changed"
login.write_text(source)

api = root / "api/StremioApi.smali"
source = api.read_text()
for name in ("addLibraryItem", "removeLibraryItem", "syncLibrary", "loadPlayer", "markAsWatched"):
    pattern = rf"(\.method public final {name}(?:-|\()[^\n]*\n    \.locals (\d+)\n)"
    def instrument(match):
        assert int(match[2]) >= 2
        return match[1] + f'''    const-string v0, "JedflixSpike"
    const-string v1, "facade seam: {name} (upstream behavior retained)"
    invoke-static {{v0, v1}}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
'''
    source, count = re.subn(pattern, instrument, source)
    if name != "markAsWatched":
        assert count == 1, f"Upstream {name} seam changed: {count}"
api.write_text(source)
print("Patched account-link entry and instrumented library/player facade; no cloud auth implemented")
