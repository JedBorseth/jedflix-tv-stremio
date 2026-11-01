package com.jedflix.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-device discovery coordinator. Existing Stremio views render every row. */
public final class HomeDiscovery {
    private static final String TAG = "JedflixDiscovery";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Map<Object, Boolean> MODELS = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<IdentityPreview, Boolean> LOCAL_PREVIEWS = new HashMap<IdentityPreview, Boolean>();
    private static final ReferenceQueue<Object> PREVIEW_QUEUE = new ReferenceQueue<Object>();
    private static volatile Context context;
    private static volatile String activeProfile = "";
    private static volatile Snapshot current;
    private static volatile Snapshot pending;
    private static volatile String completedKey = "";
    private static volatile long lastAttempt;
    private static volatile boolean refreshAgain;
    private static WeakReference<Object> foreground = new WeakReference<Object>(null);
    private static Handler main;
    private static TmdbCatalogClient client;
    private static JedPicksClient picksClient;
    private static long lastPicksPoll;
    private static boolean ticking;
    private static WeakReference<View> observedRoot = new WeakReference<View>(null);
    private static ViewTreeObserver.OnGlobalFocusChangeListener focusListener;

    private HomeDiscovery() {}

    public static synchronized Object registerPreview(Object preview) {
        IdentityPreview expired;
        while ((expired = (IdentityPreview) PREVIEW_QUEUE.poll()) != null) LOCAL_PREVIEWS.remove(expired);
        LOCAL_PREVIEWS.put(new IdentityPreview(preview, PREVIEW_QUEUE), Boolean.TRUE);
        return preview;
    }

    public static synchronized boolean hasLocalPreview(Object preview) {
        return preview != null && LOCAL_PREVIEWS.containsKey(new IdentityPreview(preview, null));
    }

    public static Object mergeFullPreview(Object fresh, Object previous) {
        if (!hasLocalPreview(previous)) return fresh;
        try { return TmdbCatalogMapper.preserveArtwork(fresh, previous); }
        catch (Exception ignored) { return previous; }
    }

    public static synchronized void init(Context supplied) {
        if (context != null || supplied == null) return;
        context = supplied.getApplicationContext();
        main = new Handler(Looper.getMainLooper());
        client = new TmdbCatalogClient(context, JedflixTmdbConfig.API_KEY);
        picksClient = new JedPicksClient(context, client);
        DiscoverySignals.setListener(new Runnable() {
            @Override public void run() {
                main.post(new Runnable() {
                    @Override public void run() {
                        syncProfile();
                        completedKey = "";
                        lastAttempt = 0;
                        requestRefresh();
                    }
                });
            }
        });
    }

    public static void attach(Object viewModel) {
        if (viewModel == null) return;
        MODELS.put(viewModel, Boolean.TRUE);
        syncProfile();
        requestRefresh();
    }

    private static void syncProfile() {
        JSONObject signals = DiscoverySignals.snapshot();
        String profile = signals.optString("profile", "local");
        if (!profile.equals(activeProfile)) {
            boolean clearPrevious = current != null;
            activeProfile = profile;
            current = null;
            pending = null;
            completedKey = "";
            lastAttempt = 0;
            if (clearPrevious) publishToModels(Collections.emptyList());
        }
    }

    /** Called from the existing collector: all native Continue Watching remains intact. */
    public static Object onState(Object state) {
        Snapshot snapshot = current;
        if (state == null || snapshot == null || snapshot.catalogs.isEmpty()
                || !snapshot.profile.equals(activeProfile)) return state;
        try {
            Object watching = state.getClass().getMethod("getContinueWatching").invoke(state);
            return state.getClass().getMethod("copy", List.class, List.class)
                .invoke(state, watching, snapshot.catalogs);
        } catch (Exception error) {
            Log.e(TAG, "Cannot merge native Home rows", error);
            return state;
        }
    }

    public static void onResume(final Object fragment) {
        if (fragment == null) return;
        try {
            if (context == null) init((Context) fragment.getClass().getMethod("requireContext").invoke(fragment));
            syncProfile();
            foreground = new WeakReference<Object>(fragment);
            View root = (View) fragment.getClass().getMethod("getView").invoke(fragment);
            removeFocusListener();
            if (root != null) {
                final WeakReference<View> view = new WeakReference<View>(root);
                focusListener = new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override public void onGlobalFocusChanged(View previous, View next) {
                        View held = view.get();
                        if (held == null || !held.isAttachedToWindow()) return;
                        if (pending != null && !hasPosterFocus(held)) applyPending();
                    }
                };
                observedRoot = view;
                root.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
            }
            applyPending();
            requestRefresh();
            startTick();
        } catch (Exception error) { Log.e(TAG, "Cannot resume discovery", error); }
    }

    public static void onPause(Object fragment) {
        if (foreground.get() == fragment) {
            foreground = new WeakReference<Object>(null);
            removeFocusListener();
        }
    }

    private static void removeFocusListener() {
        View root = observedRoot.get();
        if (root != null && focusListener != null && root.getViewTreeObserver().isAlive())
            root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        observedRoot = new WeakReference<View>(null);
        focusListener = null;
    }

    public static boolean isSyntheticRow(Object row) {
        if (row == null || !row.getClass().getName().endsWith("MetaRow$CatalogRow")) return false;
        try {
            Object catalog = row.getClass().getMethod("getCatalog").invoke(row);
            return isSyntheticCatalog(catalog);
        } catch (Exception ignored) { return false; }
    }

    /** Native constructor defaults: hide the addon-only See All card for local rows. */
    public static int rowDefaults(Object catalog) {
        return isSyntheticCatalog(catalog) ? 6 : 14;
    }

    private static boolean isSyntheticCatalog(Object catalog) {
        if (catalog == null) return false;
        try {
            List<?> pages = (List<?>) catalog.getClass().getMethod("getPages").invoke(catalog);
            if (pages == null || pages.isEmpty()) return false;
            Object page = pages.get(0);
            Object id = page.getClass().getMethod("getCatalogId").invoke(page);
            return id instanceof String && ((String) id).startsWith("jedflix-");
        } catch (Exception ignored) { return false; }
    }

    private static void requestRefresh() {
        if (context == null) return;
        if (!BUSY.compareAndSet(false, true)) { refreshAgain = true; return; }
        WORK.execute(new Runnable() {
            @Override public void run() {
                try { refresh(); }
                catch (Exception error) { Log.e(TAG, "Discovery refresh kept the previous Home", error); }
                finally {
                    BUSY.set(false);
                    if (refreshAgain) { refreshAgain = false; requestRefresh(); }
                }
            }
        });
    }

    private static void refresh() throws Exception {
        final long now = System.currentTimeMillis();
        final JSONObject signals = DiscoverySignals.snapshot();
        final String profile = signals.optString("profile", "local");
        if (!profile.equals(activeProfile)) return;
        final TimeZone zone = TimeZone.getDefault();
        final List<DiscoveryRules.Row> plan = DiscoveryRules.rows(now, zone, signals);
        final String key = contextKey(now, zone, signals);
        boolean discoveryDue = !key.equals(completedKey);
        boolean picksDue = now - lastPicksPoll >= 300000L;
        if (!discoveryDue && !picksDue) return;
        if (discoveryDue && now - lastAttempt < 30000L && current != null
                && profile.equals(current.profile)) return;
        JSONArray raw = readDisk(profile);
        JSONArray usable = forPlan(raw, plan);
        if (usable.length() > 0) publish(profile, DiscoveryFeed.assemble(usable, signals));

        JSONObject picks = findRow(usable, "home.jeds-picks");
        if (picksDue || picks == null) {
            lastPicksPoll = now;
            try { picks = picksClient.load(); }
            catch (Exception ignored) { Log.w(TAG, "Jed's Picks retained its cached selection"); }
        }
        JSONArray refreshed = new JSONArray();
        if (picks != null) refreshed.put(picks);
        usable = mergeUnrefreshed(refreshed, usable, plan);
        if (!discoveryDue) {
            writeDisk(profile, usable);
            publish(profile, DiscoveryFeed.assemble(usable, signals));
            return;
        }

        lastAttempt = now;
        int succeeded = 0;
        boolean allFresh = true;
        long started = System.currentTimeMillis();
        for (DiscoveryRules.Row row : plan) {
            // An account switch invalidates in-flight recommendation work.
            if (!profile.equals(activeProfile)) return;
            JSONArray metas;
            try { metas = client.load(row); }
            catch (Exception error) {
                metas = null;
                Log.w(TAG, "A discovery row retained its cached selection");
            }
            if (metas == null || metas.length() == 0) {
                allFresh = false;
                metas = previousRow(raw, row.id);
            }
            if (metas.length() == 0) continue;
            refreshed.put(new JSONObject().put("id", row.id).put("title", row.title)
                .put("type", row.type).put("metas", metas));
            succeeded++;
            // Cached Home appears first; cold Home fills in from the top down.
            if (succeeded == 1 || succeeded % 3 == 0) {
                publish(profile, DiscoveryFeed.assemble(
                    mergeUnrefreshed(refreshed, usable, plan), signals));
            }
        }
        JSONArray complete = mergeUnrefreshed(refreshed, usable, plan);
        if (complete.length() > 0 && profile.equals(activeProfile)) {
            writeDisk(profile, complete);
            publish(profile, DiscoveryFeed.assemble(complete, signals));
            if (allFresh) completedKey = key;
            Log.i(TAG, "TMDB Home ready: " + succeeded + " discovery rows in "
                + (System.currentTimeMillis() - started) + "ms; requests stayed on device");
        }
    }

    private static JSONArray forPlan(JSONArray raw, List<DiscoveryRules.Row> plan) throws Exception {
        JSONArray result = new JSONArray();
        JSONObject picks = findRow(raw, "home.jeds-picks");
        if (picks != null) result.put(picks);
        for (DiscoveryRules.Row row : plan) {
            JSONArray metas = previousRow(raw, row.id);
            if (metas.length() > 0) result.put(new JSONObject().put("id", row.id)
                .put("title", row.title).put("type", row.type).put("metas", metas));
        }
        return result;
    }

    private static JSONArray mergeUnrefreshed(JSONArray fresh, JSONArray cached, List<DiscoveryRules.Row> plan) throws Exception {
        Map<String, JSONObject> rows = new HashMap<String, JSONObject>();
        for (int i = 0; i < cached.length(); i++) rows.put(cached.getJSONObject(i).optString("id"), cached.getJSONObject(i));
        for (int i = 0; i < fresh.length(); i++) rows.put(fresh.getJSONObject(i).optString("id"), fresh.getJSONObject(i));
        JSONArray result = new JSONArray();
        if (rows.containsKey("home.jeds-picks")) result.put(rows.get("home.jeds-picks"));
        for (DiscoveryRules.Row row : plan) if (rows.containsKey(row.id)) result.put(rows.get(row.id));
        return result;
    }

    private static JSONObject findRow(JSONArray rows, String id) {
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) return row;
        }
        return null;
    }

    private static JSONArray previousRow(JSONArray rows, String id) {
        if (rows == null) return new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) {
                JSONArray metas = row.optJSONArray("metas");
                if (metas != null) return metas;
            }
        }
        return new JSONArray();
    }

    private static void publish(final String profile, final JSONArray rows) throws Exception {
        if (rows.length() == 0) return;
        final Snapshot next = new Snapshot(profile, rows, TmdbCatalogMapper.catalogs(rows));
        if (next.catalogs.isEmpty()) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (!profile.equals(activeProfile)) return;
                Snapshot before = current;
                if (before != null && before.rows.toString().equals(rows.toString())) return;
                View root = currentRoot();
                if (before != null && root != null && hasPosterFocus(root)
                        && !DiscoveryFeed.isAppendOnly(before.rows, next.rows)) {
                    pending = next;
                    return;
                }
                apply(next);
            }
        });
    }

    private static void applyPending() {
        Snapshot next = pending;
        View root = currentRoot();
        if (next != null && next.profile.equals(activeProfile)
                && (root == null || !hasPosterFocus(root) || current == null
                    || DiscoveryFeed.isAppendOnly(current.rows, next.rows))) {
            pending = null;
            apply(next);
        }
    }

    private static void apply(Snapshot next) {
        if (current == null) Log.i(TAG, "Native Home visible: " + next.catalogs.size() + " rows");
        pending = null;
        current = next;
        publishToModels(next.catalogs);
    }

    private static void publishToModels(List<Object> catalogs) {
        List<Object> models;
        synchronized (MODELS) { models = new ArrayList<Object>(MODELS.keySet()); }
        for (Object vm : models) {
            if (vm == null) continue;
            try { vm.getClass().getMethod("jedflixSetCatalogs", List.class).invoke(vm, catalogs); }
            catch (Exception error) { Log.e(TAG, "Cannot publish native Home rows", error); }
        }
    }

    private static View currentRoot() {
        Object fragment = foreground.get();
        if (fragment == null) return null;
        try { return (View) fragment.getClass().getMethod("getView").invoke(fragment); }
        catch (Exception ignored) { return null; }
    }

    private static boolean hasPosterFocus(View root) {
        View focused = root.findFocus();
        while (focused != null) {
            if (focused.getClass().getName().contains("HorizontalGridView")) return true;
            ViewParent parent = focused.getParent();
            focused = parent instanceof View ? (View) parent : null;
            if (focused == root) return false;
        }
        return false;
    }

    private static synchronized void startTick() {
        if (ticking || main == null) return;
        ticking = true;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (foreground.get() != null) requestRefresh();
                main.postDelayed(this, 60000L);
            }
        }, 60000L);
    }

    private static String contextKey(long millis, TimeZone zone, JSONObject signals) {
        Calendar date = Calendar.getInstance(zone);
        date.setTimeInMillis(millis);
        return signals.optString("profile") + "|" + date.get(Calendar.YEAR) + "|"
            + date.get(Calendar.DAY_OF_YEAR) + "|" + date.get(Calendar.HOUR_OF_DAY) + "|"
            + zone.getID() + "|" + DiscoverySignals.digest(signals.toString());
    }

    private static File disk(String profile) {
        File directory = new File(context.getFilesDir(), "jedflix-discovery");
        directory.mkdirs();
        return new File(directory, "home-" + DiscoverySignals.digest(profile) + ".json");
    }

    private static JSONArray readDisk(String profile) {
        File file = disk(profile);
        if (!file.exists() || file.length() > 3000000L) return new JSONArray();
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new JSONArray(bytes.toString("UTF-8"));
        } catch (Exception ignored) { return new JSONArray(); }
    }

    private static void writeDisk(String profile, JSONArray rows) {
        File target = disk(profile), temporary = new File(target.getPath() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(rows.toString().getBytes("UTF-8"));
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) temporary.delete();
        } catch (Exception ignored) { temporary.delete(); }
    }

    private static final class Snapshot {
        final String profile;
        final JSONArray rows;
        final List<Object> catalogs;
        Snapshot(String profile, JSONArray rows, List<Object> catalogs) {
            this.profile = profile; this.rows = rows;
            this.catalogs = Collections.unmodifiableList(catalogs);
        }
    }

    private static final class IdentityPreview extends WeakReference<Object> {
        final int hash;
        IdentityPreview(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof IdentityPreview && get() != null
                && get() == ((IdentityPreview) other).get();
        }
    }
}
