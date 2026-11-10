#!/usr/bin/env python3
"""Attach one shared live TV session to the stock stream-only player.

The pinned upstream smali is asserted before every edit so a STREMIO update
fails the build loudly instead of silently playing video-on-demand with a live
session attached. Hooks:

* PlaybackManager.createPlayer  -> adjust the initial media offset
* PlaybackManager.update        -> bind the manager to the resolved stream
* PlaybackManager.release       -> detach the manager (player teardown)
* PlaybackManager$playerListener$1.onEnded
                                -> suppress stock ended handling for live
* PlayerPage pause/seek lambdas -> explicit user pause/seek, never buffering
* MediaControls media session   -> remote pause/seek
* PlayerFragment.onCreateView   -> navigation/process restore binding
* player Overlay Row            -> native Go Live text button
* JedflixLiveButtonKt clone     -> isolated ButtonKt clone that consumes Center/Enter

Run this against the decoded apktool stage, after the regular theme build and
before apktool assembles the APK.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: live-tv-playback-patch.py <decoded-stage>")

stage = Path(sys.argv[1])
smali = stage / "smali_classes6"


def source_of(relative):
    path = smali / relative
    if not path.exists():
        raise SystemExit(f"Missing pinned smali: {path}")
    return path


def patch(relative, before, after, what):
    path = source_of(relative)
    source = path.read_text()
    count = source.count(before)
    if count != 1:
        raise SystemExit(
            f"Upstream {what} seam changed in {relative}: "
            f"expected 1 match, found {count}")
    path.write_text(source.replace(before, after, 1))


def assert_method(relative, header):
    source = source_of(relative).read_text()
    if header not in source:
        raise SystemExit(f"Upstream method moved in {relative}: {header[:120]}")


def assert_result(relative, needle, what):
    source = source_of(relative).read_text()
    if needle not in source:
        raise SystemExit(f"Live TV playback hook missing in {relative}: {what}")


MANAGER = "com/stremio/common/players/PlaybackManager.smali"
LISTENER = "com/stremio/common/players/PlaybackManager$playerListener$1.smali"
PAGE = "com/stremio/tv/views/player/PlayerPageKt.smali"
MEDIA = "com/stremio/common/players/MediaControls$mediaSessionCallback$1.smali"
FRAGMENT = "com/stremio/tv/views/player/PlayerFragment.smali"
OVERLAY = "com/stremio/tv/views/player/overlay/OverlayKt.smali"

# Pinned method shapes. These are the exact seams the offsets rely on; if any
# moves, the patch must be revisited rather than guessed at runtime.
assert_method(MANAGER, ".method private final createPlayer(Lcom/stremio/core/types/resource/Stream;"
                       "Lcom/stremio/common/viewmodels/state/PlayerType;)"
                       "Lcom/stremio/common/players/Player;\n    .locals 9")
assert_method(MANAGER, ".method public final update(Lcom/stremio/core/types/resource/Stream;"
                       "Lcom/stremio/common/viewmodels/state/PlayerType;)V\n    .locals 4")
assert_method(MANAGER, ".method public final release()V\n    .locals 5")
assert_method(LISTENER, ".method public onEnded()V\n    .locals 4")
assert_method(PAGE, ".method private static final PlayerPage$lambda$20$0("
                     "Lcom/stremio/common/players/Player;Landroidx/compose/runtime/State;)"
                     "Lkotlin/Unit;\n    .locals 1")
assert_method(PAGE, ".method private static final PlayerPage$lambda$22$0("
                     "Lcom/stremio/common/players/Player;)Lkotlin/Unit;\n    .locals 2")
assert_method(PAGE, ".method private static final PlayerPage$lambda$23$0("
                     "Lcom/stremio/common/players/Player;J)Lkotlin/Unit;\n    .locals 0")
assert_method(PAGE, ".method private static final PlayerPage$lambda$34$0("
                     "Lcom/stremio/common/players/Player;Lcom/stremio/common/players/Chapter;)"
                     "Lkotlin/Unit;\n    .locals 2")
assert_method(MEDIA, ".method public onPause()V\n    .locals 1")
assert_method(MEDIA, ".method public onSeekTo(J)V\n    .locals 1")
assert_method(FRAGMENT, ".method public onCreateView(Landroid/view/LayoutInflater;"
                        "Landroid/view/ViewGroup;Landroid/os/Bundle;)"
                        "Landroid/view/View;\n    .locals 6")
assert_method(OVERLAY, ".method public static final Overlay(")

# 1. Offset the stock player to the live edge at the moment it is created.
patch(MANAGER, """    invoke-virtual {v2}, Lcom/stremio/common/viewmodels/state/PlayerState;->getInitialPosition()J

    move-result-wide v7
""", """    invoke-virtual {v2}, Lcom/stremio/common/viewmodels/state/PlayerState;->getInitialPosition()J

    move-result-wide v7

    invoke-static {p1, v7, v8}, Lcom/jedflix/tv/LiveTvPlayback;->adjustInitialPosition(Ljava/lang/Object;J)J

    move-result-wide v7
""", "createPlayer initial position")

# 2. Bind the manager before the player is recreated, so engine changes and
#    stream-only launches keep the same live session identity.
patch(MANAGER, """.method public final update(Lcom/stremio/core/types/resource/Stream;Lcom/stremio/common/viewmodels/state/PlayerType;)V
    .locals 4
""", """.method public final update(Lcom/stremio/core/types/resource/Stream;Lcom/stremio/common/viewmodels/state/PlayerType;)V
    .locals 4

    invoke-static {p0, p1}, Lcom/jedflix/tv/LiveTvPlayback;->attach(Ljava/lang/Object;Ljava/lang/Object;)V
""", "PlaybackManager.update")

# 3. Detach on release. The helper keeps a pending replacement session alive.
patch(MANAGER, """.method public final release()V
    .locals 5
""", """.method public final release()V
    .locals 5

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->detach(Ljava/lang/Object;)V
""", "PlaybackManager.release")

# 4. Suppress the stock end-of-video path for an active live session. The
#    helper either waits for the broadcast boundary or advances a delayed
#    session to the following scheduled episode.
patch(LISTENER, """.method public onEnded()V
    .locals 4

    .line 293
""", """.method public onEnded()V
    .locals 5

    iget-object v4, p0, Lcom/stremio/common/players/PlaybackManager$playerListener$1;->this$0:Lcom/stremio/common/players/PlaybackManager;

    invoke-static {v4}, Lcom/jedflix/tv/LiveTvPlayback;->onEnded(Ljava/lang/Object;)Z

    move-result v4

    if-eqz v4, :jedflix_live_ended_continue

    return-void

    :jedflix_live_ended_continue

    .line 293
""", "playerListener.onEnded")

# 5. Explicit user pause toggle. Buffering and initial isPlaying=false are not
#    treated as pauses anywhere; only this control (and the media session) is.
patch(PAGE, """.method private static final PlayerPage$lambda$20$0(Lcom/stremio/common/players/Player;Landroidx/compose/runtime/State;)Lkotlin/Unit;
    .locals 1
""", """.method private static final PlayerPage$lambda$20$0(Lcom/stremio/common/players/Player;Landroidx/compose/runtime/State;)Lkotlin/Unit;
    .locals 1

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->onUserPause(Ljava/lang/Object;)V
""", "pause toggle")

# 6. Explicit seek paths: restart, timeline scrub and chapter selection. Remote
#    FF/RW reaches the timeline callback, so seeks always leave the live edge.
patch(PAGE, """.method private static final PlayerPage$lambda$22$0(Lcom/stremio/common/players/Player;)Lkotlin/Unit;
    .locals 2
""", """.method private static final PlayerPage$lambda$22$0(Lcom/stremio/common/players/Player;)Lkotlin/Unit;
    .locals 2

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->onUserSeek(Ljava/lang/Object;)V
""", "restart seek")
patch(PAGE, """.method private static final PlayerPage$lambda$23$0(Lcom/stremio/common/players/Player;J)Lkotlin/Unit;
    .locals 0
""", """.method private static final PlayerPage$lambda$23$0(Lcom/stremio/common/players/Player;J)Lkotlin/Unit;
    .locals 0

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->onUserSeek(Ljava/lang/Object;)V
""", "timeline seek")
patch(PAGE, """.method private static final PlayerPage$lambda$34$0(Lcom/stremio/common/players/Player;Lcom/stremio/common/players/Chapter;)Lkotlin/Unit;
    .locals 2
""", """.method private static final PlayerPage$lambda$34$0(Lcom/stremio/common/players/Player;Lcom/stremio/common/players/Chapter;)Lkotlin/Unit;
    .locals 2

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->onUserSeek(Ljava/lang/Object;)V
""", "chapter seek")

# 7. Media-session pause/seek (TV remote and companion device events).
patch(MEDIA, """    if-eqz v0, :cond_0

    invoke-interface {v0}, Lcom/stremio/common/players/Player;->pause()V
""", """    if-eqz v0, :cond_0

    invoke-static {v0}, Lcom/jedflix/tv/LiveTvPlayback;->onUserPause(Ljava/lang/Object;)V

    invoke-interface {v0}, Lcom/stremio/common/players/Player;->pause()V
""", "media session pause")
patch(MEDIA, """    if-eqz v0, :cond_0

    invoke-interface {v0, p1, p2}, Lcom/stremio/common/players/Player;->setTime(J)V
""", """    if-eqz v0, :cond_0

    invoke-static {v0}, Lcom/jedflix/tv/LiveTvPlayback;->onUserSeek(Ljava/lang/Object;)V

    invoke-interface {v0, p1, p2}, Lcom/stremio/common/players/Player;->setTime(J)V
""", "media session seek")

# 8. Keep a weak player-page binding and restore the session markers after
#    process death. This runs for normal VOD too and does nothing without them.
patch(FRAGMENT, """.method public onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;
    .locals 6

    const-string p2, "inflater"
""", """.method public onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;
    .locals 6

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->bindPlayerFragment(Ljava/lang/Object;)V

    const-string p2, "inflater"
""", "PlayerFragment.onCreateView")

# Clear a tune when Back leaves before its asynchronous player ever attaches.
# Armed player-to-player replacements are distinguished by their stream data.
fragment_source = source_of(FRAGMENT).read_text()
if ".method public onDestroyView()V" in fragment_source:
    raise SystemExit("Upstream PlayerFragment now defines onDestroyView; revisit teardown hook")
source_of(FRAGMENT).write_text(fragment_source.rstrip() + """

.method public onDestroyView()V
    .locals 0

    invoke-static {p0}, Lcom/jedflix/tv/LiveTvPlayback;->unbindPlayerFragment(Ljava/lang/Object;)V

    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onDestroyView()V

    return-void
.end method
""")

# 9. Go Live lives in the existing overlay Row, immediately after the native
#    Controls row and before the right-hand status group. The composer has just
#    been restored into v1, which keeps the surrounding group stack valid.
patch(OVERLAY, """    invoke-static/range {v0 .. v21}, Lcom/stremio/tv/views/player/overlay/ControlsKt;->Controls(Landroidx/compose/ui/focus/FocusRequester;Lcom/stremio/common/viewmodels/state/PlayerType;ZZZZZZZZLcom/stremio/core/types/resource/Stream;Lcom/stremio/core/models/LoadableStatistics;ZLkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;III)V

    move-object/from16 v1, v18
""", """    invoke-static/range {v0 .. v21}, Lcom/stremio/tv/views/player/overlay/ControlsKt;->Controls(Landroidx/compose/ui/focus/FocusRequester;Lcom/stremio/common/viewmodels/state/PlayerType;ZZZZZZZZLcom/stremio/core/types/resource/Stream;Lcom/stremio/core/models/LoadableStatistics;ZLkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;III)V

    move-object/from16 v1, v18

    invoke-static {v1}, Lcom/jedflix/tv/LiveTvControls;->Content(Landroidx/compose/runtime/Composer;)V
""", "overlay controls row")

# 10. Clone ButtonKt -> JedflixLiveButtonKt in smali_classes5 and patch its
#     onKeyEvent lambda so DPAD_CENTER / ENTER / NUMPAD_ENTER are consumed on
#     both key-down and key-up instead of bubbling to PlayerPage pause/play.
composables = stage / "smali_classes5/com/stremio/tv/composables"
if not composables.is_dir():
    raise SystemExit(f"Missing composables smali directory: {composables}")

expected_button_files = sorted([
    "ButtonKt.smali",
    "ButtonKt$$ExternalSyntheticLambda0.smali",
    "ButtonKt$Button$1$1.smali",
    "ButtonKt$Button$1$1$$ExternalSyntheticLambda0.smali",
    "ButtonKt$Button$onKeyEvent$1$1.smali",
])
button_sources = sorted(composables.glob("ButtonKt*.smali"))
if [p.name for p in button_sources] != expected_button_files:
    raise SystemExit(
        f"Upstream ButtonKt smali set changed in {composables}: "
        f"expected {expected_button_files}, found {[p.name for p in button_sources]}")

for src_path in button_sources:
    dst_name = src_path.name.replace("ButtonKt", "JedflixLiveButtonKt", 1)
    dst_path = composables / dst_name
    dst_path.write_text(src_path.read_text().replace("ButtonKt", "JedflixLiveButtonKt"))

cloned_key_event = composables / "JedflixLiveButtonKt$Button$onKeyEvent$1$1.smali"
if not cloned_key_event.exists():
    raise SystemExit(f"Missing cloned key event smali: {cloned_key_event}")

key_event_before = """.method public final invoke-ZmokQxo(Landroid/view/KeyEvent;)Ljava/lang/Boolean;
    .locals 4

    const-string v0, "it"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    .line 88
    invoke-static {p1}, Landroidx/compose/ui/input/key/KeyEvent_androidKt;->getType-ZmokQxo(Landroid/view/KeyEvent;)I

    move-result v0

    sget-object v1, Landroidx/compose/ui/input/key/KeyEventType;->Companion:Landroidx/compose/ui/input/key/KeyEventType$Companion;

    invoke-virtual {v1}, Landroidx/compose/ui/input/key/KeyEventType$Companion;->getKeyDown-CS__XNY()I

    move-result v1

    invoke-static {v0, v1}, Landroidx/compose/ui/input/key/KeyEventType;->equals-impl0(II)Z

    move-result v0

    if-eqz v0, :cond_1

    .line 89
    invoke-static {p1}, Landroidx/compose/ui/input/key/KeyEvent_androidKt;->getKey-ZmokQxo(Landroid/view/KeyEvent;)J

    move-result-wide v0

    .line 90
    sget-object p1, Landroidx/compose/ui/input/key/Key;->Companion:Landroidx/compose/ui/input/key/Key$Companion;

    invoke-virtual {p1}, Landroidx/compose/ui/input/key/Key$Companion;->getDirectionCenter-EK5gGoQ()J

    move-result-wide v2

    invoke-static {v0, v1, v2, v3}, Landroidx/compose/ui/input/key/Key;->equals-impl0(JJ)Z

    move-result p1

    if-nez p1, :cond_0

    sget-object p1, Landroidx/compose/ui/input/key/Key;->Companion:Landroidx/compose/ui/input/key/Key$Companion;

    invoke-virtual {p1}, Landroidx/compose/ui/input/key/Key$Companion;->getEnter-EK5gGoQ()J

    move-result-wide v2

    invoke-static {v0, v1, v2, v3}, Landroidx/compose/ui/input/key/Key;->equals-impl0(JJ)Z

    move-result p1

    if-eqz p1, :cond_1

    :cond_0
    iget-object p1, p0, Lcom/stremio/tv/composables/JedflixLiveButtonKt$Button$onKeyEvent$1$1;->$onPress:Lkotlin/jvm/functions/Function0;

    invoke-interface {p1}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;

    :cond_1
    const/4 p1, 0x0

    .line 94
    invoke-static {p1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p1

    return-object p1
.end method"""

key_event_after = """.method public final invoke-ZmokQxo(Landroid/view/KeyEvent;)Ljava/lang/Boolean;
    .locals 2

    const-string v0, "it"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {p1}, Landroid/view/KeyEvent;->getKeyCode()I

    move-result v0

    const/16 v1, 0x17

    if-eq v0, v1, :cond_key_matched

    const/16 v1, 0x42

    if-eq v0, v1, :cond_key_matched

    const/16 v1, 0xa0

    if-eq v0, v1, :cond_key_matched

    goto :cond_not_handled

    :cond_key_matched
    invoke-virtual {p1}, Landroid/view/KeyEvent;->getAction()I

    move-result v0

    if-nez v0, :cond_check_up

    invoke-virtual {p1}, Landroid/view/KeyEvent;->getRepeatCount()I

    move-result p1

    if-nez p1, :cond_consumed

    iget-object p1, p0, Lcom/stremio/tv/composables/JedflixLiveButtonKt$Button$onKeyEvent$1$1;->$onPress:Lkotlin/jvm/functions/Function0;

    invoke-interface {p1}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;

    goto :cond_consumed

    :cond_check_up
    const/4 v1, 0x1

    if-ne v0, v1, :cond_not_handled

    :cond_consumed
    sget-object p1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;

    return-object p1

    :cond_not_handled
    sget-object p1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;

    return-object p1
.end method"""

key_event_source = cloned_key_event.read_text()
key_event_count = key_event_source.count(key_event_before)
if key_event_count != 1:
    raise SystemExit(
        f"Upstream invoke-ZmokQxo seam changed in {cloned_key_event}: "
        f"expected 1 match, found {key_event_count}")
cloned_key_event.write_text(key_event_source.replace(key_event_before, key_event_after, 1))

# Result hooks. These make a partially applied patch fail before packaging.
assert_result(MANAGER, "Lcom/jedflix/tv/LiveTvPlayback;->adjustInitialPosition(Ljava/lang/Object;J)J",
              "createPlayer offset")
assert_result(MANAGER, "Lcom/jedflix/tv/LiveTvPlayback;->attach(Ljava/lang/Object;Ljava/lang/Object;)V",
              "manager attach")
assert_result(MANAGER, "Lcom/jedflix/tv/LiveTvPlayback;->detach(Ljava/lang/Object;)V",
              "manager detach")
assert_result(LISTENER, "Lcom/jedflix/tv/LiveTvPlayback;->onEnded(Ljava/lang/Object;)Z",
              "ended suppression")
assert_result(PAGE, "Lcom/jedflix/tv/LiveTvPlayback;->onUserPause(Ljava/lang/Object;)V",
              "pause hook")
if source_of(PAGE).read_text().count("LiveTvPlayback;->onUserSeek(Ljava/lang/Object;)V") != 3:
    raise SystemExit("Expected three PlayerPage seek hooks")
assert_result(MEDIA, "Lcom/jedflix/tv/LiveTvPlayback;->onUserPause(Ljava/lang/Object;)V",
              "media pause hook")
assert_result(MEDIA, "Lcom/jedflix/tv/LiveTvPlayback;->onUserSeek(Ljava/lang/Object;)V",
              "media seek hook")
assert_result(FRAGMENT, "Lcom/jedflix/tv/LiveTvPlayback;->bindPlayerFragment(Ljava/lang/Object;)V",
              "fragment binding")
assert_result(FRAGMENT, "Lcom/jedflix/tv/LiveTvPlayback;->unbindPlayerFragment(Ljava/lang/Object;)V",
              "fragment teardown")
assert_result(OVERLAY, "Lcom/jedflix/tv/LiveTvControls;->Content(Landroidx/compose/runtime/Composer;)V",
              "Go Live control")
if "Landroid/view/KeyEvent;->getRepeatCount()I" not in cloned_key_event.read_text():
    raise SystemExit("Live TV button key-event hook missing in JedflixLiveButtonKt$Button$onKeyEvent$1$1.smali")

print("Applied live TV playback hooks: player lifecycle, user pause/seek, stream-only launch, Go Live")
