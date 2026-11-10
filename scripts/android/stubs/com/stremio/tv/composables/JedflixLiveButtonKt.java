/** Compile-only cloned upstream API shape. Packaged via live-tv-playback-patch.py. */
package com.stremio.tv.composables;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** Cloned upstream Compose Button with key-consuming D-pad Center/Enter handling for Live TV. */
public final class JedflixLiveButtonKt {
    public static void Button(Modifier modifier, Integer icon, String label, boolean disabled,
            boolean small, boolean clear, Function0<Unit> onPress, Composer composer,
            int changed, int defaults) {}
}
