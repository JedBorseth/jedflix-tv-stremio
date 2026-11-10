/** Compile-only upstream API shape. Never packaged in the APK. */
package com.stremio.tv.composables;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** Shape of the upstream Compose Button used by the player overlay. */
public final class ButtonKt {
    public static void Button(Modifier modifier, Integer icon, String label, boolean disabled,
            boolean small, boolean clear, Function0<Unit> onPress, Composer composer,
            int changed, int defaults) {}
}
