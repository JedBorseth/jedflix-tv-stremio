package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.List;

public final class LiveTvStreamRulesTest {
    static JSONObject stream(String release, String url) throws Exception {
        return new JSONObject().put("name", "Comet").put("url", url)
            .put("behaviorHints", new JSONObject().put("filename", release));
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        check(LiveTvStreamRules.validVideoId("movie", "tt0319343"), "movies use their IMDb video ID");
        check(LiveTvStreamRules.validVideoId("series", "tt0108778:1:2"), "mixed shows use complete episode IDs");
        check(!LiveTvStreamRules.validVideoId("movie", "tt0108778:1:2"), "movie requests reject episode IDs");
        check(!LiveTvStreamRules.validVideoId("series", "tt0319343"), "series requests require an episode ID");
        check(!LiveTvStreamRules.validVideoId("other", "tt0319343"), "unsupported resource types rejected");
        check(!LiveTvStreamRules.validVideoId("movie", "tt0319343/stream.json"), "resource IDs cannot inject paths");
        JSONObject a = stream("The.Office.S01E01.1080p.A.mkv", "https://example.test/user-one");
        JSONObject b = stream("The.Office.S01E01.1080p.B.mkv", "https://example.test/user-two");
        check(LiveTvStreamRules.select(new JSONArray().put(b).put(a)) == a, "stable preferred release");
        check(LiveTvStreamRules.select(new JSONArray().put(a).put(b)) == a, "response order does not alter release");
        JSONObject alternateAccount = stream("The.Office.S01E01.1080p.A.mkv", "https://example.test/other-account");
        check(LiveTvStreamRules.select(new JSONArray().put(b).put(alternateAccount)) == alternateAccount,
            "personal URLs do not affect release preference");
        JSONObject refreshed = stream("The.Office.S01E01.1080p.A.mkv", "https://example.test/new-personal-link")
            .put("name", "Comet cached").put("description", "99 seeders today");
        check(LiveTvStreamRules.key(a).equals(LiveTvStreamRules.key(refreshed)),
            "changing seed counts and cache labels do not change file identity");
        JSONObject low = stream("The.Office.S01E01.720p.mkv", "https://example.test/720");
        check(LiveTvStreamRules.select(new JSONArray().put(low).put(a)) == a, "1080p preferred if returned by settings");
        check(LiveTvStreamRules.select(new JSONArray().put(low)) == low, "720p-only settings supported");
        JSONObject extended = stream("The.Office.Superfan.1080p.mkv", "https://example.test/extended");
        check(LiveTvStreamRules.select(new JSONArray().put(extended)) == null, "different episode cut cannot use canonical timeline");
        JSONObject torrent = new JSONObject().put("infoHash", "abcdef");
        JSONObject magnet = stream("1080p", "magnet:?xt=urn:btih:abc");
        JSONObject credentialUrl = stream("1080p", "https://secret@example.test/file");
        check(LiveTvStreamRules.select(new JSONArray().put(JSONObject.NULL).put(4).put(torrent).put(magnet).put(credentialUrl)) == null,
            "unplayable entries excluded");
        check(LiveTvStreamRules.select(null) == null, "missing streams is an empty result");
        check(LiveTvStreamRules.select(new JSONArray()) == null, "empty source result");
        check(!LiveTvStreamRules.playableUrl("https://comet.elfhosted.com/errors/INFRINGING_FILE/slate.m3u8"),
            "add-on error playlist is rejected before playback");
        check(!LiveTvStreamRules.playableUrl("https://comet.elfhosted.com/errors/INFRINGING_FILE/slate.mp4"),
            "add-on error video is rejected even when HTTP succeeds");
        check(LiveTvStreamRules.playableUrl("https://example.test/The.Slate.1080p.mkv"),
            "a real release with slate in its title is allowed");

        JSONArray alternatives = new JSONArray().put(low).put(a).put(alternateAccount);
        List<JSONObject> ordered = LiveTvStreamRules.candidates(alternatives, null);
        check(ordered.get(0) == a, "candidate order prefers 1080p");
        check(ordered.get(ordered.size() - 1) == low, "candidate order falls back to 720p");
        List<JSONObject> withoutA = LiveTvStreamRules.candidates(alternatives,
            Collections.singleton(LiveTvStreamRules.key(a)));
        check(withoutA.get(0) != a, "rejected release is skipped");
        check(withoutA.get(0) == low || withoutA.get(0) == alternateAccount,
            "next stable release is used after rejection");

        long slot = 23L * 60L * 1000L;
        check(LiveTvStreamRules.durationCompatible(slot, slot), "exact runtime accepted");
        check(LiveTvStreamRules.durationCompatible(slot + 2L * 60L * 1000L, slot),
            "credits and recaps accepted");
        check(!LiveTvStreamRules.durationCompatible(slot + 7L * 60L * 1000L, slot),
            "extended cut rejected");
        check(!LiveTvStreamRules.durationCompatible(slot - 5L * 60L * 1000L, slot),
            "short edit rejected");
        check(LiveTvStreamRules.durationCompatible(0L, slot), "unknown runtime is not a failure");

        System.out.println("PASS: stable release choice, source settings, error-video rejection, cut compatibility, malformed and empty streams");
    }
}
