#!/usr/bin/env python3
"""Add Night mode to the stock player Audio menu and bind it to mpv's lifecycle."""
from pathlib import Path
import sys

stage = Path(sys.argv[1])
smali = stage / 'smali_classes6'


def edit(path, before, after):
    source = path.read_text()
    assert source.count(before) == 1, f'Upstream Night mode seam changed: {path}: {before[:100]}'
    path.write_text(source.replace(before, after))


manager = smali / 'com/stremio/common/players/PlaybackManager.smali'
needle = '''    iput-object v1, v0, Lcom/stremio/common/players/PlaybackManager;->playerListener:Lcom/stremio/common/players/PlaybackManager$playerListener$1;

    return-void'''
edit(manager, needle, '''    iput-object v1, v0, Lcom/stremio/common/players/PlaybackManager;->playerListener:Lcom/stremio/common/players/PlaybackManager$playerListener$1;

    invoke-static {v0, v2}, Lcom/jedflix/tv/NightAudio;->bindManager(Ljava/lang/Object;Landroid/content/Context;)V

    return-void''')

# Normalize the actual state and the instantiated player together, rather than
# quietly substituting an engine behind an ExoPlayer label. External playback
# remains an explicit choice. The user-change hook below exits Night mode when
# the user deliberately chooses a different engine (including error fallback).
needle = '''.method public final updatePlayerType(Lcom/stremio/common/viewmodels/state/PlayerType;)V
    .locals 1'''
edit(manager, needle, needle + '''

    invoke-static {p1}, Lcom/jedflix/tv/NightAudio;->resolvePlayerType(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object p1
    check-cast p1, Lcom/stremio/common/viewmodels/state/PlayerType;''')
needle = '''.method public final update(Lcom/stremio/core/types/resource/Stream;Lcom/stremio/common/viewmodels/state/PlayerType;)V
    .locals 4'''
edit(manager, needle, needle + '''

    invoke-static {p2}, Lcom/jedflix/tv/NightAudio;->resolvePlayerType(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object p2
    check-cast p2, Lcom/stremio/common/viewmodels/state/PlayerType;''')
needle = '''.method public final playerChanged(Lcom/stremio/common/viewmodels/state/PlayerType;)V
    .locals 2'''
edit(manager, needle, needle + '''

    invoke-static {p1}, Lcom/jedflix/tv/NightAudio;->onUserPlayerChange(Ljava/lang/Object;)V''')
needle = '''.method public final release()V
    .locals 5'''
edit(manager, needle, needle + '''

    invoke-static {p0}, Lcom/jedflix/tv/NightAudio;->unbindManager(Ljava/lang/Object;)V''')

player = smali / 'com/stremio/common/players/MpvPlayer.smali'
needle = '    invoke-virtual {v1}, Ldev/jdtech/mpv/MPVLib;->init()V'
edit(player, needle, needle + '''

    invoke-static {v1}, Lcom/jedflix/tv/NightAudio;->attach(Ljava/lang/Object;)V''')
needle = '    invoke-virtual {v1}, Ldev/jdtech/mpv/MPVLib;->destroy()V'
edit(player, needle, '''    invoke-static {v1}, Lcom/jedflix/tv/NightAudio;->detach(Ljava/lang/Object;)V

''' + needle)

# Use one stock MenuList for Night mode and Audio delay so lazy-list sizing and
# remote focus behave exactly like the existing menu. Its auxiliary pane must
# also exist when the current engine has no Audio delay capability.
menu = smali / 'com/stremio/tv/views/player/menus/AudioMenuKt.smali'
needle = '''    if-nez v0, :cond_2

    const v0, 0x66e99d5'''
edit(menu, needle, '''    # Night mode always provides an auxiliary menu row.
    nop

    const v0, 0x66e99d5''')
needle = '''    .line 52
    invoke-static/range {v1 .. v6}, Lcom/stremio/tv/composables/MenuKt;->MenuList(Landroidx/compose/ui/Modifier;Ljava/util/List;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;II)V'''
edit(menu, needle, '''    .line 52
    invoke-static {v2, v3, p3, p2}, Lcom/jedflix/tv/NightAudio;->Content(Ljava/util/List;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;Lkotlin/jvm/functions/Function0;)V''')
