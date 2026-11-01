package com.jedflix.tv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class TmdbCatalogClient {
    static final String API_ROOT = "https://api.themoviedb.org/3";
    static final String IMAGE_ROOT = "https://image.tmdb.org/t/p/";
    static final String LANGUAGE = "en-US";

    private static final long CATALOG_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long DETAIL_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CACHE_FILE_BYTES = MAX_BODY_BYTES * 3 + 1024;
    private static final int MAX_MEMORY_ENTRIES = 192;
    private static final int PRUNE_EVERY_WRITES = 32;
    private static final int MAX_DISK_ENTRIES = 800;
    private static final long MAX_DISK_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_DETAIL_THREADS = 4;
    private static final int MAX_ITEMS = 80;
    private static final int MAX_PAGES = 4;
    private static final int MAX_START_PAGE = 5;
    private static final int DEFAULT_LIMIT = 48;
    private static final int RECENT_RELEASE_DAYS = 120;
    private static final int PUBLIC_RELEASE_DAYS = 30;
    private static final int SHORT_MOVIE_RUNTIME = 45;
    private static final int SHORT_SERIES_RUNTIME = 15;
    private static final int MAX_CAST_LINKS = 5;
    private static final int MAX_GENRE_LINKS = 6;

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    private final String apiKey;
    private final Transport transport;
    private final Clock clock;
    private final File cacheDir;
    private final Object cacheLock = new Object();
    private int cacheWrites;
    private final LinkedHashMap<String, CacheEntry> memory =
        new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > MAX_MEMORY_ENTRIES;
            }
        };
    private volatile long throttleUntil;

    public TmdbCatalogClient(Context context, String apiKey) {
        this(context, apiKey, null, null, null);
    }

    TmdbCatalogClient(Context context, String apiKey, Transport transport, Clock clock, File cacheDir) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() || key.matches("(?s).*[\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("A TMDB API key is required.");
        }
        this.apiKey = key;
        this.transport = transport == null ? new HttpTransport() : transport;
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
        File directory = cacheDir;
        if (directory == null && context != null) {
            try {
                File base = context.getCacheDir();
                if (base != null) directory = new File(base, "jedflix-tmdb-v1");
            } catch (Exception ignored) {
                directory = null;
            }
        }
        this.cacheDir = directory;
        if (directory != null) {
            try { directory.mkdirs(); } catch (Exception ignored) { }
        }
    }

    public JSONArray load(DiscoveryRules.Row row) throws Exception {
        if (row == null) throw new IllegalArgumentException("A discovery row is required.");
        return loadInternal(row.type, row.path, row.seedImdb, row.query,
            row.limit, row.rotate, row.id, row.title);
    }

    public JSONObject resolveImdb(String imdbId, String preferredType) throws Exception {
        String imdb = normalizeImdbId(imdbId);
        if (imdb == null) return null;
        String type = normalizeType(preferredType);
        FindHit hit = find(imdb, type);
        if (hit == null) return null;
        JSONObject result = new JSONObject();
        result.put("tmdbId", hit.item.optInt("id", -1));
        result.put("type", hit.type);
        String name = first(hit.item.optString("title", ""), hit.item.optString("name", ""));
        if (name != null) result.put("name", name);
        return result;
    }

    public JSONObject loadItem(int tmdbId, String type) throws Exception {
        if (tmdbId <= 0) return null;
        String normalized = normalizeType(type);
        if (normalized == null) return null;
        String apiType = "movie".equals(normalized) ? "movie" : "tv";
        return mapDetail(Candidate.known(tmdbId), apiType, normalized, new Options(), today());
    }

    public static boolean isImdbId(String value) {
        return normalizeImdbId(value) != null;
    }

    public static String normalizeImdbId(String value) {
        if (value == null) return null;
        String candidate = value.trim().toLowerCase(Locale.US);
        if (!candidate.matches("tt[0-9]{5,12}")) return null;
        return candidate;
    }

    private JSONArray loadInternal(String rawType, String rawPath, String seedImdb,
            JSONObject query, int limit, boolean rotate, String rowId, String rowTitle)
            throws Exception {
        String type = normalizeType(rawType);
        if (type == null) throw new IllegalArgumentException("Discovery row type is invalid.");
        String apiType = "movie".equals(type) ? "movie" : "tv";
        JSONObject q = query == null ? new JSONObject() : query;
        Options options = Options.parse(q, rowId, rowTitle);
        int requested = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_ITEMS);
        String today = today();

        String endpoint;
        Map<String, String> baseParams = new TreeMap<String, String>();
        boolean qualityDefaults;
        String imdb = first(seedImdb);
        if (imdb != null) {
            String normalized = normalizeImdbId(imdb);
            if (normalized == null) throw new IllegalArgumentException("Discovery seed is invalid.");
            FindHit seed = find(normalized, type);
            if (seed == null) return new JSONArray();
            int tmdbId = seed.item.optInt("id", -1);
            if (tmdbId <= 0) return new JSONArray();
            endpoint = "/" + apiType + "/" + tmdbId + "/recommendations";
            baseParams.put("language", LANGUAGE);
            qualityDefaults = false;
        } else {
            String path = normalizePath(rawPath);
            requirePath(path, type);
            endpoint = path;
            if (path.startsWith("/discover/")) {
                baseParams.putAll(requestParams(q));
                baseParams.put("include_adult", "false");
                baseParams.put("language", LANGUAGE);
                if (!baseParams.containsKey("sort_by")) baseParams.put("sort_by", "popularity.desc");
                qualityDefaults = false;
            } else {
                baseParams.put("language", LANGUAGE);
                String region = first(q.optString("region", ""));
                if (region != null && region.matches("[A-Za-z]{2}")) baseParams.put("region", region.toUpperCase(Locale.US));
                qualityDefaults = true;
            }
        }

        if (qualityDefaults) {
            options.minVoteCount = Math.max(options.minVoteCount, "movie".equals(type) ? 100 : 50);
        }

        int target = Math.min(MAX_ITEMS, Math.max(requested * 2, 28));
        int page = startPage(rotate, nonNegativeInteger(q.opt("page")), rowId, rowTitle);

        int fetched = 0;
        int totalPages = 1;
        boolean resetTried = false;
        Set<Integer> seenTmdb = new HashSet<Integer>();
        List<Candidate> candidates = new ArrayList<Candidate>();

        while (fetched < MAX_PAGES && candidates.size() < target) {
            JSONObject response;
            try {
                response = fetchObject(endpoint, withPage(baseParams, page),
                    CATALOG_TTL_MS, true, true);
            } catch (Exception failure) {
                if (fetched == 0 && page > 1 && !resetTried) {
                    page = 1;
                    resetTried = true;
                    continue;
                }
                if (fetched == 0) throw failure;
                break;
            }
            if (response == null) break;
            int pages = response.optInt("total_pages", 1);
            totalPages = pages < 1 ? 1 : pages;
            JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) {
                if (fetched == 0 && page > 1 && !resetTried) {
                    page = 1;
                    resetTried = true;
                    continue;
                }
                break;
            }
            addCandidates(candidates, seenTmdb, results, apiType, options, today);
            fetched++;
            page = page >= totalPages ? 1 : page + 1;
        }

        return enrich(rank(candidates, rowId, requested), apiType, type, options, requested, today);
    }

    private List<Candidate> rank(List<Candidate> candidates, String rowId, int limit) {
        if (candidates.size() <= 1) return candidates;
        JSONArray raw = new JSONArray();
        Map<Integer, Candidate> byId = new LinkedHashMap<Integer, Candidate>();
        for (Candidate candidate : candidates) {
            raw.put(candidate.raw);
            byId.put(Integer.valueOf(candidate.tmdbId), candidate);
        }
        JSONArray ranked = DiscoveryRules.rankCandidates(
            raw, rowId, clock.now(), TimeZone.getDefault(), limit);
        List<Candidate> ordered = new ArrayList<Candidate>();
        Set<Integer> used = new HashSet<Integer>();
        for (int index = 0; index < ranked.length(); index++) {
            JSONObject item = ranked.optJSONObject(index);
            if (item == null) continue;
            Candidate candidate = byId.get(Integer.valueOf(item.optInt("id", -1)));
            if (candidate != null && used.add(Integer.valueOf(candidate.tmdbId))) {
                ordered.add(candidate);
            }
        }
        for (Candidate candidate : candidates) {
            if (used.add(Integer.valueOf(candidate.tmdbId))) ordered.add(candidate);
        }
        return ordered;
    }

    private JSONArray enrich(List<Candidate> candidates, String apiType, String type,
            Options options, int limit, String today) throws Exception {
        if (candidates.isEmpty()) return new JSONArray();
        ExecutorService executor = Executors.newFixedThreadPool(MAX_DETAIL_THREADS,
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "jedflix-tmdb-detail");
                    thread.setDaemon(true);
                    return thread;
                }
            });
        List<Future<JSONObject>> futures = new ArrayList<Future<JSONObject>>();
        int count = Math.min(candidates.size(), MAX_ITEMS);
        int submitted = 0;
        JSONArray output = new JSONArray();
        Set<String> seenImdb = new HashSet<String>();
        Set<Integer> seenTmdb = new HashSet<Integer>();
        try {
            while (submitted < count && submitted < MAX_DETAIL_THREADS) {
                futures.add(submitDetail(executor, candidates.get(submitted), apiType, type, options, today));
                submitted++;
            }
            for (int index = 0; index < count && output.length() < limit; index++) {
                if (index >= futures.size()) break;
                JSONObject item;
                try {
                    item = futures.get(index).get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    item = null;
                }
                if (item != null) {
                    String imdb = normalizeImdbId(item.optString("id", ""));
                    int tmdbId = item.optInt("_tmdbId", -1);
                    if (imdb != null && tmdbId > 0
                            && seenImdb.add(imdb) && seenTmdb.add(tmdbId)) {
                        output.put(item);
                    }
                }
                if (output.length() < limit && submitted < count) {
                    futures.add(submitDetail(executor, candidates.get(submitted), apiType, type, options, today));
                    submitted++;
                }
            }
            return output;
        } finally {
            executor.shutdownNow();
        }
    }

    private Future<JSONObject> submitDetail(ExecutorService executor, final Candidate candidate,
            final String apiType, final String type, final Options options, final String today) {
        return executor.submit(new Callable<JSONObject>() {
            @Override public JSONObject call() {
                try {
                    return mapDetail(candidate, apiType, type, options, today);
                } catch (Exception ignored) {
                    return null;
                }
            }
        });
    }

    private JSONObject mapDetail(Candidate candidate, String apiType, String type,
            Options options, String today) {
        JSONObject detail;
        try {
            detail = fetchObject("/" + apiType + "/" + candidate.tmdbId,
                detailParams(apiType), DETAIL_TTL_MS, true, false);
        } catch (Exception failure) {
            return null;
        }
        if (detail == null) return null;
        try {
            if (detail.optBoolean("adult", false) || detail.optBoolean("softcore", false)) return null;

            String date = first(detail.optString("release_date", ""),
                detail.optString("first_air_date", ""), candidate.date);
            if (!releasedOnOrBefore(date, today)) return null;

            String imdb = detailImdb(detail);
            if (imdb == null) return null;

            String poster = imagePath(detail.optString("poster_path", ""), candidate.posterPath);
            String backdrop = imagePath(detail.optString("backdrop_path", ""), candidate.backdropPath);
            if (poster == null || backdrop == null) return null;

            String name = first(detail.optString("title", ""), detail.optString("name", ""),
                candidate.title);
            if (name == null) return null;

            List<Integer> genreIds = new ArrayList<Integer>();
            List<String> genreNames = new ArrayList<String>();
            JSONArray genres = detail.optJSONArray("genres");
            if (genres != null) {
                for (int index = 0; index < genres.length(); index++) {
                    JSONObject genre = genres.optJSONObject(index);
                    if (genre == null) continue;
                    int id = genre.optInt("id", -1);
                    if (id > 0) genreIds.add(id);
                    String genreName = first(genre.optString("name", ""));
                    if (genreName != null) genreNames.add(genreName);
                }
            }
            if (genreIds.isEmpty()) genreIds.addAll(candidate.genreIds);
            if (options.family && genreIds.isEmpty()) return null;

            int votes = Math.max(candidate.voteCount, detail.optInt("vote_count", 0));
            double rating = detail.has("vote_average")
                ? detail.optDouble("vote_average", candidate.voteAverage) : candidate.voteAverage;
            String language = first(detail.optString("original_language", ""),
                candidate.originalLanguage);
            if (!acceptResolved(genreIds, votes, rating, language, date, options)) return null;

            if ("movie".equals(type)) {
                if (!movieReleaseEligible(date, detail, today)) return null;
            } else if (!seriesEligible(detail, options)) {
                return null;
            }

            int runtime = "movie".equals(type)
                ? detail.optInt("runtime", 0) : seriesRuntime(detail);
            Integer maxRuntime = options.maxRuntime;
            if ("movie".equals(type) && options.maxTotalRuntime != null
                    && (maxRuntime == null || options.maxTotalRuntime < maxRuntime)) {
                maxRuntime = options.maxTotalRuntime;
            }
            if (runtime > 0 && !options.allowShorts) {
                if ("movie".equals(type) && runtime < SHORT_MOVIE_RUNTIME) return null;
                if ("series".equals(type) && runtime < SHORT_SERIES_RUNTIME) return null;
            }
            if (runtime > 0 && options.minRuntime != null && runtime < options.minRuntime) return null;
            if (runtime > 0 && maxRuntime != null && runtime > maxRuntime) return null;

            JSONArray links = buildLinks(genreNames, detail);
            String description = first(detail.optString("overview", ""), candidate.overview);
            if (description == null) description = "";
            if (description.length() > 4000) description = description.substring(0, 4000);

            JSONObject item = new JSONObject();
            item.put("id", imdb);
            item.put("type", type);
            item.put("name", truncate(name, 300));
            item.put("poster", IMAGE_ROOT + "w500" + poster);
            item.put("background", IMAGE_ROOT + "w1280" + backdrop);
            String logo = logoUrl(detail);
            if (logo != null) item.put("logo", logo);
            item.put("description", description);
            item.put("releaseInfo", date.substring(0, 4));
            if (runtime > 0) item.put("runtime", runtime + " min");
            if (links.length() > 0) item.put("links", links);
            item.put("_tmdbId", detail.optInt("id", candidate.tmdbId));
            item.put("_tmdbRating", rating);
            item.put("_genreIds", intArray(genreIds));
            return item;
        } catch (JSONException invalid) {
            return null;
        }
    }

    private static boolean acceptResolved(List<Integer> genreIds, int votes, double rating,
            String language, String date, Options options) {
        if (options.minVoteCount > 0 && votes < options.minVoteCount) return false;
        if (options.minVoteAverage > 0 && rating < options.minVoteAverage) return false;
        if (options.originalLanguage != null && language != null
                && !options.originalLanguage.equalsIgnoreCase(language)) return false;
        if (options.releaseGte != null && date != null && date.compareTo(options.releaseGte) < 0) return false;
        if (options.releaseLte != null && date != null && date.compareTo(options.releaseLte) > 0) return false;

        if (options.family && !genreIds.isEmpty()) {
            if (!genreIds.contains(Integer.valueOf(10751))) return false;
            if (genreIds.contains(Integer.valueOf(27))) return false;
        }
        if (!options.withGenres.isEmpty() && !genreIds.isEmpty()
                && !intersects(genreIds, options.withGenres)) return false;
        if (!options.withoutGenres.isEmpty() && !genreIds.isEmpty()
                && intersects(genreIds, options.withoutGenres)) return false;
        return true;
    }

    private static void addCandidates(List<Candidate> candidates, Set<Integer> seenTmdb,
            JSONArray results, String apiType, Options options, String today) {
        for (int index = 0; index < results.length(); index++) {
            JSONObject result = results.optJSONObject(index);
            if (result == null) continue;
            Candidate candidate = Candidate.parse(result, apiType, today);
            if (candidate == null) continue;
            if (!seenTmdb.add(Integer.valueOf(candidate.tmdbId))) continue;
            if (!acceptResolved(candidate.genreIds, candidate.voteCount,
                    candidate.voteAverage, candidate.originalLanguage, candidate.date, options)) continue;
            candidates.add(candidate);
        }
    }

    private static boolean seriesEligible(JSONObject detail, Options options) {
        int seasons = detail.optInt("number_of_seasons", -1);
        int episodes = detail.optInt("number_of_episodes", -1);
        if (options.maxSeasons != null && seasons > 0 && seasons > options.maxSeasons) return false;
        if (options.minSeasons != null && seasons > 0 && seasons < options.minSeasons) return false;
        if (options.maxEpisodes != null && episodes > 0 && episodes > options.maxEpisodes) return false;
        if (options.minEpisodes != null && episodes > 0 && episodes < options.minEpisodes) return false;
        if (options.maxTotalRuntime != null) {
            Integer total = totalSeriesRuntime(detail);
            if (total != null && total > options.maxTotalRuntime) return false;
        }
        return true;
    }

    private static boolean movieReleaseEligible(String date, JSONObject detail, String today) {
        long released = dayNumber(date);
        long now = dayNumber(today);
        if (released < 0 || now < 0 || released > now) return false;
        long age = now - released;
        if (age > RECENT_RELEASE_DAYS) return true;

        ReleaseScan scan = scanReleaseDates(detail, now);
        if (scan.hasHome) return true;
        if (scan.oldPublic) return true;
        if (!scan.recognized) return age >= PUBLIC_RELEASE_DAYS;
        return false;
    }

    private static ReleaseScan scanReleaseDates(JSONObject detail, long today) {
        ReleaseScan scan = new ReleaseScan();
        JSONObject releaseDates = detail.optJSONObject("release_dates");
        JSONArray results = releaseDates == null ? null : releaseDates.optJSONArray("results");
        if (results == null) return scan;
        long monthAgo = today - PUBLIC_RELEASE_DAYS;
        for (int index = 0; index < results.length(); index++) {
            JSONObject country = results.optJSONObject(index);
            if (country == null) continue;
            String iso = country.optString("iso_3166_1", "");
            if (!"US".equalsIgnoreCase(iso) && !"CA".equalsIgnoreCase(iso)) continue;
            JSONArray dates = country.optJSONArray("release_dates");
            if (dates == null) continue;
            for (int entry = 0; entry < dates.length(); entry++) {
                JSONObject release = dates.optJSONObject(entry);
                if (release == null) continue;
                String value = release.optString("release_date", "");
                if (value.length() < 10) continue;
                long day = dayNumber(value.substring(0, 10));
                if (day < 0) continue;
                int type = release.optInt("type", 0);
                if (type < 1 || type > 6) continue;
                scan.recognized = true;
                if (day > today) continue;
                if (type == 4 || type == 5 || type == 6) scan.hasHome = true;
                if ((type == 2 || type == 3) && day <= monthAgo) scan.oldPublic = true;
            }
        }
        return scan;
    }

    private static JSONArray buildLinks(List<String> genreNames, JSONObject detail) {
        JSONArray links = new JSONArray();
        Set<String> seen = new HashSet<String>();
        int genreCount = 0;
        for (String genre : genreNames) {
            if (genreCount >= MAX_GENRE_LINKS) break;
            if (!seen.add("g:" + genre)) continue;
            links.put(link(genre, "Genres"));
            genreCount++;
        }
        JSONObject credits = detail.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast != null) {
            int castCount = 0;
            for (int index = 0; index < cast.length() && castCount < MAX_CAST_LINKS; index++) {
                JSONObject person = cast.optJSONObject(index);
                if (person == null) continue;
                String name = first(person.optString("name", ""));
                if (name == null || name.length() > 120 || !seen.add("c:" + name)) continue;
                links.put(link(name, "Cast"));
                castCount++;
            }
        }
        return links;
    }

    private static JSONObject link(String name, String category) {
        JSONObject link = new JSONObject();
        try {
            link.put("name", name);
            link.put("category", category);
            link.put("url", "stremio:///search?search=" + encode(name));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
        return link;
    }

    private static JSONArray intArray(List<Integer> values) {
        JSONArray array = new JSONArray();
        for (Integer value : values) {
            if (value != null) array.put(value.intValue());
        }
        return array;
    }

    private static String logoUrl(JSONObject detail) {
        JSONObject images = detail.optJSONObject("images");
        JSONArray logos = images == null ? null : images.optJSONArray("logos");
        if (logos == null) return null;
        String best = null;
        double bestVote = -1;
        int bestWidth = -1;
        for (int index = 0; index < logos.length(); index++) {
            JSONObject logo = logos.optJSONObject(index);
            if (logo == null) continue;
            if (!"en".equalsIgnoreCase(logo.optString("iso_639_1", ""))) continue;
            String path = imagePath(logo.optString("file_path", ""), null);
            if (path == null || !path.toLowerCase(Locale.US).endsWith(".png")) continue;
            double vote = logo.optDouble("vote_average", 0);
            int width = logo.optInt("width", 0);
            if (vote > bestVote || (vote == bestVote && width > bestWidth)) {
                best = path;
                bestVote = vote;
                bestWidth = width;
            }
        }
        return best == null ? null : IMAGE_ROOT + "w500" + best;
    }

    private static int seriesRuntime(JSONObject detail) {
        int runtime = firstRuntime(detail.optJSONArray("episode_run_time"));
        if (runtime <= 0) {
            JSONObject last = detail.optJSONObject("last_episode_to_air");
            if (last != null) runtime = last.optInt("runtime", 0);
        }
        return runtime > 0 ? runtime : 0;
    }

    private static Integer totalSeriesRuntime(JSONObject detail) {
        int runtime = firstRuntime(detail.optJSONArray("episode_run_time"));
        if (runtime <= 0) {
            JSONObject last = detail.optJSONObject("last_episode_to_air");
            if (last != null) runtime = last.optInt("runtime", 0);
        }
        if (runtime <= 0) return null;
        int episodes = detail.optInt("number_of_episodes", 0);
        return Integer.valueOf(episodes > 0 ? runtime * episodes : runtime);
    }

    private static int firstRuntime(JSONArray values) {
        if (values == null) return 0;
        int runtime = 0;
        for (int index = 0; index < values.length(); index++) {
            int value = values.optInt(index, 0);
            if (value > runtime) runtime = value;
        }
        return runtime;
    }

    private static Map<String, String> detailParams(String apiType) {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("append_to_response", "movie".equals(apiType)
            ? "credits,external_ids,images,release_dates" : "credits,external_ids,images");
        params.put("include_image_language", "en,null");
        params.put("language", LANGUAGE);
        return params;
    }

    private static Map<String, String> withPage(Map<String, String> params, int page) {
        Map<String, String> copy = new TreeMap<String, String>(params);
        copy.put("page", String.valueOf(page));
        return copy;
    }

    private static Map<String, String> requestParams(JSONObject query) {
        Map<String, String> params = new TreeMap<String, String>();
        if (query == null) return params;
        Iterator<String> keys = query.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null) continue;
            if (key.startsWith("_")) continue;
            if (!key.matches("[A-Za-z0-9_.-]+")) continue;
            if ("api_key".equals(key) || "session_id".equals(key)
                    || "guest_session_id".equals(key) || "page".equals(key)
                    || "language".equals(key) || "include_adult".equals(key)
                    || "append_to_response".equals(key)) continue;
            String value = scalar(query.opt(key));
            if (value != null) params.put(key, value);
        }
        return params;
    }

    private static String scalar(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder joined = new StringBuilder();
            for (int index = 0; index < array.length(); index++) {
                String part = scalar(array.opt(index));
                if (part == null) continue;
                if (joined.length() > 0) joined.append(',');
                joined.append(part);
            }
            return joined.length() == 0 ? null : joined.toString();
        }
        return null;
    }

    private static String normalizePath(String raw) {
        if (raw == null) return null;
        String path = raw.trim();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static void requirePath(String path, String type) {
        boolean valid;
        if ("/trending/movie/week".equals(path)) {
            valid = "movie".equals(type);
        } else if ("/trending/tv/week".equals(path)) {
            valid = "series".equals(type);
        } else if ("/discover/movie".equals(path)) {
            valid = "movie".equals(type);
        } else if ("/discover/tv".equals(path)) {
            valid = "series".equals(type);
        } else {
            valid = false;
        }
        if (!valid) throw new IllegalArgumentException("Discovery row path is not supported.");
    }

    private static String normalizeType(String value) {
        if (value == null) return null;
        String type = value.trim().toLowerCase(Locale.US);
        if ("movie".equals(type)) return "movie";
        if ("series".equals(type) || "tv".equals(type)) return "series";
        return null;
    }

    private int startPage(boolean rotate, int plannedPage, String rowId, String rowTitle) {
        if (plannedPage >= 1) return Math.min(plannedPage, MAX_START_PAGE);
        if (!rotate) return 1;
        String seed = (rowId == null ? "" : rowId) + ":" + (rowTitle == null ? "" : rowTitle)
            + ":" + (clock.now() / 86400000L);
        return Math.floorMod(seed.hashCode(), MAX_PAGES) + 1;
    }

    private FindHit find(String imdb, String type) throws IOException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("external_source", "imdb_id");
        params.put("language", LANGUAGE);
        JSONObject response = fetchObject("/find/" + imdb, params, DETAIL_TTL_MS, true, true);
        return response == null ? null : pickFind(response, type);
    }

    private static FindHit pickFind(JSONObject response, String type) {
        if (!"series".equals(type)) {
            FindHit movie = firstFind(response.optJSONArray("movie_results"), "movie");
            if (movie != null) return movie;
        }
        if (!"movie".equals(type)) {
            FindHit series = firstFind(response.optJSONArray("tv_results"), "series");
            if (series != null) return series;
        }
        return null;
    }

    private static FindHit firstFind(JSONArray results, String type) {
        if (results == null) return null;
        for (int index = 0; index < results.length(); index++) {
            JSONObject item = results.optJSONObject(index);
            if (item == null) continue;
            if (item.optBoolean("adult", false) || item.optBoolean("softcore", false)) continue;
            if (item.optInt("id", -1) <= 0) continue;
            return new FindHit(item, type);
        }
        return null;
    }

    private static String detailImdb(JSONObject detail) {
        JSONObject external = detail.optJSONObject("external_ids");
        String value = external == null ? "" : external.optString("imdb_id", "");
        if (first(value) == null) value = detail.optString("imdb_id", "");
        return normalizeImdbId(value);
    }

    private JSONObject fetchObject(String path, Map<String, String> params, long ttl,
            boolean allowStale, boolean throwOnFailure) throws IOException {
        String body = fetchBody(path, params, ttl, allowStale, throwOnFailure);
        if (body == null) return null;
        try {
            return new JSONObject(body);
        } catch (JSONException invalid) {
            if (throwOnFailure) throw new IOException("TMDB response was invalid.");
            return null;
        }
    }

    private String fetchBody(String path, Map<String, String> params, long ttl,
            boolean allowStale, boolean throwOnFailure) throws IOException {
        String logical = logical(path, params);
        String key = sha256(logical);
        CacheEntry cached = readCache(key);
        long now = clock.now();
        if (cached != null && now - cached.savedAt <= ttl) return cached.body;

        try {
            if (now < throttleUntil) throw new IOException();
            RemoteResponse response = transport.get(requestUrl(logical));
            if (response == null) throw new IOException();
            if (response.status == 200 && response.body != null && isJsonObject(response.body)) {
                saveCache(key, response.body, now);
                return response.body;
            }
            if (response.status == 429) {
                long delay = retryDelay(response.retryAfter);
                throttleUntil = Math.max(throttleUntil, now + delay);
            }
            throw new IOException();
        } catch (Exception failure) {
            if (cached != null && allowStale) return cached.body;
            if (throwOnFailure) throw new IOException("TMDB request failed.");
            return null;
        }
    }

    private String requestUrl(String logical) {
        char separator = logical.indexOf('?') >= 0 ? '&' : '?';
        return API_ROOT + logical + separator + "api_key=" + encode(apiKey);
    }

    private static String logical(String path, Map<String, String> params) {
        StringBuilder out = new StringBuilder(path);
        char separator = '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.append(separator).append(entry.getKey()).append('=').append(encode(entry.getValue()));
            separator = '&';
        }
        return out.toString();
    }

    private static long retryDelay(String retryAfter) {
        long delay = 5000L;
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) delay = seconds * 1000L;
            } catch (NumberFormatException ignored) { }
        }
        if (delay < 1000L) delay = 1000L;
        if (delay > 60000L) delay = 60000L;
        return delay;
    }

    private CacheEntry readCache(String key) {
        CacheEntry entry;
        synchronized (cacheLock) {
            entry = memory.get(key);
        }
        if (entry != null) return entry;
        File directory = cacheDir;
        if (directory == null) return null;
        File file = new File(directory, key + ".json");
        if (!file.isFile() || file.length() > MAX_CACHE_FILE_BYTES) return null;
        try {
            String raw = readFile(file, MAX_CACHE_FILE_BYTES);
            JSONObject wrapper = new JSONObject(raw);
            String body = wrapper.optString("body", "");
            long savedAt = wrapper.optLong("savedAt", 0);
            if (body.isEmpty() || savedAt <= 0 || !isJsonObject(body)) return null;
            entry = new CacheEntry(body, savedAt);
            synchronized (cacheLock) {
                memory.put(key, entry);
            }
            return entry;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveCache(String key, String body, long savedAt) {
        CacheEntry entry = new CacheEntry(body, savedAt);
        synchronized (cacheLock) {
            memory.put(key, entry);
        }
        File directory = cacheDir;
        if (directory == null) return;
        boolean prune;
        synchronized (cacheLock) {
            cacheWrites++;
            prune = cacheWrites % PRUNE_EVERY_WRITES == 0;
        }
        File temporary = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            JSONObject wrapper = new JSONObject();
            wrapper.put("v", 1);
            wrapper.put("savedAt", savedAt);
            wrapper.put("body", body);
            byte[] bytes = wrapper.toString().getBytes("UTF-8");
            temporary = File.createTempFile("tmdb-", ".tmp", directory);
            FileOutputStream stream = new FileOutputStream(temporary);
            try {
                stream.write(bytes);
                stream.getFD().sync();
            } finally {
                stream.close();
            }
            File target = new File(directory, key + ".json");
            if (!temporary.renameTo(target)) {
                if (target.exists()) target.delete();
                if (!temporary.renameTo(target)) temporary.delete();
            }
            temporary = null;
        } catch (Exception ignored) {
            if (temporary != null) temporary.delete();
        }
        if (prune) pruneDiskCache(directory, MAX_DISK_ENTRIES, MAX_DISK_BYTES);
    }

    static void pruneDiskCache(File directory, int maxEntries, long maxBytes) {
        if (directory == null) return;
        File[] listed = directory.listFiles();
        if (listed == null || listed.length == 0) return;
        List<File> cacheFiles = new ArrayList<File>();
        long total = 0L;
        for (File file : listed) {
            if (file == null || !file.isFile()) continue;
            if (!file.getName().matches("[0-9a-f]{64}\\.json")) continue;
            cacheFiles.add(file);
            total += file.length();
        }
        if (cacheFiles.size() <= maxEntries && total <= maxBytes) return;
        Collections.sort(cacheFiles, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                long leftTime = left.lastModified();
                long rightTime = right.lastModified();
                if (leftTime < rightTime) return -1;
                if (leftTime > rightTime) return 1;
                return left.getName().compareTo(right.getName());
            }
        });
        int entries = cacheFiles.size();
        for (File file : cacheFiles) {
            if (entries <= maxEntries && total <= maxBytes) break;
            long size = file.length();
            if (file.delete()) {
                entries--;
                total -= size;
            }
        }
    }

    private static String readFile(File file, int limit) throws IOException {
        InputStream stream = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IOException();
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        } finally {
            stream.close();
        }
    }

    private static boolean isJsonObject(String body) {
        try {
            new JSONObject(body);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) out.append(String.format(Locale.US, "%02x", item & 255));
            return out.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            return "";
        }
    }

    private static String imagePath(String preferred, String fallback) {
        String path = first(preferred, fallback);
        if (path == null || path.startsWith("http://") || path.startsWith("https://")) return null;
        if (!path.startsWith("/")) path = "/" + path;
        if (path.contains("..") || path.contains("?") || path.contains("#")) return null;
        return path;
    }

    private static boolean releasedOnOrBefore(String date, String today) {
        if (!isDate(date) || !isDate(today)) return false;
        return date.compareTo(today) <= 0;
    }

    private static boolean isDate(String value) {
        return value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    }

    private static long dayNumber(String value) {
        if (!isDate(value)) return -1;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(value);
            return date == null ? -1 : date.getTime() / 86400000L;
        } catch (Exception invalid) {
            return -1;
        }
    }

    private String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(clock.now()));
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value == null) continue;
            String text = value.trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) return text;
        }
        return null;
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static boolean intersects(List<Integer> values, Set<Integer> filter) {
        for (Integer value : values) {
            if (value != null && filter.contains(value)) return true;
        }
        return false;
    }

    interface Clock { long now(); }

    interface Transport { RemoteResponse get(String url) throws IOException; }

    static final class RemoteResponse {
        final int status;
        final String body;
        final String retryAfter;

        RemoteResponse(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private static final class CacheEntry {
        final String body;
        final long savedAt;

        CacheEntry(String body, long savedAt) {
            this.body = body;
            this.savedAt = savedAt;
        }
    }

    private static final class FindHit {
        final JSONObject item;
        final String type;

        FindHit(JSONObject item, String type) {
            this.item = item;
            this.type = type;
        }
    }

    private static final class ReleaseScan {
        boolean recognized;
        boolean hasHome;
        boolean oldPublic;
    }

    private static final class Candidate {
        final int tmdbId;
        final String title;
        final String date;
        final String overview;
        final String posterPath;
        final String backdropPath;
        final String originalLanguage;
        final int voteCount;
        final double voteAverage;
        final List<Integer> genreIds;
        final JSONObject raw;

        Candidate(int tmdbId, String title, String date, String overview, String posterPath,
                String backdropPath, String originalLanguage, int voteCount, double voteAverage,
                List<Integer> genreIds, JSONObject raw) {
            this.tmdbId = tmdbId;
            this.title = title;
            this.date = date;
            this.overview = overview;
            this.posterPath = posterPath;
            this.backdropPath = backdropPath;
            this.originalLanguage = originalLanguage;
            this.voteCount = voteCount;
            this.voteAverage = voteAverage;
            this.genreIds = genreIds;
            this.raw = raw;
        }

        static Candidate known(int tmdbId) {
            return new Candidate(tmdbId, "", "", "", null, null, "", 0, 0.0,
                new ArrayList<Integer>(), new JSONObject());
        }

        static Candidate parse(JSONObject result, String apiType, String today) {
            if (result == null) return null;
            String mediaType = result.optString("media_type", "");
            if (first(mediaType) != null && !apiType.equals(mediaType)) return null;
            if (result.optBoolean("adult", false) || result.optBoolean("softcore", false)) return null;
            int id = result.optInt("id", -1);
            if (id <= 0) return null;
            String title = first(result.optString("title", ""), result.optString("name", ""),
                result.optString("original_title", ""), result.optString("original_name", ""));
            if (title == null) return null;
            String date = first(result.optString("release_date", ""),
                result.optString("first_air_date", ""));
            if (!releasedOnOrBefore(date, today)) return null;
            String poster = imagePath(result.optString("poster_path", ""), null);
            String backdrop = imagePath(result.optString("backdrop_path", ""), null);
            if (poster == null || backdrop == null) return null;
            List<Integer> genres = new ArrayList<Integer>();
            JSONArray ids = result.optJSONArray("genre_ids");
            if (ids != null) {
                for (int index = 0; index < ids.length(); index++) {
                    int genre = ids.optInt(index, -1);
                    if (genre > 0) genres.add(Integer.valueOf(genre));
                }
            }
            return new Candidate(id, title, date, result.optString("overview", ""),
                poster, backdrop, result.optString("original_language", ""),
                result.optInt("vote_count", 0), result.optDouble("vote_average", 0), genres, result);
        }
    }

    private static final class Options {
        boolean family;
        boolean allowShorts;
        int minVoteCount;
        double minVoteAverage;
        Integer maxTotalRuntime;
        Integer maxSeasons;
        Integer minSeasons;
        Integer maxEpisodes;
        Integer minEpisodes;
        Integer minRuntime;
        Integer maxRuntime;
        Set<Integer> withGenres = new HashSet<Integer>();
        Set<Integer> withoutGenres = new HashSet<Integer>();
        String originalLanguage;
        String releaseGte;
        String releaseLte;

        static Options parse(JSONObject query, String rowId, String rowTitle) {
            Options options = new Options();
            JSONObject source = query == null ? new JSONObject() : query;
            options.family = truthy(source.opt("_family"));
            options.maxTotalRuntime = positiveInteger(source.opt("_maxTotalRuntime"));
            options.maxSeasons = positiveInteger(source.opt("_maxSeasons"));
            options.minSeasons = positiveInteger(source.opt("_minSeasons"));
            options.maxEpisodes = positiveInteger(source.opt("_maxEpisodes"));
            options.minEpisodes = positiveInteger(source.opt("_minEpisodes"));
            options.minRuntime = positiveInteger(source.opt("_minRuntime"));
            options.maxRuntime = positiveInteger(source.opt("_maxRuntime"));
            options.allowShorts = truthy(source.opt("_shorts"))
                || truthy(source.opt("_allowShorts"));
            options.minVoteCount = Math.max(nonNegativeInteger(source.opt("vote_count.gte")),
                nonNegativeInteger(source.opt("_minVoteCount")));
            options.minVoteAverage = Math.max(nonNegativeDouble(source.opt("vote_average.gte")),
                nonNegativeDouble(source.opt("_minVoteAverage")));
            options.withGenres = idSet(source.opt("with_genres"));
            options.withoutGenres = idSet(source.opt("without_genres"));
            options.originalLanguage = first(source.optString("with_original_language", ""));

            String gte = first(source.optString("primary_release_date.gte", ""),
                source.optString("first_air_date.gte", ""),
                source.optString("release_date.gte", ""));
            String lte = first(source.optString("primary_release_date.lte", ""),
                source.optString("first_air_date.lte", ""),
                source.optString("release_date.lte", ""));
            options.releaseGte = isDate(gte) ? gte : null;
            options.releaseLte = isDate(lte) ? lte : null;

            int runtimeGte = nonNegativeInteger(source.opt("with_runtime.gte"));
            int runtimeLte = nonNegativeInteger(source.opt("with_runtime.lte"));
            if (runtimeGte > 0) options.minRuntime = options.minRuntime == null
                ? Integer.valueOf(runtimeGte) : Integer.valueOf(Math.max(options.minRuntime, runtimeGte));
            if (runtimeLte > 0) options.maxRuntime = options.maxRuntime == null
                ? Integer.valueOf(runtimeLte) : Integer.valueOf(Math.min(options.maxRuntime, runtimeLte));

            if (options.withGenres.size() == 1 && options.withGenres.contains(Integer.valueOf(10751))) {
                options.family = true;
            }
            if (!options.family && hintsFamily(rowId, rowTitle)
                    && (options.withGenres.isEmpty()
                        || options.withGenres.contains(Integer.valueOf(10751)))) {
                options.family = true;
            }
            return options;
        }

        private static boolean hintsFamily(String rowId, String rowTitle) {
            String id = rowId == null ? "" : rowId.toLowerCase(Locale.US);
            String title = rowTitle == null ? "" : rowTitle.toLowerCase(Locale.US);
            return id.contains("family") || title.contains("family");
        }
    }

    private static boolean truthy(Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        String text = String.valueOf(value).trim().toLowerCase(Locale.US);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private static Integer positiveInteger(Object value) {
        int number = nonNegativeInteger(value);
        return number > 0 ? Integer.valueOf(number) : null;
    }

    private static int nonNegativeInteger(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        if (value instanceof Number) {
            int number = ((Number) value).intValue();
            return number > 0 ? number : 0;
        }
        try {
            int number = Integer.parseInt(String.valueOf(value).trim());
            return number > 0 ? number : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double nonNegativeDouble(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return number > 0 ? number : 0;
        }
        try {
            double number = Double.parseDouble(String.valueOf(value).trim());
            return number > 0 ? number : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Set<Integer> idSet(Object value) {
        Set<Integer> ids = new HashSet<Integer>();
        if (value == null || value == JSONObject.NULL) return ids;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                int id = nonNegativeInteger(array.opt(index));
                if (id > 0) ids.add(Integer.valueOf(id));
            }
            return ids;
        }
        String text = String.valueOf(value);
        for (String part : text.split("[,|]")) {
            int id = nonNegativeInteger(part);
            if (id > 0) ids.add(Integer.valueOf(id));
        }
        return ids;
    }

    private static final class HttpTransport implements Transport {
        @Override public RemoteResponse get(String address) throws IOException {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(address);
                if (!"https".equals(url.getProtocol())
                        || !"api.themoviedb.org".equals(url.getHost())
                        || (url.getPort() != -1 && url.getPort() != 443)
                        || url.getUserInfo() != null) {
                    throw new IOException();
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
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
                String body = "";
                if (status == 200) {
                    InputStream stream = null;
                    try {
                        stream = connection.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = stream.read(buffer)) != -1) {
                            if (out.size() + count > MAX_BODY_BYTES) throw new IOException();
                            out.write(buffer, 0, count);
                        }
                        body = out.toString("UTF-8");
                    } finally {
                        if (stream != null) stream.close();
                    }
                } else {
                    drain(connection.getErrorStream());
                }
                return new RemoteResponse(status, body, connection.getHeaderField("Retry-After"));
            } catch (IOException failure) {
                throw new IOException("TMDB request failed.");
            } catch (Exception failure) {
                throw new IOException("TMDB request failed.");
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
