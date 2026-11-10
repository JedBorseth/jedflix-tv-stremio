package com.jedflix.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Canonical release preference for the shared broadcast timeline. */
public final class LiveTvStreamRules {
    /** A file may differ from the metadata runtime by this much before it is
     * considered a different cut of the episode. Releases routinely include
     * recaps, credits or a few extra seconds; extended edits do not. */
    private static final long MIN_DURATION_SLACK_MS = 3L * 60L * 1000L;
    private static final int DURATION_SLACK_PERCENT = 15;

    private static final class Candidate {
        final JSONObject stream;
        final int rank;
        final String sortKey;

        Candidate(JSONObject stream, int rank, String sortKey) {
            this.stream = stream;
            this.rank = rank;
            this.sortKey = sortKey;
        }
    }

    private static final Comparator<Candidate> ORDER = new Comparator<Candidate>() {
        @Override public int compare(Candidate left, Candidate right) {
            if (left.rank != right.rank) return left.rank - right.rank;
            return left.sortKey.compareTo(right.sortKey);
        }
    };

    private LiveTvStreamRules() {}

    static boolean validVideoId(String type, String id) {
        if (id == null) return false;
        if ("movie".equals(type)) return id.matches("tt[0-9]+");
        return "series".equals(type) && id.matches("tt[0-9]+:[0-9]+:[0-9]+");
    }

    /** Stable release selection, independent of response order or personal RD URLs. */
    static JSONObject select(JSONArray streams) {
        List<JSONObject> ordered = candidates(streams, null);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /** Stable release order, optionally skipping already-rejected releases. */
    static List<JSONObject> candidates(JSONArray streams, Set<String> excluded) {
        List<Candidate> usable = new ArrayList<Candidate>();
        if (streams == null) return new ArrayList<JSONObject>();
        for (int i = 0; i < Math.min(streams.length(), 1000); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null || !playableUrl(stream.optString("url", ""))) continue;
            String key = key(stream);
            String name = stream.optString("name", "");
            String title = stream.optString("title", stream.optString("description", ""));
            JSONObject hints = stream.optJSONObject("behaviorHints");
            String filename = hints == null ? "" : hints.optString("filename", "");
            String label = (filename + "\n" + name + "\n" + title).toLowerCase(Locale.US);
            // Extended/Superfan cuts have a different timeline. Prefer regular
            // releases and the widely available 1080p encode within the user's
            // configured source results; never fetch outside their settings.
            if (label.contains("superfan") || label.contains("extended")) continue;
            if (excluded != null && excluded.contains(key)) continue;
            int rank = label.contains("1080") ? 0 : label.contains("720") ? 1
                    : label.contains("2160") || label.contains("4k") ? 2 : 3;
            usable.add(new Candidate(stream, rank, key));
        }
        Collections.sort(usable, ORDER);
        List<JSONObject> ordered = new ArrayList<JSONObject>(usable.size());
        for (int i = 0; i < usable.size(); i++) ordered.add(usable.get(i).stream);
        return ordered;
    }

    /** Stable identity used to avoid retrying the same release. */
    static String key(JSONObject stream) {
        if (stream == null) return "";
        JSONObject hints = stream.optJSONObject("behaviorHints");
        String filename = hints == null ? "" : hints.optString("filename", "");
        if (!filename.isEmpty()) {
            // Add-on descriptions contain changing seed counts/cache badges.
            // The same file should retain its identity across requests/clients.
            return (filename + "\n" + hints.optLong("videoSize", 0L)).toLowerCase(Locale.US);
        }
        return (filename + "\n" + stream.optString("name", "") + "\n"
                + stream.optString("title", stream.optString("description", "")))
                .toLowerCase(Locale.US);
    }

    /** Runtime guard for files whose cut differs from the broadcast slot. */
    static boolean durationCompatible(long actualMs, long scheduledMs) {
        if (actualMs <= 0L || scheduledMs <= 0L) return true;
        long slack = Math.max(MIN_DURATION_SLACK_MS,
                scheduledMs * DURATION_SLACK_PERCENT / 100L);
        return Math.abs(actualMs - scheduledMs) <= slack;
    }

    static boolean playableUrl(String address) {
        try {
            URI uri = new URI(address);
            String path = uri.getPath();
            String leaf = path == null ? "" : path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.US);
            // Comet returns these successful HTTP video responses for source
            // failures. They are error screens, never an episode release.
            if (leaf.equals("slate.m3u8") || leaf.equals("slate.mp4")) return false;
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception invalid) { return false; }
    }

}
