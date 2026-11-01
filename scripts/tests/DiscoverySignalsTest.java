package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DiscoverySignalsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject item(String id, String type, boolean saved, long minutes) throws Exception {
        JSONObject state = new JSONObject().put("timeWatched", minutes * 60000L).put("timeOffset", minutes * 60000L);
        return new JSONObject().put("_id", id).put("name", id).put("type", type)
            .put("removed", !saved).put("temp", !saved)
            .put("_mtime", "2026-10-31T20:00:00.000Z").put("state", state);
    }

    public static void main(String[] args) throws Exception {
        JSONObject entries = new JSONObject();
        entries.put("tt100", item("tt100", "series", false, 12));
        entries.put("tt200", item("tt200", "movie", true, 0));
        entries.put("tt300", item("tt300", "movie", false, 0));
        entries.put("tt400", item("tt400", "movie", false, 1));
        entries.put("tmdb:5", item("tmdb:5", "movie", true, 10));
        String recent = new JSONObject().put("uid", "account-a").put("items", entries).toString();
        JSONObject signals = DiscoverySignals.fromBuckets(recent, null);
        JSONArray seeds = signals.getJSONArray("seeds");
        check(seeds.length() == 2, "Only meaningful watches and saved items seed taste");
        for (int i = 0; i < seeds.length(); i++) {
            JSONObject seed = seeds.getJSONObject(i);
            check(seed.getBoolean("watched") == "tt100".equals(seed.getString("id")),
                "Saved-only seeds must not be described as watched");
        }
        check(!seeds.getJSONObject(0).getString("type").equals(seeds.getJSONObject(1).getString("type")), "Mixed evidence keeps movie/series variety");
        check(!signals.toString().contains("account-a"), "Profile identity is hashed");
        check(!signals.getJSONArray("exclude").toString().contains("tt300"), "Unwatched removed items stay eligible");
        check(!signals.getJSONArray("exclude").toString().contains("tmdb:5"), "Non-IMDb records cannot seed Comet discovery");
        JSONObject old = new JSONObject().put("uid", "account-b").put("items",
            new JSONObject().put("tt999", item("tt999", "movie", true, 0)));
        check(!DiscoverySignals.fromBuckets(recent, old.toString()).toString().contains("tt999"), "Previous account's bucket is ignored");
        check(DiscoverySignals.fromBuckets("broken", null).optJSONArray("seeds").length() == 0, "Malformed storage has a useful cold-start fallback");
        check(DiscoverySignals.fromBuckets(recent, null).toString().equals(signals.toString()), "Snapshot is deterministic");
        System.out.println("PASS: meaningful local taste, exclusions, profile isolation, and malformed-library fallback");
    }
}
