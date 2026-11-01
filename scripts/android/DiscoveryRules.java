package com.jedflix.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class DiscoveryRules {
    public static final int DEFAULT_LIMIT = 48;

    private static final int MAX_ROWS = 14;
    private static final String TYPE_MOVIE = "movie";
    private static final String TYPE_SERIES = "series";
    private static final String PATH_TRENDING_MOVIE = "/trending/movie/week";
    private static final String PATH_MOVIE = "/discover/movie";
    private static final String PATH_TV = "/discover/tv";
    private static final String LANGUAGE = "en-US";

    private static final String ID_TRENDING = "home.trending.movies";
    private static final String ID_MOMENT_DEFAULT = "home.moment.default";
    private static final String ID_MOMENT_FRIDAY = "home.moment.friday";
    private static final String ID_MOMENT_SATURDAY = "home.moment.saturday";
    private static final String ID_MOMENT_SUNDAY = "home.moment.sunday";
    private static final String ID_MOMENT_WEEKNIGHT = "home.moment.weeknight";
    private static final String ID_MOMENT_WEEKEND = "home.moment.weekend";
    private static final String ID_MOMENT_LATENIGHT = "home.moment.latenight";
    private static final String ID_FRESH_DIGITAL = "home.spotlight.newdigital";
    private static final String ID_HIDDEN_GEMS = "home.spotlight.hidden";
    private static final String ID_PRESTIGE_TV = "home.spotlight.prestige";

    private static final String[] WORLD_LANGUAGES = {
        "ko", "fr", "ja", "es", "hi", "it", "da", "sv", "pt", "de", "zh"
    };

    private DiscoveryRules() {}

    public static List<Row> rows(long nowMillis, TimeZone zone, JSONObject signals) {
        TimeZone localZone = zone != null ? zone : TimeZone.getDefault();
        Calendar now = calendar(nowMillis, localZone);
        String today = dateKey(now);
        JSONObject safeSignals = signals != null ? signals : new JSONObject();
        List<Seed> seeds = readSeeds(safeSignals);
        Set<Integer> preferred = readPreferredGenres(safeSignals);

        List<Planned> planned = new ArrayList<>();
        planned.add(new Planned(trendingRow(now), 100));

        List<Planned> seasonal = seasonalRows(now, today);
        for (int i = 0; i < seasonal.size() && i < 2; i++) {
            planned.add(seasonal.get(i));
        }

        planned.add(new Planned(momentRow(now, today), 90));
        addPersonalRows(planned, seeds, now, today);

        for (Spotlight spotlight : orderedSpotlights(preferred)) {
            planned.add(new Planned(spotlightRow(spotlight, now, today), spotlight.keep));
        }

        planned.add(new Planned(freshDigitalRow(now, today), 55));
        planned.add(new Planned(hiddenGemsRow(now, today), 0));
        planned.add(new Planned(prestigeTvRow(now, today), 0));

        while (planned.size() > MAX_ROWS) {
            int index = -1;
            int lowest = Integer.MAX_VALUE;
            for (int i = 0; i < planned.size(); i++) {
                int keep = planned.get(i).keep;
                if (keep <= lowest) {
                    lowest = keep;
                    index = i;
                }
            }
            planned.remove(index);
        }

        List<Row> rows = new ArrayList<>(planned.size());
        for (Planned item : planned) {
            rows.add(item.row);
        }
        return rows;
    }

    public static JSONArray rankCandidates(
            JSONArray candidates, String rowId, long nowMillis, TimeZone zone, int limit) {
        JSONArray result = new JSONArray();
        if (candidates == null || limit <= 0) return result;

        TimeZone localZone = zone != null ? zone : TimeZone.getDefault();
        String day = dateKey(calendar(nowMillis, localZone));
        String saltBase = (rowId != null ? rowId : "row") + "|" + day;
        List<Ranked> ranked = new ArrayList<>();

        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item == null) continue;
            double voteAverage = item.optDouble("vote_average", 0.0);
            int voteCount = item.optInt("vote_count", 0);
            double popularity = item.optDouble("popularity", 0.0);
            double quality = voteAverage * Math.min(1.0, Math.log1p(voteCount) / 7.0);
            double popularityScore = Math.log1p(Math.max(0.0, popularity)) / 10.0;
            String release = item.optString("release_date", "");
            if (release.isEmpty()) release = item.optString("first_air_date", "");
            double freshness = freshness(release, nowMillis);
            double score = quality + (popularityScore * 0.25) + (freshness * 0.35);
            String itemId = item.optString("id", String.valueOf(i));
            long salt = stableHash(saltBase + "|" + itemId);
            ranked.add(new Ranked(item, score, salt, itemId));
        }

        Collections.sort(ranked, new Comparator<Ranked>() {
            @Override
            public int compare(Ranked left, Ranked right) {
                int byScore = Double.compare(right.score, left.score);
                if (byScore != 0) return byScore;
                int bySalt = Long.compare(left.salt, right.salt);
                if (bySalt != 0) return bySalt;
                return left.id.compareTo(right.id);
            }
        });

        int headCount = Math.min(limit, Math.max(1, (limit * 3) / 4));
        for (int i = 0; i < headCount && i < ranked.size(); i++) {
            result.put(ranked.get(i).item);
        }
        if (result.length() < limit && ranked.size() > headCount) {
            List<Ranked> tail = new ArrayList<>(ranked.subList(headCount, ranked.size()));
            Collections.sort(tail, new Comparator<Ranked>() {
                @Override
                public int compare(Ranked left, Ranked right) {
                    int bySalt = Long.compare(left.salt, right.salt);
                    if (bySalt != 0) return bySalt;
                    return left.id.compareTo(right.id);
                }
            });
            for (int i = 0; i < tail.size() && result.length() < limit; i++) {
                result.put(tail.get(i).item);
            }
        }
        return result;
    }

    public static final class Row {
        public final String id;
        public final String title;
        public final String type;
        public final String path;
        public final String seedImdb;
        public final JSONObject query;
        public final int limit;
        public final boolean rotate;

        public Row(
                String id,
                String title,
                String type,
                String path,
                String seedImdb,
                JSONObject query,
                int limit,
                boolean rotate) {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("id");
            if (title == null || title.isEmpty()) throw new IllegalArgumentException("title");
            if (!TYPE_MOVIE.equals(type) && !TYPE_SERIES.equals(type)) {
                throw new IllegalArgumentException("type");
            }
            if (path == null || path.isEmpty()) throw new IllegalArgumentException("path");
            this.id = id;
            this.title = title;
            this.type = type;
            this.path = path;
            this.seedImdb = seedImdb == null || seedImdb.isEmpty() ? null : seedImdb;
            this.query = copy(query);
            this.limit = limit > 0 ? limit : DEFAULT_LIMIT;
            this.rotate = rotate;
        }

        public Row(
                String id,
                String title,
                String type,
                String path,
                JSONObject query,
                int limit,
                boolean rotate) {
            this(id, title, type, path, null, query, limit, rotate);
        }
    }

    private static final class Planned {
        final Row row;
        final int keep;

        Planned(Row row, int keep) {
            this.row = row;
            this.keep = keep;
        }
    }

    private static final class Seed {
        final String id;
        final String type;
        final String name;
        final boolean watched;

        Seed(String id, String type, String name, boolean watched) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.watched = watched;
        }
    }

    private static final class Ranked {
        final JSONObject item;
        final double score;
        final long salt;
        final String id;

        Ranked(JSONObject item, double score, long salt, String id) {
            this.item = item;
            this.score = score;
            this.salt = salt;
            this.id = id;
        }
    }

    private static final class Spotlight {
        final String id;
        final String title;
        final String type;
        final String path;
        final String genres;
        final int[] seedGenres;
        final int minVotes;
        final double minRating;
        final String sort;
        final int pages;
        final int keep;

        Spotlight(
                String id,
                String title,
                String type,
                String path,
                String genres,
                int[] seedGenres,
                int minVotes,
                double minRating,
                String sort,
                int pages,
                int keep) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.path = path;
            this.genres = genres;
            this.seedGenres = seedGenres;
            this.minVotes = minVotes;
            this.minRating = minRating;
            this.sort = sort;
            this.pages = pages;
            this.keep = keep;
        }
    }

    private static Row trendingRow(Calendar now) {
        JSONObject query = new JSONObject();
        put(query, "language", LANGUAGE);
        put(query, "page", dailyNumber(ID_TRENDING, now, 3));
        return new Row(
                ID_TRENDING,
                "Trending Now",
                TYPE_MOVIE,
                PATH_TRENDING_MOVIE,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static List<Planned> seasonalRows(Calendar now, String today) {
        List<Planned> rows = new ArrayList<>();
        if (isCanadianThanksgiving(now)) {
            JSONObject query = movieQuery(today, "10751", 80, 6.0, "popularity.desc", 1);
            rows.add(new Planned(new Row(
                    "home.seasonal.thanksgiving",
                    "Thanksgiving Together",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        if (isHalloween(now)) {
            JSONObject query = movieQuery(today, "27", 150, 5.8, "popularity.desc", 1);
            rows.add(new Planned(new Row(
                    "home.seasonal.halloween",
                    "Halloween Frights",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        if (isChristmas(now)) {
            JSONObject query = movieQuery(today, null, 120, 6.0, "popularity.desc", 1);
            put(query, "with_keywords", 207317);
            rows.add(new Planned(new Row(
                    "home.seasonal.christmas",
                    "Christmas Movies",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        if (isNewYear(now)) {
            JSONObject query = movieQuery(today, "35|18", 200, 6.5, "popularity.desc", 1);
            rows.add(new Planned(new Row(
                    "home.seasonal.newyear",
                    "New Year, Fresh Start",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        if (isValentine(now)) {
            JSONObject query = movieQuery(today, "10749", 150, 6.3, "popularity.desc", 1);
            rows.add(new Planned(new Row(
                    "home.seasonal.valentine",
                    "Valentine's Movie Night",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        if (isSummer(now)) {
            JSONObject query = movieQuery(today, "12|10751", 200, 6.2, "popularity.desc", 1);
            rows.add(new Planned(new Row(
                    "home.seasonal.summer",
                    "Summer Adventures",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        if (isWinter(now)) {
            JSONObject query = movieQuery(today, "18|35", 200, 6.5, "popularity.desc", 1);
            rows.add(new Planned(new Row(
                    "home.seasonal.winter",
                    "Cozy Winter Nights",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    false), 95));
        }
        return rows;
    }

    private static Row momentRow(Calendar now, String today) {
        int dayOfWeek = now.get(Calendar.DAY_OF_WEEK);
        int hour = now.get(Calendar.HOUR_OF_DAY);

        if (hour >= 22 || hour < 3) {
            JSONObject query = movieQuery(today, "53|9648|80", 200, 6.4, "popularity.desc",
                    dailyNumber(ID_MOMENT_LATENIGHT, now, 3));
            return new Row(
                    ID_MOMENT_LATENIGHT,
                    "Late-Night Thrillers",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    true);
        }
        if (dayOfWeek == Calendar.FRIDAY && hour >= 17) {
            JSONObject query = movieQuery(today, "28|12|35|878|14", 300, 6.3, "popularity.desc",
                    dailyNumber(ID_MOMENT_FRIDAY, now, 3));
            return new Row(
                    ID_MOMENT_FRIDAY,
                    "Friday Night Movie Night",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    true);
        }
        if (dayOfWeek == Calendar.SATURDAY && hour >= 12) {
            JSONObject query = movieQuery(today, "28|12|35|53|878", 300, 6.4, "popularity.desc",
                    dailyNumber(ID_MOMENT_SATURDAY, now, 3));
            return new Row(
                    ID_MOMENT_SATURDAY,
                    "Saturday Double Feature",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    true);
        }
        if (dayOfWeek == Calendar.SUNDAY && hour >= 16) {
            JSONObject query = comfortMovieQuery(now, today, ID_MOMENT_SUNDAY);
            return new Row(
                    ID_MOMENT_SUNDAY,
                    "Sunday Comfort Watches",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    true);
        }
        if (dayOfWeek >= Calendar.MONDAY && dayOfWeek <= Calendar.THURSDAY && hour >= 17) {
            JSONObject query = comfortMovieQuery(now, today, ID_MOMENT_WEEKNIGHT);
            return new Row(
                    ID_MOMENT_WEEKNIGHT,
                    "After a Long Day",
                    TYPE_MOVIE,
                    PATH_MOVIE,
                    query,
                    DEFAULT_LIMIT,
                    true);
        }
        if ((dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
                && hour >= 9 && hour < 17) {
            JSONObject query = seriesQuery(today, "18|35|9648", 100, 6.8, "popularity.desc",
                    dailyNumber(ID_MOMENT_WEEKEND, now, 3));
            return new Row(
                    ID_MOMENT_WEEKEND,
                    "Weekend Binge",
                    TYPE_SERIES,
                    PATH_TV,
                    query,
                    DEFAULT_LIMIT,
                    true);
        }

        JSONObject query = movieQuery(today, "35|18|53|878", 500, 7.0, "popularity.desc",
                dailyNumber(ID_MOMENT_DEFAULT, now, 3));
        return new Row(
                ID_MOMENT_DEFAULT,
                "Tonight's Pick",
                TYPE_MOVIE,
                PATH_MOVIE,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static JSONObject comfortMovieQuery(Calendar now, String today, String id) {
        JSONObject query = movieQuery(today, "35|10751|10749", 200, 6.5, "popularity.desc",
                dailyNumber(id, now, 3));
        put(query, "without_genres", "27|53|80|10752");
        return query;
    }

    private static void addPersonalRows(
            List<Planned> planned, List<Seed> seeds, Calendar now, String today) {
        if (seeds.isEmpty()) return;

        Seed first = seeds.get(0);
        planned.add(new Planned(personalRow(
                "home.personal." + first.id,
                personalTitle(first.watched ? "Because You Watched " : "More Like ", first.name),
                first,
                now,
                today), 85));

        if (seeds.size() < 2) return;

        Seed second = seeds.get(1);
        planned.add(new Planned(personalRow(
                "home.personal.more." + second.id,
                personalTitle("More Like ", second.name),
                second,
                now,
                today), 85));
    }

    private static Row personalRow(
            String id, String title, Seed seed, Calendar now, String today) {
        boolean movie = TYPE_MOVIE.equals(seed.type);
        JSONObject query = movie
                ? movieQuery(today, null, 40, 6.5, "popularity.desc", dailyNumber(id, now, 2))
                : seriesQuery(today, null, 30, 6.5, "popularity.desc", dailyNumber(id, now, 2));
        return new Row(
                id,
                title,
                seed.type,
                movie ? PATH_MOVIE : PATH_TV,
                seed.id,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static String personalTitle(String prefix, String name) {
        String trimmed = name != null ? name.trim() : "";
        if (trimmed.isEmpty()) return prefix.trim();
        String shortName = trimmed.length() > 40 ? trimmed.substring(0, 37) + "..." : trimmed;
        return prefix + shortName;
    }

    private static List<Spotlight> orderedSpotlights(Set<Integer> preferred) {
        List<Spotlight> spotlights = new ArrayList<>();
        spotlights.add(new Spotlight(
                "home.spotlight.family", "Family Movie Night", TYPE_MOVIE, PATH_MOVIE, "10751",
                new int[] {10751}, 150, 6.2, "popularity.desc", 3, 60));
        spotlights.add(new Spotlight(
                "home.spotlight.comedy", "Comedy That Lands", TYPE_MOVIE, PATH_MOVIE, "35",
                new int[] {35}, 250, 6.3, "popularity.desc", 4, 50));
        spotlights.add(new Spotlight(
                "home.spotlight.crime", "Crime Files", TYPE_SERIES, PATH_TV, "80",
                new int[] {80}, 80, 6.8, "popularity.desc", 3, 45));
        spotlights.add(new Spotlight(
                "home.spotlight.scifi", "Sci-Fi Worlds", TYPE_SERIES, PATH_TV, "10765",
                new int[] {878, 10765}, 100, 6.8, "popularity.desc", 3, 45));
        spotlights.add(new Spotlight(
                "home.spotlight.mystery", "Mystery & Suspense", TYPE_MOVIE, PATH_MOVIE, "9648|53",
                new int[] {9648, 53}, 200, 6.5, "popularity.desc", 3, 40));
        spotlights.add(new Spotlight(
                "home.spotlight.fantasy", "Fantasy Escapes", TYPE_MOVIE, PATH_MOVIE, "14|12",
                new int[] {14, 12}, 250, 6.3, "popularity.desc", 3, 30));
        spotlights.add(new Spotlight(
                "home.spotlight.documentary", "True Stories", TYPE_SERIES, PATH_TV, "99",
                new int[] {99}, 30, 6.8, "popularity.desc", 3, 60));
        spotlights.add(new Spotlight(
                "home.spotlight.world", "World Cinema", TYPE_MOVIE, PATH_MOVIE, null,
                new int[0], 80, 6.6, "popularity.desc", 3, 60));
        spotlights.add(new Spotlight(
                "home.spotlight.anime", "Anime Picks", TYPE_SERIES, PATH_TV, "16",
                new int[] {16}, 50, 6.8, "popularity.desc", 3, 60));

        Collections.sort(spotlights, new Comparator<Spotlight>() {
            @Override
            public int compare(Spotlight left, Spotlight right) {
                return Integer.compare(preferenceRank(left, preferred), preferenceRank(right, preferred));
            }
        });
        return spotlights;
    }

    private static int preferenceRank(Spotlight spotlight, Set<Integer> preferred) {
        for (int genre : spotlight.seedGenres) {
            if (preferred.contains(genre)) return 0;
        }
        return 1;
    }

    private static Row spotlightRow(Spotlight spotlight, Calendar now, String today) {
        int page = dailyNumber(spotlight.id, now, spotlight.pages);
        JSONObject query;
        if (TYPE_SERIES.equals(spotlight.type)) {
            query = seriesQuery(
                    today, spotlight.genres, spotlight.minVotes, spotlight.minRating,
                    spotlight.sort, page);
        } else {
            query = movieQuery(
                    today, spotlight.genres, spotlight.minVotes, spotlight.minRating,
                    spotlight.sort, page);
        }
        if ("home.spotlight.family".equals(spotlight.id)) {
            put(query, "certification_country", "US");
            put(query, "certification.lte", "PG");
        }
        if ("home.spotlight.world".equals(spotlight.id)) {
            put(query, "with_original_language", dailyLanguage(spotlight.id, now));
        }
        if ("home.spotlight.anime".equals(spotlight.id)) {
            put(query, "with_original_language", "ja");
        }
        return new Row(
                spotlight.id,
                spotlight.title,
                spotlight.type,
                spotlight.path,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static Row freshDigitalRow(Calendar now, String today) {
        JSONObject query = movieQuery(
                today, null, adaptiveVoteFloor(180), 5.5, "release_date.desc",
                dailyNumber(ID_FRESH_DIGITAL, now, 3));
        put(query, "region", "CA");
        put(query, "with_release_type", "4|5");
        put(query, "release_date.gte", dateKeyOffset(now, -180));
        return new Row(
                ID_FRESH_DIGITAL,
                "Fresh on Digital",
                TYPE_MOVIE,
                PATH_MOVIE,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static Row hiddenGemsRow(Calendar now, String today) {
        JSONObject query = movieQuery(
                today, null, 150, 7.4, "vote_average.desc",
                dailyNumber(ID_HIDDEN_GEMS, now, 3));
        put(query, "vote_count.lte", 1200);
        return new Row(
                ID_HIDDEN_GEMS,
                "Hidden Gems",
                TYPE_MOVIE,
                PATH_MOVIE,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static Row prestigeTvRow(Calendar now, String today) {
        JSONObject query = seriesQuery(
                today, "18", 250, 7.5, "vote_average.desc",
                dailyNumber(ID_PRESTIGE_TV, now, 2));
        return new Row(
                ID_PRESTIGE_TV,
                "Prestige TV",
                TYPE_SERIES,
                PATH_TV,
                query,
                DEFAULT_LIMIT,
                true);
    }

    private static JSONObject movieQuery(
            String today, String genres, int minVotes, double minRating,
            String sort, int page) {
        JSONObject query = baseQuery();
        put(query, "sort_by", sort);
        put(query, "vote_count.gte", minVotes);
        put(query, "vote_average.gte", minRating);
        put(query, "release_date.lte", today);
        if (genres != null) put(query, "with_genres", genres);
        put(query, "page", page);
        return query;
    }

    private static JSONObject seriesQuery(
            String today, String genres, int minVotes, double minRating,
            String sort, int page) {
        JSONObject query = baseQuery();
        put(query, "sort_by", sort);
        put(query, "vote_count.gte", minVotes);
        put(query, "vote_average.gte", minRating);
        put(query, "first_air_date.lte", today);
        if (genres != null) put(query, "with_genres", genres);
        put(query, "page", page);
        return query;
    }

    private static JSONObject baseQuery() {
        JSONObject query = new JSONObject();
        put(query, "include_adult", false);
        put(query, "language", LANGUAGE);
        return query;
    }

    private static List<Seed> readSeeds(JSONObject signals) {
        List<Seed> seeds = new ArrayList<>();
        JSONArray array = signals.optJSONArray("seeds");
        if (array == null) return seeds;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            String type = item.optString("type", "").trim();
            String name = item.optString("name", "");
            if (!id.matches("tt[0-9]+")) continue;
            if (!TYPE_MOVIE.equals(type) && !TYPE_SERIES.equals(type)) continue;
            seeds.add(new Seed(id, type, name, item.optBoolean("watched")));
            if (seeds.size() == 2) break;
        }
        return seeds;
    }

    private static Set<Integer> readPreferredGenres(JSONObject signals) {
        Set<Integer> genres = new HashSet<>();
        JSONArray array = signals.optJSONArray("preferredGenres");
        if (array == null) return genres;
        for (int i = 0; i < array.length(); i++) {
            int genre = array.optInt(i, -1);
            if (genre > 0) genres.add(genre);
        }
        return genres;
    }

    private static boolean isCanadianThanksgiving(Calendar now) {
        if (now.get(Calendar.MONTH) != Calendar.OCTOBER) return false;
        int year = now.get(Calendar.YEAR);
        int secondMonday = secondMondayOfOctober(year);
        int today = dateNumber(now);
        int start = (year * 10000) + 1000 + (secondMonday - 2);
        int end = (year * 10000) + 1000 + secondMonday;
        return today >= start && today <= end;
    }

    private static int secondMondayOfOctober(int year) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        calendar.clear();
        calendar.set(year, Calendar.OCTOBER, 1, 12, 0, 0);
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        int firstMonday = 1 + ((Calendar.MONDAY - dayOfWeek + 7) % 7);
        return firstMonday + 7;
    }

    private static boolean isHalloween(Calendar now) {
        return now.get(Calendar.MONTH) == Calendar.OCTOBER;
    }

    private static boolean isChristmas(Calendar now) {
        int month = now.get(Calendar.MONTH);
        int day = now.get(Calendar.DAY_OF_MONTH);
        return (month == Calendar.NOVEMBER && day >= 20)
                || (month == Calendar.DECEMBER && day <= 25);
    }

    private static boolean isNewYear(Calendar now) {
        int month = now.get(Calendar.MONTH);
        int day = now.get(Calendar.DAY_OF_MONTH);
        return (month == Calendar.DECEMBER && day >= 26)
                || (month == Calendar.JANUARY && day <= 7);
    }

    private static boolean isValentine(Calendar now) {
        return now.get(Calendar.MONTH) == Calendar.FEBRUARY
                && now.get(Calendar.DAY_OF_MONTH) >= 7
                && now.get(Calendar.DAY_OF_MONTH) <= 14;
    }

    private static boolean isSummer(Calendar now) {
        int month = now.get(Calendar.MONTH);
        return month >= Calendar.JUNE && month <= Calendar.AUGUST;
    }

    private static boolean isWinter(Calendar now) {
        int month = now.get(Calendar.MONTH);
        return month == Calendar.DECEMBER
                || month == Calendar.JANUARY
                || month == Calendar.FEBRUARY;
    }

    private static int adaptiveVoteFloor(int releaseWindowDays) {
        if (releaseWindowDays <= 180) return 20;
        if (releaseWindowDays <= 730) return 60;
        return 150;
    }

    private static int dailyNumber(String id, Calendar now, int count) {
        long hash = stableHash(id + "|" + dateKey(now));
        return positiveMod(hash, count) + 1;
    }

    private static String dailyLanguage(String id, Calendar now) {
        long hash = stableHash(id + "|" + dateKey(now) + "|language");
        return WORLD_LANGUAGES[positiveMod(hash, WORLD_LANGUAGES.length)];
    }

    private static int positiveMod(long value, int count) {
        int remainder = (int) (value % (long) count);
        return remainder < 0 ? remainder + count : remainder;
    }

    private static Calendar calendar(long nowMillis, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.setTimeInMillis(nowMillis);
        return calendar;
    }

    private static String dateKey(Calendar calendar) {
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String dateKeyOffset(Calendar calendar, int days) {
        Calendar copy = (Calendar) calendar.clone();
        copy.add(Calendar.DAY_OF_YEAR, days);
        return dateKey(copy);
    }

    private static int dateNumber(Calendar calendar) {
        return (calendar.get(Calendar.YEAR) * 10000)
                + ((calendar.get(Calendar.MONTH) + 1) * 100)
                + calendar.get(Calendar.DAY_OF_MONTH);
    }

    private static long stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long hash = 0L;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (bytes[i] & 0xffL);
            }
            return hash;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static double freshness(String releaseDate, long nowMillis) {
        if (releaseDate == null || releaseDate.isEmpty()) return 0.0;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try {
            long released = format.parse(releaseDate).getTime();
            double days = (nowMillis - released) / 86400000.0;
            if (days < 0) return 0.0;
            return 1.0 / (1.0 + (days / 730.0));
        } catch (ParseException error) {
            return 0.0;
        }
    }

    private static JSONObject copy(JSONObject query) {
        if (query == null) return new JSONObject();
        try {
            return new JSONObject(query.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("query", error);
        }
    }

    private static void put(JSONObject query, String key, Object value) {
        try {
            query.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException(key, error);
        }
    }
}
