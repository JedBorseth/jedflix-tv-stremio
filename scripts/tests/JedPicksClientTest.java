package com.jedflix.tv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class JedPicksClientTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        testRowContractAndOrder();
        testCacheAndStaleFallback();
        testConfirmedEmptyRemovesPicks();
        testDeduplicationAndBadItems();
        testMaxPicks();
        testPreservesPreviousMeta();
        testInvalidFeedsFallBack();
        testColdFailureThrows();
        testRemovedPicksAreNotResurrected();
        testConcurrencyBound();
        testAddressAndBodyBounds();
        System.out.println("PASS: Jed's Picks feed contract, cache, staleness, dedupe, bounds, and transport");
    }

    private static void testRowContractAndOrder() throws Exception {
        FakeTransport transport = new FakeTransport();
        JSONArray picks = picks();
        add(picks, 100, "movie");
        add(picks, 200, "series");
        add(picks, 101, "movie");
        transport.body = feed(picks);

        MapLoader loader = new MapLoader();
        loader.metas.put("movie:100", meta("tt0000100", "movie", "Movie A"));
        loader.metas.put("series:200", meta("tt0000200", "series", "Series B"));
        loader.metas.put("movie:101", meta("tt0000101", "movie", "Movie C"));

        JSONObject row = new JedPicksClient(transport, new FakeClock(1000L), loader).load();
        check(JedPicksClient.ROW_ID.equals(row.getString("id")), "row keeps the fixed id");
        check("Jed\u2019s Picks".equals(row.getString("title")), "row keeps the curated title");
        check("movie".equals(row.getString("type")), "curated row advertises the movie shelf type");
        check(row.getBoolean("curated"), "row is marked curated");
        JSONArray metas = row.getJSONArray("metas");
        check(metas.length() == 3, "every valid pick is returned");
        check("tt0000100".equals(metas.getJSONObject(0).getString("id")), "web order is preserved");
        check("movie".equals(metas.getJSONObject(0).getString("type")), "movie metadata is respected");
        check("tt0000200".equals(metas.getJSONObject(1).getString("id")), "series follows in web order");
        check("series".equals(metas.getJSONObject(1).getString("type")), "series metadata is respected");
        check("tt0000101".equals(metas.getJSONObject(2).getString("id")), "later picks keep order");
        check(transport.requests.size() == 1
            && JedPicksClient.FEED_URL.equals(transport.requests.get(0)), "the fixed HTTPS feed is used");
    }

    private static void testCacheAndStaleFallback() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeClock clock = new FakeClock(1_000_000L);
        MapLoader loader = new MapLoader();
        loader.metas.put("movie:100", meta("tt0000100", "movie", "Cached"));
        transport.body = feed(add(picks(), 100, "movie"));
        JedPicksClient client = new JedPicksClient(transport, clock, loader);

        JSONObject first = client.load();
        check(first.getJSONArray("metas").length() == 1, "initial load resolves the pick");

        clock.now += JedPicksClient.TTL_MS - 1000L;
        transport.body = "invalid";
        JSONObject cached = client.load();
        check(cached.getJSONArray("metas").length() == 1
            && transport.requests.size() == 1, "a fresh cache serves picks without another request");

        clock.now += 2000L;
        transport.fail = true;
        JSONObject stale = client.load();
        check(stale.getJSONArray("metas").length() == 1
            && "tt0000100".equals(stale.getJSONArray("metas").getJSONObject(0).getString("id")),
            "a network failure keeps the previous full row");
        check(transport.requests.size() == 2, "staleness is retried instead of pinned forever");

        transport.fail = false;
        transport.status = 500;
        check(client.load().getJSONArray("metas").length() == 1, "an HTTP failure keeps the previous row");

        transport.status = 200;
        transport.body = "{\"version\":1}";
        check(client.load().getJSONArray("metas").length() == 1, "an invalid feed keeps the previous row");
    }

    private static void testConfirmedEmptyRemovesPicks() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeClock clock = new FakeClock(50_000L);
        MapLoader loader = new MapLoader();
        loader.metas.put("movie:100", meta("tt0000100", "movie", "Cached"));
        transport.body = feed(add(picks(), 100, "movie"));
        JedPicksClient client = new JedPicksClient(transport, clock, loader);
        check(client.load().getJSONArray("metas").length() == 1, "a pick is cached before the empty feed");

        clock.now += JedPicksClient.TTL_MS + 1L;
        transport.body = feed(new JSONArray());
        JSONObject empty = client.load();
        check(empty.getJSONArray("metas").length() == 0, "a confirmed empty feed clears the row");
        check(JedPicksClient.ROW_ID.equals(empty.getString("id"))
            && empty.getBoolean("curated"), "the empty row keeps the shelf identity");

        int requests = transport.requests.size();
        clock.now += 1000L;
        check(client.load().getJSONArray("metas").length() == 0
            && transport.requests.size() == requests, "the confirmed empty row is cached");

        clock.now += JedPicksClient.TTL_MS + 1L;
        transport.fail = true;
        check(client.load().getJSONArray("metas").length() == 0,
            "an empty feed never falls back to old picks");
    }

    private static void testDeduplicationAndBadItems() throws Exception {
        FakeTransport transport = new FakeTransport();
        JSONArray picks = picks();
        add(picks, 10, "movie");
        add(picks, 10, "movie");
        add(picks, 11, "movie");
        add(picks, 12, "movie");
        add(picks, 13, "series");
        add(picks, 14, "movie");
        add(picks, 15, "movie");
        transport.body = feed(picks);

        MapLoader loader = new MapLoader();
        loader.metas.put("movie:10", meta("tt0000010", "movie", "Ten"));
        loader.metas.put("movie:11", meta("tt0000011", "movie", "Eleven"));
        loader.metas.put("movie:12", meta("tt0000011", "movie", "Eleven Again"));
        loader.metas.put("series:13", meta("tt0000013", "series", "Thirteen"));
        loader.metas.put("movie:14", meta("", "movie", "No IMDb"));
        loader.metas.put("movie:15", meta("tt0000015", "movie", ""));
        JSONObject row = new JedPicksClient(transport, new FakeClock(0L), loader).load();
        JSONArray metas = row.getJSONArray("metas");

        check(metas.length() == 3, "duplicates and unusable metadata are skipped");
        check("tt0000010".equals(metas.getJSONObject(0).getString("id")), "the first pick is kept");
        check("tt0000011".equals(metas.getJSONObject(1).getString("id")), "the first IMDb match wins");
        check("series".equals(metas.getJSONObject(2).getString("type")), "series picks stay series");
        int duplicates = 0;
        for (String call : loader.calls) if (call.equals("movie:10")) duplicates++;
        check(duplicates == 1, "duplicate type and TMDB ids are collapsed before loading");
        int noImdbCalls = 0;
        for (String call : loader.calls) if (call.equals("movie:14")) noImdbCalls++;
        check(noImdbCalls == 1, "a known-bad item is resolved once and then skipped");
    }

    private static void testMaxPicks() throws Exception {
        FakeTransport transport = new FakeTransport();
        JSONArray picks = picks();
        MapLoader loader = new MapLoader();
        for (int id = 1; id <= 90; id++) {
            add(picks, id, "movie");
            loader.metas.put("movie:" + id, meta(imdb(id), "movie", "Title " + id));
        }
        transport.body = feed(picks);
        JSONArray metas = new JedPicksClient(transport, new FakeClock(0L), loader)
            .load().getJSONArray("metas");
        check(metas.length() == JedPicksClient.MAX_PICKS, "no more than 80 picks are published");
        check(imdb(1).equals(metas.getJSONObject(0).getString("id")), "the cap keeps web order");
        check(imdb(80).equals(metas.getJSONObject(79).getString("id")), "the cap ends at the eightieth pick");
        check(loader.calls.size() == JedPicksClient.MAX_PICKS, "metadata is not loaded for dropped picks");
    }

    private static void testPreservesPreviousMeta() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeClock clock = new FakeClock(0L);
        MapLoader loader = new MapLoader();
        JSONObject old = meta("tt0000001", "movie", "Unchanged");
        loader.metas.put("movie:1", old);
        transport.body = feed(add(picks(), 1, "movie"));
        JedPicksClient client = new JedPicksClient(transport, clock, loader);
        check(client.load().getJSONArray("metas").length() == 1, "the original pick resolves");

        clock.now += JedPicksClient.TTL_MS + 1L;
        JSONArray picks = picks();
        add(picks, 1, "movie");
        add(picks, 2, "series");
        add(picks, 3, "movie");
        transport.body = feed(picks);
        loader.failures.add("movie:1");
        loader.metas.put("series:2", meta("tt0000002", "series", "New Series"));
        JSONObject row = client.load();
        JSONArray metas = row.getJSONArray("metas");
        check(metas.length() == 2, "failed and unknown items do not fabricate metadata");
        check("Unchanged".equals(metas.getJSONObject(0).getString("name"))
            && "movie".equals(metas.getJSONObject(0).getString("type")),
            "a transient TMDB failure keeps the previous metadata for an unchanged id");
        check("tt0000002".equals(metas.getJSONObject(1).getString("id")),
            "new metadata still follows in web order");
    }

    private static void testInvalidFeedsFallBack() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeClock clock = new FakeClock(0L);
        MapLoader loader = new MapLoader();
        loader.metas.put("movie:1", meta("tt0000001", "movie", "Keep"));
        transport.body = feed(add(picks(), 1, "movie"));
        JedPicksClient client = new JedPicksClient(transport, clock, loader);
        check(client.load().getJSONArray("metas").length() == 1, "a good feed seeds the cache");

        clock.now += JedPicksClient.TTL_MS + 1L;
        JSONArray wrongType = picks();
        add(wrongType, 2, "person");
        add(wrongType, 0, "movie");
        add(wrongType, -5, "movie");
        add(wrongType, 1.5, "movie");
        add(wrongType, 3, "movie");
        loader.metas.put("movie:3", meta("tt0000003", "movie", "Three"));
        transport.body = feed(wrongType);
        JSONArray metas = client.load().getJSONArray("metas");
        check(metas.length() == 1 && "tt0000003".equals(metas.getJSONObject(0).getString("id")),
            "invalid entries are ignored while valid picks resolve");

        clock.now += JedPicksClient.TTL_MS + 1L;
        transport.body = new JSONObject().put("version", 2)
            .put("picks", add(picks(), 4, "movie")).toString();
        check("tt0000003".equals(client.load().getJSONArray("metas")
            .getJSONObject(0).getString("id")), "an unsupported version is a failed fetch");
    }

    private static void testColdFailureThrows() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.fail = true;
        boolean threw = false;
        try {
            new JedPicksClient(transport, new FakeClock(0L), new MapLoader()).load();
        } catch (IOException expected) {
            threw = true;
        }
        check(threw, "a cold failure throws so Home keeps its persisted row");

        transport.fail = false;
        transport.status = 500;
        threw = false;
        try {
            new JedPicksClient(transport, new FakeClock(0L), new MapLoader()).load();
        } catch (IOException expected) {
            threw = true;
        }
        check(threw, "an invalid cold contract throws so Home keeps its persisted row");
    }

    private static void testRemovedPicksAreNotResurrected() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeClock clock = new FakeClock(0L);
        MapLoader loader = new MapLoader();
        loader.metas.put("movie:1", meta("tt0000001", "movie", "Removed"));
        transport.body = feed(add(picks(), 1, "movie"));
        JedPicksClient client = new JedPicksClient(transport, clock, loader);
        check("Removed".equals(client.load().getJSONArray("metas")
            .getJSONObject(0).getString("name")), "the old pick is cached first");

        clock.now += JedPicksClient.TTL_MS + 1L;
        transport.body = feed(add(picks(), 2, "movie"));
        loader.failures.add("movie:2");
        JSONObject built = client.load();
        check(built.getJSONArray("metas").length() == 0,
            "an unavailable new pick does not resurrect the removed pick");

        int requests = transport.requests.size();
        check(client.load().getJSONArray("metas").length() == 0
            && transport.requests.size() == requests + 1,
            "an unresolved valid feed is retried instead of cached");

        loader.failures.clear();
        loader.metas.put("movie:2", meta("tt0000002", "movie", "Replacement"));
        JSONObject retried = client.load();
        check(retried.getJSONArray("metas").length() == 1
            && "tt0000002".equals(retried.getJSONArray("metas").getJSONObject(0).getString("id")),
            "the retry publishes the new pick once metadata is available");
    }

    private static void testConcurrencyBound() throws Exception {
        FakeTransport transport = new FakeTransport();
        JSONArray picks = picks();
        MapLoader loader = new MapLoader();
        for (int id = 1; id <= 8; id++) {
            add(picks, id, "movie");
            loader.metas.put("movie:" + id, meta(imdb(id), "movie", "Title " + id));
        }
        loader.delayMs = 150L;
        transport.body = feed(picks);
        JedPicksClient client = new JedPicksClient(transport, new FakeClock(0L), loader);
        JSONArray metas = client.load().getJSONArray("metas");
        check(metas.length() == 8, "parallel metadata loading keeps every pick");
        check(loader.maxActive() <= JedPicksClient.MAX_PARALLEL,
            "at most four metadata loads run in parallel");
        check(loader.maxActive() >= 2, "metadata loads are actually parallel");
        for (int index = 0; index < metas.length(); index++) {
            check(imdb(index + 1).equals(metas.getJSONObject(index).getString("id")),
                "parallel completion does not reorder picks");
        }
    }

    private static void testAddressAndBodyBounds() throws Exception {
        check(JedPicksClient.isFeedAddress(JedPicksClient.FEED_URL), "the fixed feed URL is accepted");
        check(!JedPicksClient.isFeedAddress(null), "a null address is rejected");
        check(!JedPicksClient.isFeedAddress(
            "http://canny-bat-352.convex.site/tv/jeds-picks"), "plain HTTP is rejected");
        check(!JedPicksClient.isFeedAddress(
            "https://canny-bat-352.convex.site:8443/tv/jeds-picks"), "a non-default port is rejected");
        check(!JedPicksClient.isFeedAddress(
            "https://user@canny-bat-352.convex.site/tv/jeds-picks"), "credentials are rejected");
        check(!JedPicksClient.isFeedAddress(
            "https://example.com/tv/jeds-picks"), "another host is rejected");
        check(!JedPicksClient.isFeedAddress(
            "https://canny-bat-352.convex.site/tv/other"), "another path is rejected");
        check(!JedPicksClient.isFeedAddress(
            "https://canny-bat-352.convex.site/tv/jeds-picks?extra=1"), "query strings are rejected");

        byte[] max = new byte[JedPicksClient.MAX_BODY_BYTES];
        check(JedPicksClient.readBounded(new ByteArrayInputStream(max)).length
            == JedPicksClient.MAX_BODY_BYTES, "a response at the byte limit is accepted");
        boolean overflow = false;
        try {
            JedPicksClient.readBounded(new ByteArrayInputStream(
                new byte[JedPicksClient.MAX_BODY_BYTES + 1]));
        } catch (IOException expected) {
            overflow = true;
        }
        check(overflow, "an oversized response is rejected");
    }

    private static JSONArray picks() { return new JSONArray(); }

    private static JSONArray add(JSONArray picks, Object tmdbId, String type) throws Exception {
        return picks.put(new JSONObject().put("tmdbId", tmdbId).put("type", type));
    }

    private static String feed(JSONArray picks) throws Exception {
        return new JSONObject().put("version", 1).put("updatedAt", 1700000000000L)
            .put("picks", picks).toString();
    }

    private static JSONObject meta(String imdb, String type, String name) throws Exception {
        return new JSONObject().put("id", imdb).put("type", type).put("name", name)
            .put("poster", "https://image.tmdb.org/t/p/w500/" + imdb + ".jpg");
    }

    private static String imdb(int id) {
        return String.format(Locale.US, "tt%07d", Integer.valueOf(id));
    }

    private static final class FakeClock implements JedPicksClient.Clock {
        long now;

        FakeClock(long now) { this.now = now; }

        @Override public long now() { return now; }
    }

    private static final class FakeTransport implements JedPicksClient.Transport {
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        String body = "";
        int status = 200;
        boolean fail;

        @Override public JedPicksClient.Response get(String url) throws IOException {
            requests.add(url);
            if (fail) throw new IOException("offline");
            try {
                return new JedPicksClient.Response(status, body.getBytes("UTF-8"));
            } catch (java.io.UnsupportedEncodingException failure) {
                throw new IOException();
            }
        }
    }

    private static final class MapLoader implements JedPicksClient.ItemLoader {
        final Map<String, JSONObject> metas = new HashMap<String, JSONObject>();
        final Set<String> failures = new HashSet<String>();
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        long delayMs;
        private int active;
        private int maxActive;

        @Override public JSONObject loadItem(int tmdbId, String type) throws Exception {
            String key = type + ":" + tmdbId;
            calls.add(key);
            synchronized (this) {
                active++;
                if (active > maxActive) maxActive = active;
            }
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                if (failures.contains(key)) throw new IOException("tmdb offline");
                return metas.get(key);
            } finally {
                synchronized (this) { active--; }
            }
        }

        synchronized int maxActive() { return maxActive; }
    }
}
