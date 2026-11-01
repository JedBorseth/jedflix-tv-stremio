package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Shared page-level selection rules, independent of views and transport. */
public final class DiscoveryFeed {
    private DiscoveryFeed() {}

    public static JSONArray assemble(JSONArray rows, JSONObject signals) {
        JSONArray result = new JSONArray();
        Set<String> used = new HashSet<String>();
        JSONArray exclude = signals == null ? null : signals.optJSONArray("exclude");
        if (exclude != null) for (int i = 0; i < exclude.length(); i++) used.add(exclude.optString(i));
        if (rows == null) return result;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONArray source = row.optJSONArray("metas"), chosen = new JSONArray();
            boolean curated = row.optBoolean("curated");
            Set<String> inRow = new HashSet<String>();
            if (source == null) continue;
            for (int j = 0; j < source.length() && chosen.length() < (curated ? 80 : 32); j++) {
                JSONObject item = source.optJSONObject(j);
                if (item == null) continue;
                String id = item.optString("id");
                if (!id.matches("tt[0-9]+") || (!curated && used.contains(id)) || inRow.contains(id)) continue;
                if (item.optString("poster").isEmpty() || item.optString("name").isEmpty()) continue;
                inRow.add(id);
                chosen.put(item);
            }
            // A few strong suggestions are preferable to a nearly empty shelf.
            if (chosen.length() < (curated ? 1 : 4)) continue;
            try {
                JSONObject output = new JSONObject().put("id", row.optString("id"))
                    .put("title", row.optString("title")).put("type", row.optString("type"))
                    .put("metas", chosen).put("curated", curated);
                result.put(output);
                for (String id : inRow) used.add(id);
            } catch (Exception ignored) {}
        }
        return result;
    }

    /** Appending shelves preserves every existing row and poster identity. */
    public static boolean isAppendOnly(JSONArray before, JSONArray after) {
        if (before == null || before.length() == 0) return true;
        if (after == null || after.length() < before.length()) return false;
        for (int i = 0; i < before.length(); i++) {
            JSONObject a = before.optJSONObject(i), b = after.optJSONObject(i);
            if (a == null || b == null || !a.optString("id").equals(b.optString("id"))
                    || !a.optString("title").equals(b.optString("title"))) return false;
            JSONArray am = a.optJSONArray("metas"), bm = b.optJSONArray("metas");
            if (am == null || bm == null || am.length() != bm.length()) return false;
            for (int j = 0; j < am.length(); j++) {
                if (!am.optJSONObject(j).optString("id").equals(bm.optJSONObject(j).optString("id"))) return false;
            }
        }
        return true;
    }
}
