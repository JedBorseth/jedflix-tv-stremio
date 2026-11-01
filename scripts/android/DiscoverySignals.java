package com.jedflix.tv;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads existing local viewing/library evidence; it never changes Stremio storage. */
public final class DiscoverySignals {
    private static volatile SharedPreferences core;
    private static volatile JSONObject cached = fromBuckets(null, null);
    private static volatile Runnable listener;
    private static final ExecutorService READER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean READING = new AtomicBoolean();
    private static volatile boolean dirty;
    private static final SharedPreferences.OnSharedPreferenceChangeListener CHANGES =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
                if (key == null || "library".equals(key) || "library_recent".equals(key)) refresh();
            }
        };

    private DiscoverySignals() {}

    public static void attach(SharedPreferences preferences) {
        SharedPreferences previous = core;
        if (previous != null) previous.unregisterOnSharedPreferenceChangeListener(CHANGES);
        core = preferences;
        if (preferences != null) preferences.registerOnSharedPreferenceChangeListener(CHANGES);
        refresh();
    }

    public static JSONObject snapshot() {
        return cached;
    }

    public static void setListener(Runnable changed) { listener = changed; }

    private static void refresh() {
        dirty = true;
        if (!READING.compareAndSet(false, true)) return;
        READER.execute(new Runnable() {
            @Override public void run() {
                try {
                    while (dirty) {
                        dirty = false;
                        SharedPreferences preferences = core;
                        JSONObject next = preferences == null ? fromBuckets(null, null)
                            : fromBuckets(preferences.getString("library_recent", null),
                                preferences.getString("library", null));
                        if (preferences != core) { dirty = true; continue; }
                        if (!next.toString().equals(cached.toString())) {
                            cached = next;
                            Runnable changed = listener;
                            if (changed != null) changed.run();
                        }
                    }
                } finally {
                    READING.set(false);
                    if (dirty) refresh();
                }
            }
        });
    }

    public static JSONObject fromBuckets(String recent, String older) {
        JSONObject result = new JSONObject();
        try {
            Map<String, JSONObject> items = new HashMap<String, JSONObject>();
            String recentUid = owner(recent), olderUid = owner(older);
            // Storage can briefly contain the previous account's older bucket.
            if (!recentUid.isEmpty() && !olderUid.isEmpty() && !recentUid.equals(olderUid)) older = null;
            String owner = readBucket(older, items);
            String recentOwner = readBucket(recent, items);
            if (!recentOwner.isEmpty()) owner = recentOwner;
            result.put("profile", digest(owner.isEmpty() ? "local" : owner));
            List<JSONObject> positive = new ArrayList<JSONObject>();
            List<String> excluded = new ArrayList<String>();
            for (JSONObject item : items.values()) {
                String id = item.optString("_id", item.optString("id"));
                String type = item.optString("type");
                if (!id.matches("tt[0-9]+") || !("movie".equals(type) || "series".equals(type))) continue;
                JSONObject state = item.optJSONObject("state");
                if (state == null) state = new JSONObject();
                boolean watched = state.optLong("timesWatched") > 0;
                boolean meaningful = watched || state.optLong("timeWatched") >= 300000L
                    || state.optLong("overallTimeWatched") >= 300000L
                    || state.optLong("timeOffset") >= 1200000L;
                boolean saved = !item.optBoolean("removed") && !item.optBoolean("temp");
                boolean continuing = !item.optBoolean("removed") && state.optLong("timeOffset") > 0;
                if (meaningful || saved || continuing) excluded.add(id);
                if (meaningful || saved) positive.add(item);
            }
            Collections.sort(excluded);
            Collections.sort(positive, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject left, JSONObject right) {
                    String a = activity(left), b = activity(right);
                    int date = b.compareTo(a);
                    if (date != 0) return date;
                    return left.optString("_id").compareTo(right.optString("_id"));
                }
            });
            JSONArray seeds = new JSONArray();
            // Prefer a mix of movie and series evidence when both are available.
            List<JSONObject> selected = new ArrayList<JSONObject>();
            for (JSONObject item : positive) {
                if (selected.isEmpty() || !selected.get(0).optString("type").equals(item.optString("type"))) {
                    selected.add(item);
                    if (selected.size() == 2) break;
                }
            }
            for (JSONObject item : positive) {
                if (selected.size() >= 3) break;
                if (!selected.contains(item)) selected.add(item);
            }
            for (JSONObject item : selected) {
                JSONObject seed = new JSONObject();
                seed.put("id", item.optString("_id", item.optString("id")));
                seed.put("type", item.optString("type"));
                seed.put("name", item.optString("name"));
                JSONObject evidence = item.optJSONObject("state");
                seed.put("watched", evidence != null && (evidence.optLong("timesWatched") > 0
                    || evidence.optLong("timeWatched") >= 300000L
                    || evidence.optLong("overallTimeWatched") >= 300000L
                    || evidence.optLong("timeOffset") >= 1200000L));
                seeds.put(seed);
            }
            result.put("seeds", seeds);
            result.put("exclude", new JSONArray(excluded));
            result.put("preferredGenres", new JSONArray());
        } catch (Exception ignored) {
            // A malformed library record cannot prevent Home from opening.
        }
        return result;
    }

    private static String activity(JSONObject item) {
        JSONObject state = item.optJSONObject("state");
        String watched = state == null ? "" : state.optString("lastWatched", "");
        return watched.isEmpty() || "null".equals(watched) ? item.optString("_mtime", "") : watched;
    }

    private static String readBucket(String raw, Map<String, JSONObject> items) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            JSONObject bucket = new JSONObject(raw);
            JSONObject entries = bucket.optJSONObject("items");
            if (entries != null) {
                java.util.Iterator<String> keys = entries.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject next = entries.optJSONObject(key);
                    if (next == null) continue;
                    JSONObject old = items.get(key);
                    if (old == null || next.optString("_mtime").compareTo(old.optString("_mtime")) >= 0) items.put(key, next);
                }
            }
            return bucket.isNull("uid") ? "" : bucket.optString("uid", "");
        } catch (Exception ignored) { return ""; }
    }

    private static String owner(String raw) {
        try {
            JSONObject bucket = new JSONObject(raw == null ? "{}" : raw);
            return bucket.isNull("uid") ? "" : bucket.optString("uid", "");
        } catch (Exception ignored) { return ""; }
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(java.util.Locale.US, "%02x", bytes[i] & 255));
            return out.toString();
        } catch (Exception ignored) { return "local"; }
    }
}
