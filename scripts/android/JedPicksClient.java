package com.jedflix.tv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Loads Jed's Picks, the curated Jedflix shelf of movie and series titles. */
public final class JedPicksClient {
    static final String FEED_URL = "https://canny-bat-352.convex.site/tv/jeds-picks";
    static final String FEED_HOST = "canny-bat-352.convex.site";
    static final String FEED_PATH = "/tv/jeds-picks";
    static final String ROW_ID = "home.jeds-picks";
    static final String ROW_TITLE = "Jed\u2019s Picks";
    static final int MAX_PICKS = 80;
    static final int MAX_PARALLEL = 4;
    static final int MAX_BODY_BYTES = 256 * 1024;
    static final int TIMEOUT_MS = 5000;
    static final long TTL_MS = 5L * 60L * 1000L;

    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ThreadFactory WORKERS = new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jeds-picks-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    };
    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    private final Transport transport;
    private final Clock clock;
    private final ItemLoader loader;
    private final Object lock = new Object();
    private final AtomicReference<Cache> cache = new AtomicReference<Cache>();

    public JedPicksClient(Context context, TmdbCatalogClient catalog) {
        if (catalog == null) throw new IllegalArgumentException("A TMDB catalog client is required.");
        this.transport = new HttpTransport();
        this.clock = SYSTEM_CLOCK;
        this.loader = new CatalogLoader(catalog);
    }

    JedPicksClient(Transport transport, Clock clock, ItemLoader loader) {
        if (transport == null || clock == null || loader == null) {
            throw new IllegalArgumentException("A transport, clock and item loader are required.");
        }
        this.transport = transport;
        this.clock = clock;
        this.loader = loader;
    }

    public JSONObject load() throws Exception {
        synchronized (lock) {
            long now = clock.now();
            Cache cached = cache.get();
            if (cached != null && now >= cached.fetchedAt && now - cached.fetchedAt < TTL_MS) {
                return cached.row;
            }

            List<Pick> picks;
            try {
                picks = fetchPicks();
            } catch (Exception failure) {
                if (cached != null) return cached.row;
                throw failure;
            }

            Map<String, JSONObject> previous = cached == null
                ? new HashMap<String, JSONObject>() : cached.metaById;
            if (picks.isEmpty()) {
                JSONObject empty = row(new JSONArray());
                cache.set(new Cache(now, empty, previous));
                return empty;
            }

            Map<String, JSONObject> resolved = loadMetas(picks, previous);
            JSONObject built = buildRow(picks, resolved);
            if (built.getJSONArray("metas").length() > 0) {
                cache.set(new Cache(now, built, resolved));
            }
            return built;
        }
    }

    private List<Pick> fetchPicks() throws Exception {
        Response response = transport.get(FEED_URL);
        if (response == null || response.status != 200 || response.body == null) {
            throw new IOException("Jed's Picks feed was unavailable.");
        }
        JSONObject root = new JSONObject(new String(response.body, "UTF-8"));
        if (root.optInt("version", -1) != 1) {
            throw new IOException("Jed's Picks feed version was not supported.");
        }
        JSONArray entries = root.optJSONArray("picks");
        if (entries == null) throw new IOException("Jed's Picks feed had no picks.");

        List<Pick> picks = new ArrayList<Pick>();
        Set<String> seen = new HashSet<String>();
        for (int index = 0; index < entries.length() && picks.size() < MAX_PICKS; index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) continue;
            Object rawId = entry.opt("tmdbId");
            if (!(rawId instanceof Number)) continue;
            Number number = (Number) rawId;
            int tmdbId = number.intValue();
            if (tmdbId <= 0 || number.doubleValue() != tmdbId) continue;
            String type = entry.optString("type", "");
            if (!"movie".equals(type) && !"series".equals(type)) continue;
            Pick pick = new Pick(tmdbId, type);
            if (seen.add(pick.key())) picks.add(pick);
        }
        return picks;
    }

    private Map<String, JSONObject> loadMetas(List<Pick> picks,
            Map<String, JSONObject> previous) {
        int threads = Math.min(MAX_PARALLEL, picks.size());
        ExecutorService executor = Executors.newFixedThreadPool(threads, WORKERS);
        List<Future<JSONObject>> futures = new ArrayList<Future<JSONObject>>(picks.size());
        try {
            for (final Pick pick : picks) {
                futures.add(executor.submit(new Callable<JSONObject>() {
                    @Override public JSONObject call() {
                        try {
                            return loader.loadItem(pick.tmdbId, pick.type);
                        } catch (Exception failure) {
                            return null;
                        }
                    }
                }));
            }
            Map<String, JSONObject> result = new HashMap<String, JSONObject>();
            for (int index = 0; index < picks.size(); index++) {
                Pick pick = picks.get(index);
                JSONObject meta = null;
                try {
                    meta = futures.get(index).get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    meta = null;
                }
                if (meta == null) meta = previous.get(pick.key());
                if (meta != null) result.put(pick.key(), meta);
            }
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private static JSONObject buildRow(List<Pick> picks, Map<String, JSONObject> metas)
            throws JSONException {
        JSONArray items = new JSONArray();
        Set<String> imdbIds = new HashSet<String>();
        for (Pick pick : picks) {
            if (items.length() >= MAX_PICKS) break;
            JSONObject meta = metas.get(pick.key());
            if (meta == null || !pick.type.equals(meta.optString("type", ""))) continue;
            String imdb = TmdbCatalogClient.normalizeImdbId(meta.optString("id", ""));
            if (imdb == null || !imdbIds.add(imdb)) continue;
            if (meta.optString("name", "").trim().isEmpty()) continue;
            if (meta.optString("poster", "").trim().isEmpty()) continue;
            items.put(meta);
        }
        return row(items);
    }

    private static JSONObject row(JSONArray metas) throws JSONException {
        return new JSONObject()
            .put("id", ROW_ID)
            .put("title", ROW_TITLE)
            .put("type", "movie")
            .put("curated", true)
            .put("metas", metas);
    }

    static boolean isFeedAddress(String address) {
        if (address == null) return false;
        try {
            URL url = new URL(address);
            return "https".equals(url.getProtocol())
                && FEED_HOST.equals(url.getHost())
                && (url.getPort() == -1 || url.getPort() == 443)
                && url.getUserInfo() == null
                && FEED_PATH.equals(url.getPath())
                && url.getQuery() == null
                && url.getRef() == null;
        } catch (Exception invalid) {
            return false;
        }
    }

    static byte[] readBounded(InputStream stream) throws IOException {
        if (stream == null) throw new IOException();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        try {
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > MAX_BODY_BYTES) throw new IOException();
                out.write(buffer, 0, count);
            }
        } finally {
            try { stream.close(); } catch (Exception ignored) { }
        }
        return out.toByteArray();
    }

    interface Transport { Response get(String url) throws IOException; }

    interface Clock { long now(); }

    interface ItemLoader { JSONObject loadItem(int tmdbId, String type) throws Exception; }

    static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class CatalogLoader implements ItemLoader {
        private final TmdbCatalogClient catalog;

        CatalogLoader(TmdbCatalogClient catalog) { this.catalog = catalog; }

        @Override public JSONObject loadItem(int tmdbId, String type) throws Exception {
            return catalog.loadItem(tmdbId, type);
        }
    }

    private static final class Pick {
        final int tmdbId;
        final String type;

        Pick(int tmdbId, String type) {
            this.tmdbId = tmdbId;
            this.type = type;
        }

        String key() { return type + ":" + tmdbId; }
    }

    private static final class Cache {
        final long fetchedAt;
        final JSONObject row;
        final Map<String, JSONObject> metaById;

        Cache(long fetchedAt, JSONObject row, Map<String, JSONObject> metaById) {
            this.fetchedAt = fetchedAt;
            this.row = row;
            this.metaById = metaById;
        }
    }

    private static final class HttpTransport implements Transport {
        @Override public Response get(String address) throws IOException {
            HttpURLConnection connection = null;
            try {
                if (!isFeedAddress(address)) throw new IOException();
                URL url = new URL(address);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Jedflix-Android/1.0");
                int status;
                try {
                    status = connection.getResponseCode();
                } catch (Exception failure) {
                    throw new IOException();
                }
                byte[] body = new byte[0];
                if (status == 200) {
                    body = readBounded(connection.getInputStream());
                } else {
                    drain(connection.getErrorStream());
                }
                return new Response(status, body);
            } catch (IOException failure) {
                throw new IOException("Jed's Picks request failed.");
            } catch (Exception failure) {
                throw new IOException("Jed's Picks request failed.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private static void drain(InputStream stream) {
            if (stream == null) return;
            try {
                byte[] buffer = new byte[1024];
                int total = 0;
                int count;
                while ((count = stream.read(buffer)) != -1 && total < 16384) { total += count; }
            } catch (Exception ignored) {
            } finally {
                try { stream.close(); } catch (Exception ignored) { }
            }
        }
    }
}
