/** Compile-only upstream API shape. Never packaged in the APK. */
package androidx.compose.runtime;
public interface Composer {
 Companion Companion = null;
 public static final class Companion { public Object getEmpty() { return null; } }
 void startReplaceGroup(int key); void endReplaceGroup();
 Object rememberedValue(); void updateRememberedValue(Object value);
 <T> T consume(CompositionLocal<T> local);
}
