package com.jedflix.tv;

import androidx.compose.runtime.Composer;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import com.stremio.tv.composables.JedflixLiveButtonKt;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * The Go Live text action inside the stock player control row.
 *
 * <p>It uses a cloned copy of the app's own Compose {@code Button} so focus,
 * typography and D-pad behaviour match the rest of the overlay while consuming
 * Center/Enter key events so they do not bubble into the player pause toggle.
 * The button only exists while a live session is active: it reads LIVE while
 * following the broadcast and shows a focusable Go Live action while the
 * session is paused or delayed.</p>
 */
public final class LiveTvControls {
    private static final int GROUP = 0x4c54566a;
    private static final MutableState<Integer> revision = SnapshotStateKt.mutableStateOf(
            0, SnapshotStateKt.<Integer>structuralEqualityPolicy());

    private static final Function0<Unit> GO_LIVE = new Function0<Unit>() {
        @Override public Unit invoke() {
            if (LiveTvPlayback.controlActionable()) {
                LiveTvPlayback.goLive();
            }
            return Unit.INSTANCE;
        }
    };

    private LiveTvControls() {}

    static int revisionValue() { return revision.getValue(); }

    static void bumpRevision() {
        revision.setValue(revision.getValue() + 1);
    }

    /**
     * Emitted from the stock player Overlay Row. Must be called on the main
     * thread inside an existing composition; it starts and ends its own group.
     */
    public static void Content(Composer composer) {
        composer.startReplaceGroup(GROUP);
        revision.getValue();
        if (LiveTvPlayback.isActive()) {
            String label = LiveTvPlayback.controlLabel();
            boolean actionable = LiveTvPlayback.controlActionable();
            if (label.length() > 0) {
                // Upstream signature:
                // Button(Modifier?, Integer?, String?, Z, Z, Z, Function0<Unit>, Composer, II)
                // The all-changed mask makes the label update whenever this group
                // is invalidated; the modifier default bit keeps the stock spacing.
                JedflixLiveButtonKt.Button(null, null, label, !actionable, true, false, GO_LIVE,
                        composer, 0x7fffffff, 0x1);
            }
        }
        composer.endReplaceGroup();
    }
}
