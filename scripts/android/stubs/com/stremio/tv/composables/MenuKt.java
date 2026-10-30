/** Compile-only upstream API shape. Never packaged in the APK. */
package com.stremio.tv.composables;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
public final class MenuKt {
 public static <T> void MenuList(Modifier modifier, List<MenuItem<T>> items,
     Function1<T, Unit> onClick, Composer composer, int changed, int defaults) {}
}
