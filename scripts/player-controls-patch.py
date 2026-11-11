#!/usr/bin/env python3
"""Hide player control buttons Jedflix viewers do not need.

The stock Controls row shows, in order: Play/Pause, Next, Rewind | Subtitles,
Audio, Speed, Scale | Players, Chapters, Episodes, Statistics. Each optional
button sits in its own FadeInOut(visible, ...) call. This patch passes
`visible = false` for Speed, Scale (crop/zoom), Players (engine picker) and
Statistics, and shows the second separator only when Chapters or Episodes is
visible. The player engine stays selectable in Settings -> Playback.

Only the visibility arguments change. The original parameter registers are
left intact so the Compose restart lambda recomposes with the real values.
"""
from pathlib import Path
import sys

stage = Path(sys.argv[1])
controls = stage / 'smali_classes6/com/stremio/tv/views/player/overlay/ControlsKt.smali'
FADE = 'Lcom/stremio/common/composables/animations/FadeInOutKt;->FadeInOut(ZLkotlin/jvm/functions/Function2;Landroidx/compose/runtime/Composer;I)V'
source = controls.read_text()


def edit(name, before, after):
    global source
    assert source.count(before) == 1, f'Upstream player controls seam changed: {name}'
    source = source.replace(before, after)


# Speed: ControlsKt$$ExternalSyntheticLambda1 renders Icons.speed. v8 holds
# hasPlaybackSpeeds and is reassigned immediately afterwards.
edit('speed', f'''    move/from16 v15, p11

    invoke-static {{v8, v14, v11, v15}}, {FADE}''', f'''    move/from16 v15, p11

    const/4 v8, 0x0

    invoke-static {{v8, v14, v11, v15}}, {FADE}''')

# Scale: Lambda2 renders Icons.scale. v7 must keep hasScalingModes for the
# restart lambda, so use v14, which is reassigned before its next read.
edit('scale', f'''    invoke-static {{v7, v8, v11, v9}}, {FADE}''', f'''    const/4 v14, 0x0

    invoke-static {{v14, v8, v11, v9}}, {FADE}''')

# Separator before the Players/Chapters/Episodes/Statistics group: keep it only
# for the buttons that remain.
edit('separator', '''    if-nez v21, :cond_3f

    if-nez p7, :cond_3f

    if-nez p8, :cond_3f

    if-eqz v20, :cond_3e

    goto :goto_30''', '''    if-nez p7, :cond_3f

    if-nez p8, :cond_3f

    goto :cond_3e''')

# Players: Lambda3 renders Icons.external_player.
edit('players', f'''    move/from16 v9, v21

    invoke-static {{v9, v8, v11, v15}}, {FADE}''', f'''    const/4 v9, 0x0

    invoke-static {{v9, v8, v11, v15}}, {FADE}''')

# Statistics: Lambda6 renders Icons.network.
edit('statistics', f'''    move/from16 v8, v20

    invoke-static {{v8, v0, v11, v9}}, {FADE}''', f'''    const/4 v8, 0x0

    invoke-static {{v8, v0, v11, v9}}, {FADE}''')

controls.write_text(source)
