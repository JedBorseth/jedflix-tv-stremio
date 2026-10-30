package com.jedflix.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.compose.runtime.Composer;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt;
import com.stremio.tv.composables.MenuItem;
import com.stremio.tv.composables.MenuKt;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/** Owns one labelled FFmpeg audio filter for the lifetime of the current mpv player. */
public final class NightAudio {
    public static final String LABEL = "jedflix_night";
    // Linear amplitude options: threshold -22 dB, makeup +6 dB, ceiling -1 dB.
    // Link channels so compression preserves the surround image. The limiter's
    // automatic output gain is deliberately disabled, preserving its ceiling.
    public static final String GRAPH = "acompressor=threshold=0.0794328:ratio=4:attack=10:release=300"
        + ":knee=2.82843:makeup=1.995262:link=maximum:detection=rms,"
        + "alimiter=limit=0.891251:attack=5:release=100:level=false:latency=true";
    private static final String FILTER = "@" + LABEL + ":lavfi=[" + GRAPH + "]";
    private static final String TAG = "JedflixNightAudio";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final MutableState<Integer> revision = SnapshotStateKt.mutableStateOf(
        0, SnapshotStateKt.<Integer>structuralEqualityPolicy());
    private static SharedPreferences preferences;
    private static WeakReference<Object> manager = new WeakReference<Object>(null);
    private static Context app;
    private static Object mpv;
    private static boolean enabled, applied, passthroughSaved, seekSaved;
    private static String savedPassthrough, savedHrSeek, status = "Off", lastError;
    private static int missingChecks;
    private static final Object NIGHT_ITEM = new Object();
    private static long generation;
    private static Object logObserver;

    private NightAudio() {}

    public static synchronized void init(Context context) {
        if (preferences != null) return;
        app = context.getApplicationContext();
        preferences = app.getSharedPreferences("jedflix_night_audio", Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
        setStatus(enabled ? "Starting" : "Off", false);
    }

    public static synchronized void bindManager(Object value, Context context) {
        init(context);
        manager = new WeakReference<Object>(value);
    }

    public static synchronized void unbindManager(Object value) {
        if (manager.get() == value) manager.clear();
    }

    /** Preserve the selected player's state, including the name displayed in UI. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized Object resolvePlayerType(Object requested) {
        if (!enabled || !(requested instanceof Enum)) return requested;
        Enum type = (Enum) requested;
        if (type.name().equals("EXTERNAL")) return requested;
        return Enum.valueOf(type.getDeclaringClass(), "MPV");
    }

    /** An explicit engine change is also an explicit exit from Night mode. */
    public static synchronized void onUserPlayerChange(Object requested) {
        if (requested instanceof Enum && !((Enum<?>)requested).name().equals("MPV") && enabled)
            setEnabled(false);
    }

    public static synchronized void attach(Object value) {
        if (mpv == value) return;
        generation++;
        main.removeCallbacks(check);
        stopObserving();
        mpv = value;
        applied = false;
        passthroughSaved = false;
        seekSaved = false;
        savedPassthrough = null;
        savedHrSeek = null;
        missingChecks = 0;
        if (enabled) apply();
        else setStatus(lastError == null ? "Off" : "Unavailable", false);
    }

    public static synchronized void detach(Object value) {
        if (mpv != value) return;
        generation++;
        main.removeCallbacks(check);
        stopObserving();
        mpv = null;
        passthroughSaved = false;
        seekSaved = false;
        savedPassthrough = null;
        savedHrSeek = null;
        setStatus(enabled ? "Starting" : lastError == null ? "Off" : "Unavailable", false);
    }

    public static synchronized boolean isEnabled() { return enabled; }
    public static synchronized boolean isApplied() { return applied; }
    public static synchronized String getStatus() { return status; }

    public static synchronized void setEnabled(boolean value) {
        generation++;
        stopObserving();
        lastError = null;
        enabled = value;
        persist();
        if (!value) {
            main.removeCallbacks(check);
            try { remove(); setStatus("Off", false); }
            catch (Exception failure) { fail(failure); }
            return;
        }
        if (mpv != null) {
            apply();
            return;
        }
        setStatus("Starting", false);
        try {
            Object playback = manager.get();
            if (playback == null) return; // Next player initialization applies it.
            Object player = call(playback, "getPlayer");
            // Save the actual current time before stock playerChanged releases
            // the old engine, so choosing Night mode resumes the same scene.
            if (player != null) {
                Object position = call(player, "getTime");
                Field field = playback.getClass().getDeclaredField("playerViewModel");
                field.setAccessible(true);
                Object model = field.get(playback);
                model.getClass().getMethod("updateInitialPosition", Long.class).invoke(model, position);
            }
            Class<?> type = Class.forName("com.stremio.common.viewmodels.state.PlayerType");
            Object mpvType = type.getField("MPV").get(null);
            playback.getClass().getMethod("playerChanged", type).invoke(playback, mpvType);
        } catch (Exception failure) { fail(failure); }
    }

    private static void persist() {
        if (preferences != null) preferences.edit().putBoolean("enabled", enabled).apply();
    }

    private static void apply() {
        try {
            observeFailures();
            if (!seekSaved) {
                savedHrSeek = property("hr-seek");
                if (savedHrSeek == null) savedHrSeek = property("options/hr-seek");
                if (savedHrSeek == null)
                    throw new IllegalStateException("Cannot read the precise seeking setting");
                seekSaved = true;
            }
            // The stock mpv constructor sets hr-seek=no, which makes the
            // initial time-pos resume jump back to a keyframe when changing
            // engines. This hook runs before loadfile, so the saved scene time
            // is decoded precisely. Restore the user's original option on Off.
            setProperty("hr-seek", "yes");
            if (!passthroughSaved) {
                savedPassthrough = property("audio-spdif");
                if (savedPassthrough == null) savedPassthrough = property("options/audio-spdif");
                if (savedPassthrough == null)
                    throw new IllegalStateException("Cannot read the audio passthrough setting");
                passthroughSaved = true;
            }
            String previousFormat = property("audio-out-params/format");
            setProperty("audio-spdif", "");
            // mpv may retain an already opened passthrough decoder. Reselecting
            // only the audio track rebuilds it as PCM without seeking the video.
            if (isPassthrough(previousFormat)) reselectAudio();
            String filters = property("af");
            if (filters == null || !filters.contains(LABEL)) command("af", "add", FILTER);
            missingChecks = 0;
            setStatus("Starting", false);
            main.removeCallbacks(check);
            main.post(check);
        } catch (Exception failure) { fail(failure); }
    }

    private static void remove() throws Exception {
        if (mpv == null) return;
        String filters = property("af");
        if (filters != null && filters.contains(LABEL)) command("af", "remove", "@" + LABEL);
        if (passthroughSaved) {
            setProperty("audio-spdif", savedPassthrough);
            // Restore the original decoder choice when returning to passthrough.
            if (!savedPassthrough.isEmpty()) reselectAudio();
            passthroughSaved = false;
        }
        if (seekSaved) {
            setProperty("hr-seek", savedHrSeek);
            seekSaved = false;
        }
        String after = property("af");
        if (after != null && after.contains(LABEL))
            throw new IllegalStateException("The Night mode filter could not be removed");
    }

    private static void reselectAudio() throws Exception {
        String track = property("aid");
        if (track != null && !track.equals("no")) {
            setProperty("aid", "no");
            setProperty("aid", track);
        }
    }

    private static boolean isPassthrough(String format) {
        return format != null && format.toLowerCase(Locale.US).contains("spdif");
    }

    /**
     * MPV's af property describes configured filters. A failed lavfi filter can
     * remain in that list while its wrapper silently passes original PCM.
     * Observe the native filter errors as well as polling the live filter below.
     */
    private static void observeFailures() throws Exception {
        stopObserving();
        final Object owner = mpv;
        final long epoch = generation;
        Class<?> observerType = Class.forName("dev.jdtech.mpv.MPVLib$LogObserver");
        logObserver = Proxy.newProxyInstance(observerType.getClassLoader(), new Class<?>[] {observerType},
            new InvocationHandler() {
                private final AtomicBoolean reported = new AtomicBoolean();
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    // MPVLib invokes this while holding its logObservers monitor.
                    // Never take NightAudio's lock or call back into MPV here:
                    // detach/removal can be holding that lock on the UI thread.
                    String name = method.getName();
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    if (name.equals("toString")) return "Jedflix Night mode log observer";
                    if (name.equals("logMessage") && args != null && args.length == 3) {
                        final String prefix = String.valueOf(args[0]);
                        final String message = String.valueOf(args[2]);
                        if (isFilterFailure(prefix, ((Number)args[1]).intValue(), message)
                                && reported.compareAndSet(false, true)) {
                            main.post(new Runnable() { @Override public void run() {
                                synchronized (NightAudio.class) {
                                    if (mpv == owner && generation == epoch && enabled)
                                        fail(new IllegalStateException(prefix + ": " + message.trim()));
                                }
                            }});
                        }
                    }
                    return null;
                }
            });
        owner.getClass().getMethod("addLogObserver", observerType).invoke(owner, logObserver);
    }

    private static void stopObserving() {
        Object observer = logObserver;
        logObserver = null;
        if (observer == null || mpv == null) return;
        try {
            Class<?> observerType = Class.forName("dev.jdtech.mpv.MPVLib$LogObserver");
            mpv.getClass().getMethod("removeLogObserver", observerType).invoke(mpv, observer);
        } catch (Exception failure) { Log.w(TAG, "Could not remove the audio log observer", failure); }
    }

    private static boolean isFilterFailure(String prefix, int level, String message) {
        // libmpv log levels: fatal=10, error=20, warn=30. Network/decoder
        // warnings and failures in an unrelated video filter are not ours.
        if (level < 1 || level > 20) return false;
        String source = prefix.toLowerCase(Locale.US);
        String text = message.toLowerCase(Locale.US);
        if (source.contains(LABEL) || text.contains(LABEL)) return true;
        if (source.equals("vf") || source.startsWith("vf/") || source.contains("/vf/")) return false;
        boolean audio = source.equals("af") || source.startsWith("af/") || source.contains("/af/");
        boolean lavfi = source.equals("lavfi") || (audio && source.contains("lavfi"));
        boolean preset = text.contains("acompressor") || text.contains("alimiter");
        // A labelled failure of another user filter must not disable Night mode.
        if (text.contains("disabling filter")) return false;
        if (!audio && !lavfi && !preset) return false;
        return text.contains("failed to") || text.contains("could not") || text.contains("cannot")
            || text.contains("error configuring") || text.contains("error parsing")
            || text.contains("error initializing") || text.contains("error on filtering")
            || text.contains("filter graph") || text.contains("filtergraph")
            || text.contains("no such filter") || text.contains("not found");
    }

    private static final Runnable check = new Runnable() {
        @Override public void run() {
            synchronized (NightAudio.class) {
                if (mpv == null || !enabled) return;
                try {
                    String filters = property("af");
                    String format = property("audio-out-params/format");
                    // GET_META reaches the actual labelled lavfi instance. It
                    // succeeds (including count=0) only after graph initialization;
                    // unlike the af option it becomes unavailable after a failed
                    // wrapper resets that instance and bypasses its processing.
                    String metadataCount = property("af-metadata/" + LABEL + "/list/count");
                    boolean present = filters != null && filters.contains(LABEL)
                        && !filters.contains("!@" + LABEL)
                        && metadataCount != null && metadataCount.matches("[0-9]+");
                    if (format != null && !format.isEmpty()) {
                        if (!present || isPassthrough(format)) {
                            // Paused playback can retain old output parameters
                            // without feeding the new filter any frames. Cache
                            // stalls and seeks likewise postpone graph setup.
                            // Native filter errors still fail immediately via
                            // the observer, even while playback is suspended.
                            boolean suspended = isTrue(property("pause"))
                                || isTrue(property("paused-for-cache"))
                                || isTrue(property("seeking"));
                            if (suspended) missingChecks = 0;
                            else if (++missingChecks >= 3)
                                throw new IllegalStateException("Audio processing did not initialize: "
                                    + "format=" + format + ", af=" + filters
                                    + ", live filter metadata=" + metadataCount);
                            setStatus(suspended ? "Waiting for playback" : "Starting", false);
                        } else {
                            missingChecks = 0;
                            setStatus("On", true);
                        }
                    } else {
                        missingChecks = 0;
                        setStatus("Waiting for audio", false);
                    }
                } catch (Exception failure) { fail(failure); return; }
                main.postDelayed(this, 750);
            }
        }
    };

    private static boolean isTrue(String value) {
        return "yes".equals(value) || "true".equals(value) || "1".equals(value);
    }

    private static void fail(Exception failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
        Log.e(TAG, "Night mode unavailable; restoring original audio", cause);
        generation++;
        enabled = false;
        persist();
        main.removeCallbacks(check);
        stopObserving();
        try { remove(); } catch (Exception cleanup) { Log.e(TAG, "Audio restoration failed", cleanup); }
        lastError = cause.getMessage();
        setStatus("Unavailable", false);
        if (app != null) main.post(new Runnable() { @Override public void run() {
            Toast.makeText(app, "Night mode couldn't start. Try Off, then On again.", Toast.LENGTH_LONG).show();
        }});
    }

    private static void setStatus(String value, boolean active) {
        if (status.equals(value) && applied == active) return;
        status = value;
        applied = active;
        revision.setValue(revision.getValue() + 1);
        Log.i(TAG, "enabled=" + enabled + " applied=" + applied + " status=" + value);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
    private static String property(String name) throws Exception {
        return (String)mpv.getClass().getMethod("getPropertyString", String.class).invoke(mpv, name);
    }
    private static void setProperty(String name, String value) throws Exception {
        mpv.getClass().getMethod("setPropertyString", String.class, String.class).invoke(mpv, name, value);
    }
    private static void command(String... args) throws Exception {
        mpv.getClass().getMethod("command", String[].class).invoke(mpv, (Object)args);
    }

    /** A standard focusable player Audio menu row, using the stock menu layout. */
    @SuppressWarnings("unchecked")
    public static void Content(List<?> originalItems, final Function1<Object, Unit> originalClick,
            Composer composer, final Function0<Unit> onClose) {
        composer.startReplaceGroup(0x4a4e4947);
        revision.getValue();
        final Context context = composer.consume(AndroidCompositionLocals_androidKt.getLocalContext());
        init(context);
        MenuItem<Object> item = new MenuItem<Object>("jedflix_night", "Night mode", NIGHT_ITEM,
            null, getStatus(), false);
        List<MenuItem<Object>> items = new ArrayList<MenuItem<Object>>();
        items.add(item);
        items.addAll((List<MenuItem<Object>>) originalItems);
        MenuKt.MenuList(null, items, new Function1<Object, Unit>() {
            @Override public Unit invoke(Object value) {
                if (value != NIGHT_ITEM) return originalClick.invoke(value);
                onClose.invoke();
                main.post(new Runnable() { @Override public void run() { show(context); }});
                return Unit.INSTANCE;
            }
        }, composer, 0, 1);
        composer.endReplaceGroup();
    }

    private static void show(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity))
            context = ((ContextWrapper)context).getBaseContext();
        if (!(context instanceof Activity) || ((Activity)context).isFinishing()) return;
        final Context dialogContext = context;
        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle("Night mode")
            .setSingleChoiceItems(new String[] {
                "Off — Original audio", "On — Quieter loud scenes, clearer quiet dialogue"
            }, isEnabled() ? 1 : 0, (selectedDialog, which) -> {
                setEnabled(which == 1);
                selectedDialog.dismiss();
                Toast.makeText(dialogContext, which == 1
                    ? "Night mode: " + getStatus() : "Night mode off", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int)Math.min(context.getResources().getDisplayMetrics().widthPixels * .85f,
                context.getResources().getDisplayMetrics().density * 760);
            dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
