package com.jedflix.tv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TmdbCatalogClientTest {
    private static final String KEY = "test-key";

    public static void main(String[] args) throws Exception {
        testMovieMappingAndFilters();
        testSeriesRuntimeFilter();
        testSeedResolution();
        testRankingAndBoundedDetails();
        testLongRowLimit();
        testShortRuntimeFilter();
        testLoadItem();
        testDiskCachePrune();
        testStaleCacheFallback();
        System.out.println("TmdbCatalogClientTest passed");
    }

    private static void testMovieMappingAndFilters() throws Exception {
        FixtureTransport transport = new FixtureTransport()
            .route("/trending/movie/week", MOVIE_LIST)
            .route("/movie/204", MOVIE_DETAIL)
            .route("/movie/205", MOVIE_WITHOUT_IMDB);
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());
        JSONObject query = new JSONObject()
            .put("_maxTotalRuntime", 150)
            .put("_family", false)
            .put("vote_count.gte", 200)
            .put("with_genres", "12|10751")
            .put("page", 2);

        JSONArray items = client.load(new DiscoveryRules.Row("trending-movies",
            "Trending movies", "movie", "/trending/movie/week", query, 20, false));

        check(items.length() == 1, "only the released, illustrated, mapped movie is kept");
        JSONObject item = items.getJSONObject(0);
        check("tt1000204".equals(item.getString("id")), "imdb id is mapped from external_ids");
        check("movie".equals(item.getString("type")), "movie type");
        check("Good Movie".equals(item.getString("name")), "detail title wins");
        check(item.getString("poster").startsWith(TmdbCatalogClient.IMAGE_ROOT + "w500/"), "poster size");
        check(item.getString("background").startsWith(TmdbCatalogClient.IMAGE_ROOT + "w1280/"), "background size");
        check(item.getString("logo").endsWith("/w500/logo.png"), "English PNG logo wins");
        check("A good movie.".equals(item.getString("description")), "description");
        check("2020".equals(item.getString("releaseInfo")), "release year");
        check("120 min".equals(item.getString("runtime")), "runtime");
        check(!item.has("imdbRating"), "TMDB rating is never falsely labelled as IMDb");
        check(item.getInt("_tmdbId") == 204, "internal TMDB id");
        check(item.getDouble("_tmdbRating") > 8.0, "internal TMDB rating");
        check(item.getJSONArray("_genreIds").length() == 2, "internal genre ids");
        JSONArray links = item.getJSONArray("links");
        check(hasLink(links, "Genres", "Action"), "genre link");
        check(hasLink(links, "Cast", "Actor One"), "cast link");
        check(!hasLink(links, "imdb", "8.1"), "no fake IMDb rating link");

        boolean plannedPage = false;
        for (String request : transport.requests) {
            assertCleanRequest(request);
            if (request.startsWith(TmdbCatalogClient.API_ROOT + "/trending/movie/week")
                    && request.contains("page=2")) plannedPage = true;
        }
        check(plannedPage, "the row planner's ranked page is honored");
        check(!requested(transport, "/movie/201"), "future release is not enriched");
        check(!requested(transport, "/movie/202"), "adult title is not enriched");
        check(!requested(transport, "/movie/203"), "missing artwork is not enriched");
    }

    private static void testSeriesRuntimeFilter() throws Exception {
        FixtureTransport transport = new FixtureTransport()
            .route("/trending/tv/week", SERIES_LIST)
            .route("/tv/301", SERIES_DETAIL);
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());
        JSONObject query = new JSONObject().put("_maxTotalRuntime", 100);

        JSONArray items = client.load(new DiscoveryRules.Row("limited-series",
            "Limited series", "series", "/trending/tv/week", query, 20, false));

        check(items.length() == 0, "known total runtime above the internal cap is dropped");
        check(requested(transport, "/tv/301"), "series details were fetched once");
    }

    private static void testSeedResolution() throws Exception {
        FixtureTransport transport = new FixtureTransport()
            .route("/find/tt1000404", FIND_MOVIE)
            .route("/movie/404/recommendations", RECOMMENDATIONS)
            .route("/movie/405", RECOMMENDATION_DETAIL);
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());

        JSONArray items = client.load(new DiscoveryRules.Row("because-watched",
            "Because you watched Seed", "movie", "/discover/movie", "tt1000404",
            new JSONObject(), 5, false));
        check(items.length() == 1, "seed recommendations are mapped through IMDb");
        check("tt1000405".equals(items.getJSONObject(0).getString("id")), "recommendation IMDb id");
        check(requested(transport, "/find/tt1000404"), "seed resolved with /find");
        check(requested(transport, "/movie/404/recommendations"), "recommendations endpoint used");
        check(!requested(transport, "/discover/movie"), "discover path is not fetched for a seed row");

        JSONObject resolved = client.resolveImdb("TT1000404", "movie");
        check(resolved != null && resolved.getInt("tmdbId") == 404, "signal IMDb resolve helper");
        check("movie".equals(resolved.getString("type")), "resolved type");
    }

    private static void testRankingAndBoundedDetails() throws Exception {
        RankingTransport transport = new RankingTransport(rankingList(24).toString());
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());
        DiscoveryRules.Row row = new DiscoveryRules.Row("rank-test", "Rank test", "movie",
            "/trending/movie/week", new JSONObject(), 4, false);

        JSONArray items = client.load(row);
        check(items.length() == 4, "ranking selects four before enrichment");
        for (int index = 0; index < 3; index++) {
            int id = items.getJSONObject(index).getInt("_tmdbId");
            check(id >= 6004, "the scored head precedes the low-quality raw head");
        }
        check(transport.details <= 8, "only the small detail window is requested");
        check(transport.details < 24, "the whole candidate page is not enriched");

        JSONArray repeated = client.load(row);
        check(repeated.length() == items.length(), "same row returns the same size");
        for (int index = 0; index < items.length(); index++) {
            check(items.getJSONObject(index).getString("id")
                .equals(repeated.getJSONObject(index).getString("id")),
                "daily ranking is deterministic");
        }
    }

    private static void testLongRowLimit() throws Exception {
        RankingTransport transport = new RankingTransport(rankingList(60).toString());
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());
        DiscoveryRules.Row row = new DiscoveryRules.Row("long-row", "Long row", "movie",
            "/trending/movie/week", new JSONObject(), 48, false);

        JSONArray items = client.load(row);
        check(items.length() == 48, "the 48-item row limit is honored");
        check(transport.details <= 51, "detail requests stay within the sliding window");
    }

    private static void testShortRuntimeFilter() throws Exception {
        FixtureTransport transport = new FixtureTransport()
            .route("/trending/movie/week", SHORT_MOVIE_LIST)
            .route("/movie/701", SHORT_MOVIE_DETAIL)
            .route("/trending/tv/week", SHORT_SERIES_LIST)
            .route("/tv/702", SHORT_SERIES_DETAIL);
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());

        JSONArray movie = client.load(new DiscoveryRules.Row("short-movie", "Short movie",
            "movie", "/trending/movie/week", new JSONObject(), 20, false));
        check(movie.length() == 0, "known 30-minute movies stay off Home");
        JSONArray movieOptIn = client.load(new DiscoveryRules.Row("short-movie-opt",
            "Short movie opt-in", "movie", "/trending/movie/week",
            new JSONObject().put("_shorts", true), 20, false));
        check(movieOptIn.length() == 1, "the shorts opt-in permits a known short film");

        JSONArray series = client.load(new DiscoveryRules.Row("short-series", "Short series",
            "series", "/trending/tv/week", new JSONObject(), 20, false));
        check(series.length() == 0, "known 7-minute episodes stay off Home");
        JSONArray seriesOptIn = client.load(new DiscoveryRules.Row("short-series-opt",
            "Short series opt-in", "series", "/trending/tv/week",
            new JSONObject().put("_shorts", true), 20, false));
        check(seriesOptIn.length() == 1, "the shorts opt-in permits a known short series");
    }

    private static void testLoadItem() throws Exception {
        FixtureTransport transport = new FixtureTransport()
            .route("/movie/204", MOVIE_DETAIL)
            .route("/movie/205", MOVIE_WITHOUT_IMDB)
            .route("/movie/701", SHORT_MOVIE_DETAIL)
            .route("/movie/704", CURATED_LOW_VOTE_DETAIL)
            .route("/tv/301", SERIES_DETAIL);
        TmdbCatalogClient client = new TmdbCatalogClient(null, KEY, transport,
            new FakeClock(epoch("2026-10-31")), tempDir());

        JSONObject mapped = client.loadItem(204, "movie");
        check(mapped != null, "known curated movie metadata loads");
        check("tt1000204".equals(mapped.getString("id")), "curated movie IMDb mapping");
        check("movie".equals(mapped.getString("type")), "curated movie type");
        check(mapped.getString("poster").contains("/g.jpg"), "curated artwork is preserved");
        check(mapped.getJSONArray("links").length() > 0, "curated links are preserved");
        check(client.loadItem(704, "movie") != null, "curated titles skip default vote floors");

        check(client.loadItem(205, "movie") == null, "missing IMDb mapping is filtered");
        check(client.loadItem(701, "movie") == null, "known short movie is filtered");
        JSONObject series = client.loadItem(301, "series");
        check(series != null && "series".equals(series.getString("type")),
            "known curated series metadata loads");
        check(client.loadItem(0, "movie") == null, "invalid TMDB id returns null");
        check(client.loadItem(204, "person") == null, "invalid type returns null");

        int before = countRequests(transport, "/movie/204");
        client.loadItem(204, "movie");
        check(countRequests(transport, "/movie/204") == before, "curated detail uses the cache");
    }

    private static int countRequests(FixtureTransport transport, String path) {
        int count = 0;
        for (String request : transport.requests) {
            if (request.startsWith(TmdbCatalogClient.API_ROOT + path)
                    && (request.length() == TmdbCatalogClient.API_ROOT.length() + path.length()
                        || request.charAt(TmdbCatalogClient.API_ROOT.length() + path.length()) == '?')) {
                count++;
            }
        }
        return count;
    }

    private static void testDiskCachePrune() throws Exception {
        File directory = tempDir();
        List<File> files = new ArrayList<File>();
        for (int index = 0; index < 5; index++) {
            File file = new File(directory,
                String.format(Locale.US, "%064x", Integer.valueOf(index)) + ".json");
            writeFile(file, "0123456789");
            file.setLastModified(1000L + index);
            files.add(file);
        }
        File inflight = new File(directory, "tmdb-inflight.tmp");
        writeFile(inflight, "in-flight");

        TmdbCatalogClient.pruneDiskCache(directory, 3, Long.MAX_VALUE);
        check(!files.get(0).exists() && !files.get(1).exists(), "oldest entries are pruned");
        check(files.get(2).exists() && files.get(3).exists() && files.get(4).exists(),
            "newest entries survive");
        check(inflight.exists(), "in-flight temp files are not pruned");

        TmdbCatalogClient.pruneDiskCache(directory, 100, 15L);
        check(!files.get(2).exists() && !files.get(3).exists(), "byte cap removes oldest first");
        check(files.get(4).exists(), "newest entry survives the byte cap");
        check(inflight.exists(), "in-flight temp files still survive");
    }

    private static JSONObject rankingList(int count) throws Exception {
        JSONObject list = new JSONObject();
        JSONArray results = new JSONArray();
        for (int index = 0; index < count; index++) {
            int id = 6000 + index;
            boolean low = index < 4;
            results.put(new JSONObject()
                .put("id", id).put("media_type", "movie").put("title", "Ranked " + id)
                .put("release_date", "2020-01-01")
                .put("poster_path", "/p" + id + ".jpg")
                .put("backdrop_path", "/b" + id + ".jpg")
                .put("vote_count", low ? 200 : 5000)
                .put("vote_average", low ? 6.0 : 9.0)
                .put("popularity", low ? 1.0 : 100.0)
                .put("genre_ids", new JSONArray().put(28)));
        }
        list.put("page", 1).put("total_pages", 1).put("results", results);
        return list;
    }

    private static void writeFile(File file, String value) throws IOException {
        FileOutputStream stream = new FileOutputStream(file);
        try {
            stream.write(value.getBytes("UTF-8"));
        } finally {
            stream.close();
        }
    }

    private static void testStaleCacheFallback() throws Exception {
        File cache = tempDir();
        FakeClock clock = new FakeClock(epoch("2026-10-31"));
        FixtureTransport online = new FixtureTransport()
            .route("/trending/movie/week", MOVIE_LIST)
            .route("/movie/204", MOVIE_DETAIL)
            .route("/movie/205", MOVIE_WITHOUT_IMDB);
        TmdbCatalogClient first = new TmdbCatalogClient(null, KEY, online, clock, cache);
        JSONArray initial = first.load(new DiscoveryRules.Row("trending-movies",
            "Trending movies", "movie", "/trending/movie/week", new JSONObject(), 20, false));
        check(initial.length() == 1, "fixture row cached");

        clock.now += 8L * 86400000L;
        TmdbCatalogClient offline = new TmdbCatalogClient(null, KEY, new ThrowingTransport(), clock, cache);
        JSONArray stale = offline.load(new DiscoveryRules.Row("trending-movies",
            "Trending movies", "movie", "/trending/movie/week", new JSONObject(), 20, false));
        check(stale.length() == 1, "expired catalog and details survive a network failure");
        check("tt1000204".equals(stale.getJSONObject(0).getString("id")), "stale item identity");
        for (File file : cache.listFiles()) {
            check(!readAll(file).contains(KEY), "the API key is never written to the cache");
        }
    }

    private static boolean hasLink(JSONArray links, String category, String name) throws Exception {
        for (int index = 0; index < links.length(); index++) {
            JSONObject link = links.getJSONObject(index);
            if (category.equals(link.optString("category")) && name.equals(link.optString("name"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean requested(FixtureTransport transport, String path) {
        for (String request : transport.requests) {
            if (request.startsWith(TmdbCatalogClient.API_ROOT + path)
                    && (request.length() == TmdbCatalogClient.API_ROOT.length() + path.length()
                        || request.charAt(TmdbCatalogClient.API_ROOT.length() + path.length()) == '?')) {
                return true;
            }
        }
        return false;
    }

    private static void assertCleanRequest(String url) throws Exception {
        check(url.startsWith(TmdbCatalogClient.API_ROOT + "/"), "only the TMDB v3 endpoint is used");
        int query = url.indexOf('?');
        if (query < 0) return;
        for (String part : url.substring(query + 1).split("&")) {
            String key = part.split("=", 2)[0];
            check(!key.startsWith("_"), "internal row fields are stripped: " + key);
        }
    }

    private static String readAll(File file) throws IOException {
        StringBuilder out = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        } finally {
            reader.close();
        }
        return out.toString();
    }

    private static File tempDir() throws IOException {
        File file = File.createTempFile("jedflix-tmdb-test", "");
        if (!file.delete() || !file.mkdirs()) throw new IOException("Could not create a test cache directory.");
        return file;
    }

    private static long epoch(String date) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsed = format.parse(date);
        return parsed == null ? 0 : parsed.getTime();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FakeClock implements TmdbCatalogClient.Clock {
        long now;

        FakeClock(long now) { this.now = now; }

        @Override public long now() { return now; }
    }

    private static final class ThrowingTransport implements TmdbCatalogClient.Transport {
        @Override public TmdbCatalogClient.RemoteResponse get(String url) throws IOException {
            throw new IOException("offline");
        }
    }

    private static final class RankingTransport implements TmdbCatalogClient.Transport {
        private final String list;
        int details;

        RankingTransport(String list) { this.list = list; }

        @Override public TmdbCatalogClient.RemoteResponse get(String url) throws IOException {
            try {
                String path = URI.create(url).getPath();
                if (path.startsWith("/3/")) path = path.substring(2);
                if ("/trending/movie/week".equals(path)) {
                    return new TmdbCatalogClient.RemoteResponse(200, list, null);
                }
                if (path.startsWith("/movie/")) {
                    details++;
                    int id = Integer.parseInt(path.substring("/movie/".length()));
                    return new TmdbCatalogClient.RemoteResponse(200, detail(id), null);
                }
                return new TmdbCatalogClient.RemoteResponse(404, "", null);
            } catch (Exception failure) {
                return new TmdbCatalogClient.RemoteResponse(500, "", null);
            }
        }

        private static String detail(int id) throws Exception {
            boolean low = id < 6004;
            JSONObject detail = new JSONObject()
                .put("id", id).put("title", "Ranked " + id)
                .put("release_date", "2020-01-01").put("adult", false)
                .put("poster_path", "/p" + id + ".jpg")
                .put("backdrop_path", "/b" + id + ".jpg")
                .put("overview", "Ranked detail.")
                .put("runtime", 120)
                .put("vote_count", low ? 200 : 5000)
                .put("vote_average", low ? 6.0 : 9.0)
                .put("original_language", "en")
                .put("genres", new JSONArray().put(new JSONObject().put("id", 28).put("name", "Action")))
                .put("external_ids", new JSONObject().put("imdb_id",
                    String.format(Locale.US, "tt%07d", Integer.valueOf(id))))
                .put("images", new JSONObject().put("logos", new JSONArray()))
                .put("credits", new JSONObject().put("cast", new JSONArray()))
                .put("release_dates", new JSONObject().put("results", new JSONArray().put(
                    new JSONObject().put("iso_3166_1", "US").put("release_dates", new JSONArray().put(
                        new JSONObject().put("release_date", "2020-01-01T00:00:00.000Z")
                            .put("type", 4))))));
            return detail.toString();
        }
    }

    private static final class FixtureTransport implements TmdbCatalogClient.Transport {
        private final Map<String, String> routes = new HashMap<String, String>();
        final List<String> requests = new ArrayList<String>();

        FixtureTransport route(String path, String body) {
            routes.put(path, body);
            return this;
        }

        @Override public TmdbCatalogClient.RemoteResponse get(String url) throws IOException {
            requests.add(url);
            try {
                URI uri = URI.create(url);
                if (!"https".equals(uri.getScheme())
                        || !"api.themoviedb.org".equals(uri.getHost())
                        || !uri.getPath().startsWith("/3/")) {
                    return new TmdbCatalogClient.RemoteResponse(500, "", null);
                }
                String path = uri.getPath().substring(2);
                String body = routes.get(path);
                if (body == null) return new TmdbCatalogClient.RemoteResponse(404, "", null);
                return new TmdbCatalogClient.RemoteResponse(200, body, null);
            } catch (Exception failure) {
                return new TmdbCatalogClient.RemoteResponse(500, "", null);
            }
        }
    }

    private static final String MOVIE_LIST = "{"
        + "\"page\":1,\"total_pages\":1,\"results\":["
        + "{\"id\":201,\"media_type\":\"movie\",\"title\":\"Future\",\"release_date\":\"2027-01-01\","
        + "\"poster_path\":\"/f.jpg\",\"backdrop_path\":\"/fb.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"genre_ids\":[28]},"
        + "{\"id\":202,\"media_type\":\"movie\",\"title\":\"Adult\",\"release_date\":\"2020-01-01\","
        + "\"poster_path\":\"/a.jpg\",\"backdrop_path\":\"/ab.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"adult\":true,\"genre_ids\":[28]},"
        + "{\"id\":203,\"media_type\":\"movie\",\"title\":\"No Art\",\"release_date\":\"2020-01-01\","
        + "\"poster_path\":\"\",\"backdrop_path\":\"/nb.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"genre_ids\":[28]},"
        + "{\"id\":204,\"media_type\":\"movie\",\"title\":\"Good Movie\",\"release_date\":\"2020-01-01\","
        + "\"poster_path\":\"/g.jpg\",\"backdrop_path\":\"/gb.jpg\",\"overview\":\"A good movie\","
        + "\"vote_count\":900,\"vote_average\":8.1,\"genre_ids\":[28,12]},"
        + "{\"id\":205,\"media_type\":\"movie\",\"title\":\"No IMDb\",\"release_date\":\"2020-01-01\","
        + "\"poster_path\":\"/n.jpg\",\"backdrop_path\":\"/nb2.jpg\",\"vote_count\":900,\"vote_average\":7.5,"
        + "\"genre_ids\":[35]}"
        + "]}";

    private static final String MOVIE_DETAIL = "{"
        + "\"id\":204,\"title\":\"Good Movie\",\"release_date\":\"2020-01-01\",\"adult\":false,"
        + "\"poster_path\":\"/g.jpg\",\"backdrop_path\":\"/gb.jpg\",\"overview\":\"A good movie.\","
        + "\"runtime\":120,\"vote_average\":8.1,\"vote_count\":900,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":28,\"name\":\"Action\"},{\"id\":12,\"name\":\"Adventure\"}],"
        + "\"external_ids\":{\"imdb_id\":\"tt1000204\"},"
        + "\"images\":{\"logos\":["
        + "{\"iso_639_1\":\"fr\",\"file_path\":\"/fr.png\",\"vote_average\":10.0,\"width\":900},"
        + "{\"iso_639_1\":\"en\",\"file_path\":\"/logo.png\",\"vote_average\":9.0,\"width\":800}],"
        + "\"backdrops\":[],\"posters\":[]},"
        + "\"credits\":{\"cast\":[{\"name\":\"Actor One\",\"order\":0},{\"name\":\"Actor Two\",\"order\":1}]},"
        + "\"release_dates\":{\"results\":[{\"iso_3166_1\":\"US\",\"release_dates\":["
        + "{\"release_date\":\"2020-01-01T00:00:00.000Z\",\"type\":4}]}]}"
        + "}";

    private static final String MOVIE_WITHOUT_IMDB = "{"
        + "\"id\":205,\"title\":\"No IMDb\",\"release_date\":\"2020-01-01\",\"adult\":false,"
        + "\"poster_path\":\"/n.jpg\",\"backdrop_path\":\"/nb2.jpg\",\"overview\":\"Unmapped.\","
        + "\"runtime\":100,\"vote_average\":7.5,\"vote_count\":900,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":35,\"name\":\"Comedy\"}],"
        + "\"external_ids\":{\"imdb_id\":\"\"},\"images\":{\"logos\":[]},\"credits\":{\"cast\":[]}"
        + "}";

    private static final String SERIES_LIST = "{"
        + "\"page\":1,\"total_pages\":1,\"results\":["
        + "{\"id\":301,\"media_type\":\"tv\",\"name\":\"Too Long\",\"first_air_date\":\"2020-01-01\","
        + "\"poster_path\":\"/t.jpg\",\"backdrop_path\":\"/tb.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"genre_ids\":[18]}"
        + "]}";

    private static final String SERIES_DETAIL = "{"
        + "\"id\":301,\"name\":\"Too Long\",\"first_air_date\":\"2020-01-01\",\"adult\":false,"
        + "\"poster_path\":\"/t.jpg\",\"backdrop_path\":\"/tb.jpg\",\"overview\":\"Two hours.\","
        + "\"number_of_seasons\":1,\"number_of_episodes\":2,\"episode_run_time\":[60],"
        + "\"vote_average\":8.0,\"vote_count\":900,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":18,\"name\":\"Drama\"}],"
        + "\"external_ids\":{\"imdb_id\":\"tt1000301\"},"
        + "\"images\":{\"logos\":[],\"backdrops\":[],\"posters\":[]},"
        + "\"credits\":{\"cast\":[{\"name\":\"Series Actor\"}]}"
        + "}";

    private static final String CURATED_LOW_VOTE_DETAIL = "{"
        + "\"id\":704,\"title\":\"Curated Gem\",\"release_date\":\"2020-01-01\","
        + "\"adult\":false,\"poster_path\":\"/c.jpg\",\"backdrop_path\":\"/cb.jpg\","
        + "\"overview\":\"Curated.\",\"runtime\":120,\"vote_average\":4.0,"
        + "\"vote_count\":3,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":18,\"name\":\"Drama\"}],"
        + "\"external_ids\":{\"imdb_id\":\"tt1000704\"},"
        + "\"images\":{\"logos\":[],\"backdrops\":[],\"posters\":[]},"
        + "\"credits\":{\"cast\":[]}"
        + "}";

    private static final String SHORT_MOVIE_LIST = "{"
        + "\"page\":1,\"total_pages\":1,\"results\":["
        + "{\"id\":701,\"media_type\":\"movie\",\"title\":\"Short Film\","
        + "\"release_date\":\"2020-01-01\",\"poster_path\":\"/s.jpg\","
        + "\"backdrop_path\":\"/sb.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"genre_ids\":[35]}"
        + "]}";

    private static final String SHORT_MOVIE_DETAIL = "{"
        + "\"id\":701,\"title\":\"Short Film\",\"release_date\":\"2020-01-01\","
        + "\"adult\":false,\"poster_path\":\"/s.jpg\",\"backdrop_path\":\"/sb.jpg\","
        + "\"overview\":\"Thirty minutes.\",\"runtime\":30,\"vote_average\":8.0,"
        + "\"vote_count\":900,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":35,\"name\":\"Comedy\"}],"
        + "\"external_ids\":{\"imdb_id\":\"tt1000701\"},"
        + "\"images\":{\"logos\":[],\"backdrops\":[],\"posters\":[]},"
        + "\"credits\":{\"cast\":[]},"
        + "\"release_dates\":{\"results\":[{\"iso_3166_1\":\"US\",\"release_dates\":["
        + "{\"release_date\":\"2020-01-01T00:00:00.000Z\",\"type\":4}]}]}"
        + "}";

    private static final String SHORT_SERIES_LIST = "{"
        + "\"page\":1,\"total_pages\":1,\"results\":["
        + "{\"id\":702,\"media_type\":\"tv\",\"name\":\"Short Episodes\","
        + "\"first_air_date\":\"2020-01-01\",\"poster_path\":\"/t.jpg\","
        + "\"backdrop_path\":\"/tb.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"genre_ids\":[16]}"
        + "]}";

    private static final String SHORT_SERIES_DETAIL = "{"
        + "\"id\":702,\"name\":\"Short Episodes\",\"first_air_date\":\"2020-01-01\","
        + "\"adult\":false,\"poster_path\":\"/t.jpg\",\"backdrop_path\":\"/tb.jpg\","
        + "\"overview\":\"Seven minutes.\",\"number_of_seasons\":1,"
        + "\"number_of_episodes\":10,\"episode_run_time\":[7],"
        + "\"vote_average\":8.0,\"vote_count\":900,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":16,\"name\":\"Animation\"}],"
        + "\"external_ids\":{\"imdb_id\":\"tt1000702\"},"
        + "\"images\":{\"logos\":[],\"backdrops\":[],\"posters\":[]},"
        + "\"credits\":{\"cast\":[]}"
        + "}";

    private static final String FIND_MOVIE = "{"
        + "\"movie_results\":[{\"id\":404,\"title\":\"Seed\",\"adult\":false}],"
        + "\"tv_results\":[],\"person_results\":[],\"tv_episode_results\":[],\"tv_season_results\":[]"
        + "}";

    private static final String RECOMMENDATIONS = "{"
        + "\"page\":1,\"total_pages\":1,\"results\":["
        + "{\"id\":405,\"media_type\":\"movie\",\"title\":\"Rec\",\"release_date\":\"2020-01-01\","
        + "\"poster_path\":\"/r.jpg\",\"backdrop_path\":\"/rb.jpg\",\"vote_count\":900,\"vote_average\":8.0,"
        + "\"genre_ids\":[28]}"
        + "]}";

    private static final String RECOMMENDATION_DETAIL = "{"
        + "\"id\":405,\"title\":\"Rec\",\"release_date\":\"2020-01-01\",\"adult\":false,"
        + "\"poster_path\":\"/r.jpg\",\"backdrop_path\":\"/rb.jpg\",\"overview\":\"Recommended.\","
        + "\"runtime\":110,\"vote_average\":8.0,\"vote_count\":900,\"original_language\":\"en\","
        + "\"genres\":[{\"id\":28,\"name\":\"Action\"}],"
        + "\"external_ids\":{\"imdb_id\":\"tt1000405\"},"
        + "\"images\":{\"logos\":[],\"backdrops\":[],\"posters\":[]},"
        + "\"credits\":{\"cast\":[{\"name\":\"Rec Actor\"}]}"
        + "}";
}
