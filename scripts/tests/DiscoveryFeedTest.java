package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DiscoveryFeedTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static JSONObject row(String id, int from, int to) throws Exception {
        JSONArray items = new JSONArray();
        for (int n = from; n <= to; n++) items.put(new JSONObject().put("id", "tt" + n)
            .put("name", "Title " + n).put("poster", "https://image.tmdb.org/t/p/w500/test.jpg"));
        return new JSONObject().put("id", id).put("title", id).put("type", "movie").put("metas", items);
    }
    public static void main(String[] args) throws Exception {
        JSONArray input = new JSONArray().put(row("trending", 100, 119)).put(row("family", 110, 129));
        JSONObject signals = new JSONObject().put("exclude", new JSONArray().put("tt100").put("tt101"));
        JSONArray output = DiscoveryFeed.assemble(input, signals);
        check(output.length() == 2, "Overlapping shelves both retain enough suggestions");
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < output.length(); i++) {
            JSONArray items = output.getJSONObject(i).getJSONArray("metas");
            check(items.length() >= 4 && items.length() <= 32, "Shelves are useful and bounded");
            for (int n = 0; n < items.length(); n++) {
                String id = items.getJSONObject(n).getString("id");
                check(!id.equals("tt100") && !id.equals("tt101"), "Watched items are excluded");
                check(seen.add(id), "One title appears only once across Home discovery");
            }
        }
        JSONArray appended = new JSONArray(output.toString()).put(row("documentaries", 300, 305));
        check(DiscoveryFeed.isAppendOnly(output, appended), "New rows can append while a poster has focus");
        appended.getJSONObject(0).getJSONArray("metas").getJSONObject(0).put("id", "tt999");
        check(!DiscoveryFeed.isAppendOnly(output, appended), "Replacement cannot reshuffle under focus");
        check(DiscoveryFeed.assemble(new JSONArray().put(row("empty", 1, 2)), signals).length() == 0,
            "Nearly empty shelves are omitted");
        JSONObject curated = row("home.jeds-picks", 100, 100).put("curated", true);
        JSONArray withPicks = DiscoveryFeed.assemble(new JSONArray().put(curated).put(row("trending", 100, 140)), signals);
        check(withPicks.getJSONObject(0).getJSONArray("metas").getJSONObject(0).getString("id").equals("tt100"),
            "Curated web picks retain watched titles and work as a one-item shelf");
        check(withPicks.getJSONObject(1).getJSONArray("metas").length() == 32,
            "Larger candidate pools fill 32-card shelves after exclusions");
        check(!withPicks.getJSONObject(1).getJSONArray("metas").toString().contains("\"tt100\""),
            "Ordinary discovery avoids repeating an earlier curated pick");
        System.out.println("PASS: cross-row deduplication, watched suppression, row bounds, and stable focus updates");
    }
}
