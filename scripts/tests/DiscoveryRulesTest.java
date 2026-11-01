package com.jedflix.tv;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DiscoveryRulesTest {
    private static final TimeZone VANCOUVER = TimeZone.getTimeZone("America/Vancouver");
    private static int checks = 0;

    private DiscoveryRulesTest() {}

    public static void main(String[] args) throws Exception {
        seasonalBoundaries();
        seasonalOrdering();
        daypartBoundaries();
        dailyStability();
        coldStartDiversity();
        coldStartContracts();
        recentDigitalContract();
        personalSignalRows();
        preferredGenreOrdering();
        crowdedDayCap();
        candidateRanking();
        System.out.println("DiscoveryRulesTest: OK (" + checks + " checks)");
    }

    private static void seasonalBoundaries() throws Exception {
        check(!has(plan(at(2026, 10, 9, 12, 0)), "home.seasonal.thanksgiving"),
                "Thanksgiving inactive Oct 9");
        check(has(plan(at(2026, 10, 10, 12, 0)), "home.seasonal.thanksgiving"),
                "Thanksgiving active Oct 10");
        check(has(plan(at(2026, 10, 11, 12, 0)), "home.seasonal.thanksgiving"),
                "Thanksgiving active Oct 11");
        check(has(plan(at(2026, 10, 12, 12, 0)), "home.seasonal.thanksgiving"),
                "Thanksgiving active Oct 12");
        check(!has(plan(at(2026, 10, 13, 12, 0)), "home.seasonal.thanksgiving"),
                "Thanksgiving inactive Oct 13");

        check(has(plan(at(2026, 10, 1, 0, 0)), "home.seasonal.halloween"), "Halloween Oct 1");
        check(has(plan(at(2026, 10, 31, 23, 59)), "home.seasonal.halloween"),
                "Halloween Oct 31");
        check(!has(plan(at(2026, 11, 1, 0, 0)), "home.seasonal.halloween"),
                "Halloween ends Nov 1");

        check(!has(plan(at(2026, 11, 19, 23, 59)), "home.seasonal.christmas"),
                "Christmas inactive Nov 19");
        check(has(plan(at(2026, 11, 20, 0, 0)), "home.seasonal.christmas"),
                "Christmas active Nov 20");
        check(has(plan(at(2026, 12, 25, 23, 59)), "home.seasonal.christmas"),
                "Christmas active Dec 25");
        check(!has(plan(at(2026, 12, 26, 0, 0)), "home.seasonal.christmas"),
                "Christmas ends Dec 26");

        check(has(plan(at(2026, 12, 26, 12, 0)), "home.seasonal.newyear"),
                "New Year starts Dec 26");
        check(has(plan(at(2027, 1, 7, 23, 59)), "home.seasonal.newyear"),
                "New Year active Jan 7");
        check(!has(plan(at(2027, 1, 8, 0, 0)), "home.seasonal.newyear"),
                "New Year ends Jan 8");
        check(has(plan(at(2027, 1, 15, 12, 0)), "home.seasonal.winter"), "Winter active Jan 15");
        check(has(plan(at(2027, 2, 28, 23, 59)), "home.seasonal.winter"),
                "Winter active Feb 28");
        check(!has(plan(at(2027, 3, 1, 0, 0)), "home.seasonal.winter"),
                "Winter ends Mar 1");

        check(!has(plan(at(2027, 2, 6, 23, 59)), "home.seasonal.valentine"),
                "Valentine inactive Feb 6");
        check(has(plan(at(2027, 2, 7, 0, 0)), "home.seasonal.valentine"),
                "Valentine active Feb 7");
        check(has(plan(at(2027, 2, 14, 23, 59)), "home.seasonal.valentine"),
                "Valentine active Feb 14");
        check(!has(plan(at(2027, 2, 15, 0, 0)), "home.seasonal.valentine"),
                "Valentine ends Feb 15");

        check(has(plan(at(2026, 6, 1, 0, 0)), "home.seasonal.summer"), "Summer starts Jun 1");
        check(has(plan(at(2026, 8, 31, 23, 59)), "home.seasonal.summer"),
                "Summer active Aug 31");
        check(!has(plan(at(2026, 9, 1, 0, 0)), "home.seasonal.summer"), "Summer ends Sep 1");
    }

    private static void seasonalOrdering() throws Exception {
        List<DiscoveryRules.Row> autumn = plan(at(2026, 10, 10, 12, 0));
        check("home.trending.movies".equals(autumn.get(0).id), "Trending first");
        int seasonal = 0;
        int index = 1;
        while (index < autumn.size() && autumn.get(index).id.startsWith("home.seasonal.")) {
            seasonal++;
            index++;
        }
        check(seasonal == 2, "Two overlapping seasonal rows at most");
        check(autumn.get(index).id.startsWith("home.moment."), "Contextual row after seasonal");

        List<DiscoveryRules.Row> spring = plan(at(2026, 3, 15, 12, 0));
        check("home.trending.movies".equals(spring.get(0).id), "Trending first without season");
        check(spring.get(1).id.startsWith("home.moment."), "Contextual row directly after trending");
    }

    private static void daypartBoundaries() throws Exception {
        check("home.moment.default".equals(moment(at(2026, 10, 2, 16, 59))),
                "Friday before 17 is default");
        check("home.moment.friday".equals(moment(at(2026, 10, 2, 17, 0))),
                "Friday 17 starts movie night");
        check("home.moment.friday".equals(moment(at(2026, 10, 2, 21, 59))),
                "Friday 21:59 still movie night");
        check("home.moment.latenight".equals(moment(at(2026, 10, 2, 22, 0))),
                "Friday 22 starts late night");

        check("home.moment.weekend".equals(moment(at(2026, 10, 3, 11, 59))),
                "Saturday morning is weekend");
        check("home.moment.saturday".equals(moment(at(2026, 10, 3, 12, 0))),
                "Saturday noon starts double feature");
        check("home.moment.saturday".equals(moment(at(2026, 10, 3, 21, 59))),
                "Saturday evening is double feature");
        check("home.moment.latenight".equals(moment(at(2026, 10, 3, 22, 0))),
                "Saturday 22 starts late night");

        check("home.moment.weekend".equals(moment(at(2026, 10, 4, 15, 59))),
                "Sunday afternoon is weekend");
        check("home.moment.sunday".equals(moment(at(2026, 10, 4, 16, 0))),
                "Sunday 16 starts comfort watches");
        check("home.moment.sunday".equals(moment(at(2026, 10, 4, 21, 59))),
                "Sunday 21:59 is comfort watches");
        check("home.moment.latenight".equals(moment(at(2026, 10, 4, 22, 0))),
                "Sunday 22 starts late night");

        check("home.moment.default".equals(moment(at(2026, 10, 5, 16, 59))),
                "Monday before 17 is default");
        check("home.moment.weeknight".equals(moment(at(2026, 10, 5, 17, 0))),
                "Monday 17 starts weeknight");
        check("home.moment.weeknight".equals(moment(at(2026, 10, 5, 21, 59))),
                "Monday 21:59 is weeknight");
        check("home.moment.latenight".equals(moment(at(2026, 10, 5, 22, 0))),
                "Monday 22 starts late night");
        check("home.moment.latenight".equals(moment(at(2026, 10, 6, 2, 59))),
                "Late night includes 02:59");
        check("home.moment.default".equals(moment(at(2026, 10, 6, 3, 0))),
                "Late night ends at 03:00");
    }

    private static void dailyStability() throws Exception {
        long instant = at(2026, 10, 15, 10, 0);
        check(serialize(plan(instant)).equals(serialize(plan(instant))),
                "Same request is byte stable");

        List<DiscoveryRules.Row> morning = plan(at(2026, 10, 15, 10, 0));
        List<DiscoveryRules.Row> evening = plan(at(2026, 10, 15, 20, 0));
        for (DiscoveryRules.Row row : morning) {
            if (row.id.startsWith("home.moment.")) continue;
            DiscoveryRules.Row other = find(evening, row.id);
            check(other != null, "Row survives same local date: " + row.id);
            check(row.query.toString().equals(other.query.toString()),
                    "Same-day query stable: " + row.id);
        }

        Set<String> firstIds = ids(plan(at(2026, 10, 15, 10, 0)));
        Set<String> lastIds = ids(plan(at(2026, 10, 21, 10, 0)));
        check(firstIds.equals(lastIds), "Row ids stable across a week in one season");

        boolean changed = false;
        List<DiscoveryRules.Row> first = plan(at(2026, 10, 15, 10, 0));
        List<DiscoveryRules.Row> last = plan(at(2026, 10, 21, 10, 0));
        for (DiscoveryRules.Row row : first) {
            if (!row.rotate) continue;
            DiscoveryRules.Row other = find(last, row.id);
            if (other != null && !row.query.toString().equals(other.query.toString())) {
                changed = true;
            }
        }
        check(changed, "Seeded rotation changes at least one row during a week");
    }

    private static void coldStartDiversity() throws Exception {
        List<DiscoveryRules.Row> rows = plan(at(2026, 3, 15, 10, 0));
        check(rows.size() >= 12 && rows.size() <= 14, "Cold start row count");
        check(countPrefix(rows, "home.personal.") == 0, "No personal rows without seeds");
        check(has(rows, "home.spotlight.family"), "Family cold-start row");
        check(has(rows, "home.spotlight.documentary"), "Documentary cold-start row");
        check(has(rows, "home.spotlight.world"), "International cold-start row");
        check(has(rows, "home.spotlight.anime"), "Anime cold-start row");
        check(has(rows, "home.spotlight.comedy"), "Comedy cold-start row");
        check(has(rows, "home.spotlight.crime"), "Crime cold-start row");
        check(has(rows, "home.spotlight.mystery"), "Mystery cold-start row");
        check(has(rows, "home.spotlight.scifi"), "Sci-fi cold-start row");
        check(has(rows, "home.spotlight.fantasy"), "Fantasy cold-start row");

        boolean movie = false;
        boolean series = false;
        for (DiscoveryRules.Row row : rows) {
            movie |= "movie".equals(row.type);
            series |= "series".equals(row.type);
        }
        check(movie, "Cold start includes movies");
        check(series, "Cold start includes series");

        List<DiscoveryRules.Row> autumn = plan(at(2026, 10, 15, 10, 0));
        check(autumn.size() <= 14, "Autumn row cap");
        check(has(autumn, "home.seasonal.halloween"), "Autumn seasonal row");
        check(has(autumn, "home.spotlight.family"), "Autumn keeps family row");
        check(has(autumn, "home.spotlight.documentary"), "Autumn keeps documentary row");
        check(has(autumn, "home.spotlight.world"), "Autumn keeps international row");
        check(has(autumn, "home.spotlight.anime"), "Autumn keeps anime row");
    }

    private static void coldStartContracts() throws Exception {
        long instant = at(2026, 3, 15, 10, 0);
        String today = dateKey(instant);
        List<DiscoveryRules.Row> rows = plan(instant);
        check(rows.size() == 14, "Expected full cold-start slate");
        Set<String> seen = new HashSet<>();
        for (DiscoveryRules.Row row : rows) {
            check(seen.add(row.id), "Unique row id: " + row.id);
            check(row.limit == 48, "Larger candidate pools fill longer shelves: " + row.id);
            check(row.path.equals("/discover/movie")
                    || row.path.equals("/discover/tv")
                    || row.path.equals("/trending/movie/week"), "API-relative path: " + row.id);
            check(row.seedImdb == null, "No seed on non-personal row: " + row.id);
            checkValidQuery(row, today);
        }

        DiscoveryRules.Row family = find(rows, "home.spotlight.family");
        check("10751".equals(genre(family)), "Family uses family genre");
        check("US".equals(family.query.optString("certification_country", "")),
                "Family has certification country");
        check("PG".equals(family.query.optString("certification.lte", "")),
                "Family certification capped at PG");
        DiscoveryRules.Row documentary = find(rows, "home.spotlight.documentary");
        check("series".equals(documentary.type) && "99".equals(genre(documentary)),
                "Documentary series genre");
        DiscoveryRules.Row crime = find(rows, "home.spotlight.crime");
        check("80".equals(genre(crime)), "Crime genre");
        DiscoveryRules.Row mystery = find(rows, "home.spotlight.mystery");
        check(genre(mystery).contains("9648"), "Mystery genre");
        DiscoveryRules.Row scifi = find(rows, "home.spotlight.scifi");
        check(genre(scifi).contains("10765"), "Sci-fi genre");
        DiscoveryRules.Row fantasy = find(rows, "home.spotlight.fantasy");
        check(genre(fantasy).contains("14"), "Fantasy genre");
        DiscoveryRules.Row world = find(rows, "home.spotlight.world");
        String language = world.query.optString("with_original_language", "");
        check(!language.isEmpty() && !"en".equals(language), "International language rotates");
        DiscoveryRules.Row anime = find(rows, "home.spotlight.anime");
        check(genre(anime).contains("16"), "Anime animation genre");
        check("ja".equals(anime.query.optString("with_original_language", "")),
                "Anime original language");

        for (DiscoveryRules.Row row : rows) {
            if (!row.path.equals("/discover/movie")) continue;
            check(today.equals(row.query.optString("release_date.lte", "")),
                    "No unreleased movie: " + row.id);
            check(row.query.has("vote_count.gte") && row.query.optInt("vote_count.gte") > 0,
                    "Vote-count floor: " + row.id);
            check(row.query.has("vote_average.gte") && row.query.optDouble("vote_average.gte") > 0,
                    "Vote-average floor: " + row.id);
        }
        for (DiscoveryRules.Row row : rows) {
            if (!row.path.equals("/discover/tv")) continue;
            check(today.equals(row.query.optString("first_air_date.lte", "")),
                    "No unreleased series: " + row.id);
            check(row.query.has("vote_count.gte") && row.query.optInt("vote_count.gte") > 0,
                    "TV vote-count floor: " + row.id);
        }
    }

    private static void recentDigitalContract() throws Exception {
        long instant = at(2026, 3, 15, 10, 0);
        String today = dateKey(instant);
        DiscoveryRules.Row fresh = find(plan(instant), "home.spotlight.newdigital");
        check(fresh != null, "Fresh digital row present");
        check("CA".equals(fresh.query.optString("region", "")), "Digital region CA");
        check("4|5".equals(fresh.query.optString("with_release_type", "")),
                "Digital/physical release types");
        check(today.equals(fresh.query.optString("release_date.lte", "")),
                "Digital release not in the future");
        check(fresh.query.optString("release_date.gte", "").compareTo(today) <= 0,
                "Digital row has a recent lower bound");
        check(fresh.query.optInt("vote_count.gte", 9999) <= 25,
                "Adaptive low vote floor for recent titles");
        check("release_date.desc".equals(fresh.query.optString("sort_by", "")),
                "Digital row sorted by release date");
        check(!fresh.title.toLowerCase(Locale.US).contains("stream"),
                "Digital row does not claim streaming availability");
    }

    private static void personalSignalRows() throws Exception {
        JSONObject signals = new JSONObject();
        JSONArray seeds = new JSONArray();
        seeds.put(seed("tt0111161", "movie", "The Shawshank Redemption"));
        seeds.put(seed("tt0903747", "series", "Breaking Bad"));
        signals.put("seeds", seeds);

        List<DiscoveryRules.Row> rows = plan(at(2026, 3, 15, 10, 0), signals);
        check(rows.size() <= 14, "Personal slate respects cap");
        check(countPrefix(rows, "home.personal.") == 2, "Two meaningful seed rows");

        String recentId = "home.personal.tt0111161";
        String moreId = "home.personal.more.tt0903747";
        DiscoveryRules.Row recent = find(rows, recentId);
        DiscoveryRules.Row more = find(rows, moreId);
        check(recent != null && more != null, "Personal ids include the seed IMDb id");
        check(recent.id.contains("tt0111161"), "First personal id carries its seed");
        check(more.id.contains("tt0903747"), "Second personal id carries its seed");
        check("tt0111161".equals(recent.seedImdb), "First seed IMDb id");
        check("tt0903747".equals(more.seedImdb), "Second seed IMDb id");
        check("/discover/movie".equals(recent.path) && "movie".equals(recent.type),
                "Movie seed path and type");
        check("/discover/tv".equals(more.path) && "series".equals(more.type),
                "Series seed path and type");
        check(recent.title.contains("The Shawshank Redemption"), "Recent seed title");
        check(more.title.contains("Breaking Bad"), "More seed title");
        check(recent.rotate && more.rotate, "Personal rows rotate");
        check(indexOf(rows, recentId) > indexOfPrefix(rows, "home.moment."),
                "Personal row follows contextual row");
        check(indexOf(rows, moreId) < indexOf(rows, "home.spotlight.family"),
                "Personal rows precede genre rows");

        JSONArray one = new JSONArray();
        one.put(seed("tt0111161", "movie", "The Shawshank Redemption"));
        JSONObject oneSignal = new JSONObject();
        oneSignal.put("seeds", one);
        check(countPrefix(plan(at(2026, 3, 15, 10, 0), oneSignal), "home.personal.") == 1,
                "One seed gives one personal row");

        JSONArray three = new JSONArray();
        three.put(seed("tt0111161", "movie", "The Shawshank Redemption"));
        three.put(seed("tt0903747", "series", "Breaking Bad"));
        three.put(seed("tt0944947", "series", "Game of Thrones"));
        JSONObject threeSignal = new JSONObject();
        threeSignal.put("seeds", three);
        check(countPrefix(plan(at(2026, 3, 15, 10, 0), threeSignal), "home.personal.") == 2,
                "Three seeds still give two personal rows");

        JSONObject realistic = new JSONObject();
        realistic.put("seeds", seeds);
        JSONArray exclude = new JSONArray();
        exclude.put("tt0111161");
        exclude.put("tt0903747");
        realistic.put("exclude", exclude);
        List<DiscoveryRules.Row> realisticRows = plan(at(2026, 3, 15, 10, 0), realistic);
        check(countPrefix(realisticRows, "home.personal.") == 2,
                "Exclude containing every seed id does not suppress personal rows");
        check(find(realisticRows, recentId) != null && find(realisticRows, moreId) != null,
                "Excluded seed ids still produce their personal rows");
        check("tt0111161".equals(find(realisticRows, recentId).seedImdb),
                "Realistic first seed remains a personal seed");

        JSONArray otherSeed = new JSONArray();
        otherSeed.put(seed("tt0133093", "movie", "The Matrix"));
        JSONObject otherSignal = new JSONObject();
        otherSignal.put("seeds", otherSeed);
        List<DiscoveryRules.Row> otherRows = plan(at(2026, 3, 15, 10, 0), otherSignal);
        check(has(otherRows, "home.personal.tt0133093"),
                "New seed produces a new seed-scoped personal id");
        check(!has(otherRows, recentId),
                "Old seed personal id is not reused for a new seed");

        JSONArray invalid = new JSONArray();
        invalid.put(seed("tmdb:1", "movie", "Bad id"));
        invalid.put(seed("ttabc", "movie", "Non-numeric IMDb id"));
        invalid.put(seed("tt1234567", "show", "Bad type"));
        JSONObject invalidSignal = new JSONObject();
        invalidSignal.put("seeds", invalid);
        check(countPrefix(plan(at(2026, 3, 15, 10, 0), invalidSignal), "home.personal.") == 0,
                "Invalid seeds are ignored");
    }

    private static void preferredGenreOrdering() throws Exception {
        JSONObject signals = new JSONObject();
        JSONArray preferred = new JSONArray();
        preferred.put(35);
        preferred.put(80);
        signals.put("preferredGenres", preferred);

        List<DiscoveryRules.Row> rows = plan(at(2026, 3, 15, 10, 0), signals);
        int comedy = indexOf(rows, "home.spotlight.comedy");
        int crime = indexOf(rows, "home.spotlight.crime");
        int mystery = indexOf(rows, "home.spotlight.mystery");
        check(comedy < mystery, "Preferred comedy rises above mystery");
        check(crime < mystery, "Preferred crime rises above mystery");

        JSONObject docs = new JSONObject();
        JSONArray docGenres = new JSONArray();
        docGenres.put(99);
        docs.put("preferredGenres", docGenres);
        List<DiscoveryRules.Row> docRows = plan(at(2026, 3, 15, 10, 0), docs);
        check(indexOf(docRows, "home.spotlight.documentary")
                < indexOf(docRows, "home.spotlight.family"),
                "Preferred documentary rises above family");
    }

    private static void crowdedDayCap() throws Exception {
        JSONObject signals = new JSONObject();
        JSONArray seeds = new JSONArray();
        seeds.put(seed("tt0111161", "movie", "The Shawshank Redemption"));
        seeds.put(seed("tt0903747", "series", "Breaking Bad"));
        signals.put("seeds", seeds);

        List<DiscoveryRules.Row> rows = plan(at(2026, 10, 10, 12, 0), signals);
        check(rows.size() == 14, "Crowded day capped at 14 rows");
        check(has(rows, "home.seasonal.thanksgiving"), "Crowded day keeps Thanksgiving");
        check(has(rows, "home.seasonal.halloween"), "Crowded day keeps Halloween");
        check(countPrefix(rows, "home.personal.") == 2, "Crowded day keeps two personal rows");
        check(has(rows, "home.spotlight.anime"), "Crowded day keeps anime");
        check(has(rows, "home.spotlight.family"), "Crowded day keeps family");
        check(has(rows, "home.spotlight.documentary"), "Crowded day keeps documentary");
        check(has(rows, "home.spotlight.world"), "Crowded day keeps international");
        DiscoveryRules.Row fresh = find(rows, "home.spotlight.newdigital");
        check(fresh != null, "Crowded day keeps Fresh on Digital");
        check("2026-10-10".equals(fresh.query.optString("release_date.lte", "")),
                "Fresh on Digital stays bounded to today");
        for (DiscoveryRules.Row row : rows) {
            String id = row.id.toLowerCase(Locale.US);
            check(!id.contains("nowplaying") && !id.contains("now_playing")
                    && !id.contains("future"), "No Now Playing/Future row: " + row.id);
        }
    }

    private static void candidateRanking() throws Exception {
        JSONArray candidates = new JSONArray();
        candidates.put(candidate(1, 9.6, 12000, 5000.0, "2026-01-15"));
        candidates.put(candidate(2, 7.1, 120, 12.0, "2012-04-01"));
        candidates.put(candidate(3, 8.4, 900, 80.0, "2024-06-01"));
        candidates.put(candidate(4, 6.8, 40, 4.0, "2004-02-01"));
        candidates.put(candidate(5, 8.9, 5000, 300.0, "2023-10-01"));

        JSONArray ranked = DiscoveryRules.rankCandidates(
                candidates, "home.spotlight.comedy", at(2026, 3, 15, 10, 0), VANCOUVER, 3);
        check(ranked.length() == 3, "Ranking honors limit");
        check(ranked.getJSONObject(0).getInt("id") == 1, "Best quality ranks first");

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < ranked.length(); i++) {
            seen.add(ranked.getJSONObject(i).getInt("id"));
        }
        check(seen.size() == 3, "Ranking does not duplicate candidates");

        JSONArray repeat = DiscoveryRules.rankCandidates(
                candidates, "home.spotlight.comedy", at(2026, 3, 15, 20, 0), VANCOUVER, 3);
        check(ranked.toString().equals(repeat.toString()), "Ranking stable within local date");

        JSONArray all = DiscoveryRules.rankCandidates(
                candidates, "home.spotlight.comedy", at(2026, 3, 15, 10, 0), VANCOUVER, 99);
        check(all.length() == 5, "Limit larger than candidate list returns all");
        check(candidates.length() == 5, "Ranking does not mutate input");
        check(DiscoveryRules.rankCandidates(null, "row", 0L, VANCOUVER, 10).length() == 0,
                "Null candidate list is empty");
    }

    private static List<DiscoveryRules.Row> plan(long millis) {
        return plan(millis, new JSONObject());
    }

    private static List<DiscoveryRules.Row> plan(long millis, JSONObject signals) {
        return DiscoveryRules.rows(millis, VANCOUVER, signals);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(VANCOUVER, Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static String dateKey(long millis) {
        Calendar calendar = Calendar.getInstance(VANCOUVER, Locale.US);
        calendar.setTimeInMillis(millis);
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static JSONObject seed(String id, String type, String name) throws Exception {
        JSONObject seed = new JSONObject();
        seed.put("id", id);
        seed.put("type", type);
        seed.put("name", name);
        return seed;
    }

    private static JSONObject candidate(
            int id, double voteAverage, int voteCount, double popularity, String release)
            throws Exception {
        JSONObject candidate = new JSONObject();
        candidate.put("id", id);
        candidate.put("vote_average", voteAverage);
        candidate.put("vote_count", voteCount);
        candidate.put("popularity", popularity);
        candidate.put("release_date", release);
        return candidate;
    }

    private static DiscoveryRules.Row find(List<DiscoveryRules.Row> rows, String id) {
        for (DiscoveryRules.Row row : rows) {
            if (row.id.equals(id)) return row;
        }
        return null;
    }

    private static boolean has(List<DiscoveryRules.Row> rows, String id) {
        return find(rows, id) != null;
    }

    private static int indexOf(List<DiscoveryRules.Row> rows, String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private static int indexOfPrefix(List<DiscoveryRules.Row> rows, String prefix) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int countPrefix(List<DiscoveryRules.Row> rows, String prefix) {
        int count = 0;
        for (DiscoveryRules.Row row : rows) {
            if (row.id.startsWith(prefix)) count++;
        }
        return count;
    }

    private static String moment(long millis) {
        List<DiscoveryRules.Row> rows = plan(millis);
        for (DiscoveryRules.Row row : rows) {
            if (row.id.startsWith("home.moment.")) return row.id;
        }
        return null;
    }

    private static Set<String> ids(List<DiscoveryRules.Row> rows) {
        Set<String> ids = new HashSet<>();
        for (DiscoveryRules.Row row : rows) ids.add(row.id);
        return ids;
    }

    private static String genre(DiscoveryRules.Row row) {
        return row.query.optString("with_genres", "");
    }

    private static String serialize(List<DiscoveryRules.Row> rows) {
        StringBuilder builder = new StringBuilder();
        for (DiscoveryRules.Row row : rows) {
            builder.append(row.id).append('|')
                    .append(row.title).append('|')
                    .append(row.type).append('|')
                    .append(row.path).append('|')
                    .append(row.seedImdb).append('|')
                    .append(row.query).append('|')
                    .append(row.limit).append('|')
                    .append(row.rotate).append('\n');
        }
        return builder.toString();
    }

    private static void checkValidQuery(DiscoveryRules.Row row, String today) {
        JSONObject query = row.query;
        String text = query.toString().toLowerCase(Locale.US);
        check(!query.has("api_key"), "No api_key: " + row.id);
        check(!text.contains("authorization") && !text.contains("bearer"),
                "No credentials: " + row.id);
        check(!text.contains("http") && !text.contains("?"), "No URLs: " + row.id);

        if (!row.path.equals("/trending/movie/week")) {
            check(Boolean.FALSE.equals(query.opt("include_adult")),
                    "include_adult=false: " + row.id);
            check("en-US".equals(query.optString("language", "")),
                    "English metadata: " + row.id);
        }
        check(query.optInt("page", 0) >= 1, "Valid page: " + row.id);

        java.util.Iterator<String> keys = query.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = query.opt(key);
            check(value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean, "Scalar query value: " + row.id + "." + key);
            if (value instanceof String) {
                check(!((String) value).isEmpty(), "Nonempty query value: " + row.id + "." + key);
            }
        }
        check(!query.has("release_date.lte") || query.optString("release_date.lte").equals(today),
                "Release bound is today: " + row.id);
        check(!query.has("first_air_date.lte")
                        || query.optString("first_air_date.lte").equals(today),
                "Air-date bound is today: " + row.id);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
