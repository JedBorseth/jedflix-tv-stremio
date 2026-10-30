/** Compile-only upstream API shape. Never packaged in the APK. */
package androidx.compose.runtime; public interface MutableState<T> extends State<T> { void setValue(T value); }
