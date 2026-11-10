package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

public final class LiveTvScheduleTest {
    private static final long EPOCH = 1700000000000L;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectInvalid(String json, String message) {
        try {
            LiveTvSchedule.parse(json);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    private static void expectInvalid(JSONObject root, String message) {
        expectInvalid(root.toString(), message);
    }

    private static void expectTimeInvalid(LiveTvSchedule.Channel channel, long utcMillis, String message) {
        try {
            channel.at(utcMillis);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    private static JSONObject program(String videoId, String title, long durationMs) {
        return new JSONObject()
                .put("videoId", videoId)
                .put("title", title)
                .put("season", 1)
                .put("episode", 1)
                .put("durationMs", durationMs)
                .put("thumbnail", "https://example.test/" + videoId + ".jpg")
                .put("description", "Description for " + title);
    }

    private static JSONObject channel(String id, long seed, JSONArray programs) {
        return new JSONObject()
                .put("id", id)
                .put("name", "Channel " + id)
                .put("description", "The " + id + " channel")
                .put("type", "series")
                .put("imdbId", "tt0386676")
                .put("thumbnail", "https://example.test/channel-" + id + ".jpg")
                .put("seed", seed)
                .put("programs", programs);
    }

    private static JSONObject scheduleObject(long epochMs) {
        JSONArray programs = new JSONArray();
        programs.put(program("tt0386676:1:1", "Pilot", 1000L));
        programs.put(program("tt0386676:1:2", "Diversity Day", 2000L));
        programs.put(program("tt0386676:1:3", "Health Care", 3000L));
        JSONArray channels = new JSONArray();
        channels.put(channel("office", 7L, programs));
        return new JSONObject()
                .put("version", 1)
                .put("revision", "office-v1")
                .put("epochMs", epochMs)
                .put("channels", channels);
    }

    private static JSONObject firstChannel(JSONObject root) {
        return root.getJSONArray("channels").getJSONObject(0);
    }

    private static JSONObject firstProgram(JSONObject root) {
        return firstChannel(root).getJSONArray("programs").getJSONObject(0);
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(character);
        return builder.toString();
    }

    private static void testFieldsAndImmutability() {
        JSONArray programs = new JSONArray();
        programs.put(program("v0", "Zero", 1000L));
        programs.put(program("v1", "One", 2000L));
        programs.put(program("v2", "Two", 3000L));
        programs.put(program("v3", "Three", 4000L));
        programs.put(program("v4", "Four", 5000L));
        JSONArray channels = new JSONArray();
        channels.put(channel("office", 3846676L, programs));
        JSONObject root = new JSONObject()
                .put("version", 1)
                .put("revision", "office-v1")
                .put("epochMs", 1767225600000L)
                .put("channels", channels);

        LiveTvSchedule schedule = LiveTvSchedule.parse(root.toString());
        check(schedule.version == 1, "Version is exposed");
        check(schedule.revision.equals("office-v1"), "Revision is exposed");
        check(schedule.epochMs == 1767225600000L, "Epoch is exposed");
        check(schedule.channels().size() == 1, "One channel is parsed");
        check(schedule.channel("missing") == null, "Unknown channel is null");
        check(schedule.channel(null) == null, "Null channel id is null");

        LiveTvSchedule.Channel office = schedule.channel("office");
        check(office != null, "Channel is found by id");
        check(office.id.equals("office"), "Channel id");
        check(office.name.equals("Channel office"), "Channel name");
        check(office.description.equals("The office channel"), "Channel description");
        check(office.type.equals("series"), "Channel type");
        check(office.imdbId.equals("tt0386676"), "Channel imdb id");
        check(office.thumbnail.equals("https://example.test/channel-office.jpg"), "Channel artwork");
        check(office.seed == 3846676L, "Channel seed");
        check(office.epochMs == schedule.epochMs, "Channel shares the schedule epoch");
        check(office.programs().size() == 5, "All programs are retained");
        check(office.program("missing") == null, "Unknown program is null");
        check(office.program(null) == null, "Null video id is null");
        check(office.program("v2").title.equals("Two"), "Program is found by video id");
        check(office.program("v2").durationMs == 3000L, "Program duration");
        check(office.program("v2").seriesTitle.equals(""),
                "Omitted seriesTitle defaults to empty string");
        check(office.program("v2").year == 0, "Omitted year defaults to zero");

        try {
            schedule.channels().add(office);
            throw new AssertionError("channels() must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            office.programs().clear();
            throw new AssertionError("programs() must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void testDeterministicShuffle() {
        JSONObject root = scheduleObject(EPOCH);
        firstChannel(root).put("seed", 3846676L);

        LiveTvSchedule first = LiveTvSchedule.parse(root.toString());
        LiveTvSchedule second = LiveTvSchedule.parse(root.toString());
        String[] expected = {"tt0386676:1:3", "tt0386676:1:1", "tt0386676:1:2"};
        List<LiveTvSchedule.Program> programs = first.channel("office").programs();
        check(programs.size() == 3, "Shuffle vector has three programs");
        for (int i = 0; i < programs.size(); i++) {
            check(programs.get(i).videoId.equals(expected[i]),
                    "Seeded shuffle vector mismatch at " + i);
            check(programs.get(i).videoId.equals(second.channel("office").programs().get(i).videoId),
                    "Parsing twice produces the same frozen order");
        }

        JSONArray five = new JSONArray();
        for (int i = 0; i < 5; i++) five.put(program("p" + i, "P" + i, 1000L * (i + 1)));
        JSONArray channels = new JSONArray();
        channels.put(channel("five", 3846676L, five));
        JSONObject fiveRoot = new JSONObject()
                .put("version", 1).put("revision", "five")
                .put("epochMs", EPOCH).put("channels", channels);
        String[] fiveExpected = {"p0", "p4", "p3", "p2", "p1"};
        List<LiveTvSchedule.Program> fivePrograms =
                LiveTvSchedule.parse(fiveRoot.toString()).channel("five").programs();
        for (int i = 0; i < fiveExpected.length; i++) {
            check(fivePrograms.get(i).videoId.equals(fiveExpected[i]),
                    "Frozen five-program shuffle vector mismatch at " + i);
        }
    }

    private static void testScheduleTimeBoundaries() {
        LiveTvSchedule schedule = LiveTvSchedule.parse(scheduleObject(EPOCH).toString());
        LiveTvSchedule.Channel office = schedule.channel("office");
        List<LiveTvSchedule.Program> order = office.programs();
        long cycle = office.cycleMs();
        check(cycle == 6000L, "Cycle is the sum of durations");
        check(order.get(0).durationMs + order.get(1).durationMs + order.get(2).durationMs == cycle,
                "Cycle duration matches each program");

        LiveTvSchedule.Slot first = office.at(EPOCH);
        check(first.channel == office, "Slot exposes its channel");
        check(first.program == order.get(0), "Epoch starts the first frozen program");
        check(first.startsAtMs == EPOCH, "First slot starts at the epoch");
        check(first.endsAtMs == EPOCH + order.get(0).durationMs, "First slot has an exact end");
        check(first.offsetMs == 0L, "First slot starts at offset zero");
        check(first.durationMs == order.get(0).durationMs, "Slot duration matches its program");

        long firstEnd = first.endsAtMs;
        LiveTvSchedule.Slot lastMillisecond = office.at(firstEnd - 1);
        check(lastMillisecond.program == order.get(0), "Time just before the end stays in the slot");
        check(lastMillisecond.startsAtMs == first.startsAtMs, "Slot start is stable");
        check(lastMillisecond.offsetMs == order.get(0).durationMs - 1,
                "Offset is derived from the requested time");

        LiveTvSchedule.Slot second = office.at(firstEnd);
        check(second.program == order.get(1), "The exact boundary starts the next program");
        check(second.startsAtMs == firstEnd, "Next start equals the previous end");
        check(second.offsetMs == 0L, "Boundary offset is zero");
        check(second.endsAtMs == firstEnd + order.get(1).durationMs, "Second end is exact");

        long midpoint = second.startsAtMs + order.get(1).durationMs / 2;
        LiveTvSchedule.Slot midway = office.at(midpoint);
        check(midway.program == order.get(1), "Midway time stays in the second program");
        check(midway.offsetMs == order.get(1).durationMs / 2, "Midway offset is exact");
        check(midway.startsAtMs == second.startsAtMs, "Midway preserves the slot start");
        check(midway.endsAtMs == second.endsAtMs, "Midway preserves the slot end");

        LiveTvSchedule.Slot last = office.at(EPOCH + cycle - 1);
        check(last.program == order.get(2), "Last millisecond is in the last program");
        check(last.endsAtMs == EPOCH + cycle, "Last slot ends at the cycle boundary");
        check(last.offsetMs == order.get(2).durationMs - 1, "Last offset reaches the cycle end");

        LiveTvSchedule.Slot wrapped = office.at(EPOCH + cycle);
        check(wrapped.program == order.get(0), "Cycle boundary wraps to the first program");
        check(wrapped.startsAtMs == EPOCH + cycle, "Wrapped slot starts a new cycle");
        check(wrapped.offsetMs == 0L, "Wrapped offset resets");
    }

    private static void testCycleWrapAndNegativeTimes() {
        LiveTvSchedule schedule = LiveTvSchedule.parse(scheduleObject(EPOCH).toString());
        LiveTvSchedule.Channel office = schedule.channel("office");
        List<LiveTvSchedule.Program> order = office.programs();
        long cycle = office.cycleMs();
        long cycleCount = 1000000000L;
        long oldStart = EPOCH - cycle * cycleCount;
        check(oldStart < 0, "Test reaches far before the epoch");

        LiveTvSchedule.Slot oldFirst = office.at(oldStart);
        check(oldFirst.program == order.get(0), "Aligned old time starts the cycle");
        check(oldFirst.startsAtMs == oldStart, "Aligned old start is exact");
        check(oldFirst.offsetMs == 0L, "Old cycle offset is zero");

        LiveTvSchedule.Slot oldLast = office.at(oldStart - 1);
        check(oldLast.program == order.get(2), "One millisecond before old epoch is the last program");
        check(oldLast.startsAtMs == oldStart - order.get(2).durationMs,
                "Old last start wraps correctly");
        check(oldLast.endsAtMs == oldStart, "Old last slot ends at the cycle boundary");
        check(oldLast.offsetMs == order.get(2).durationMs - 1, "Old last offset is correct");
    }

    private static void testNextAndChannelMismatch() {
        JSONObject root = scheduleObject(EPOCH);
        JSONArray channels = root.getJSONArray("channels");
        JSONArray otherPrograms = new JSONArray();
        otherPrograms.put(program("other:1", "Other One", 500L));
        otherPrograms.put(program("other:2", "Other Two", 700L));
        channels.put(channel("other", 99L, otherPrograms));

        LiveTvSchedule schedule = LiveTvSchedule.parse(root.toString());
        LiveTvSchedule.Channel office = schedule.channel("office");
        LiveTvSchedule.Channel other = schedule.channel("other");
        List<LiveTvSchedule.Program> order = office.programs();
        long cycle = office.cycleMs();

        LiveTvSchedule.Slot slot = office.at(EPOCH);
        long expectedStart = EPOCH;
        for (int i = 0; i < order.size() * 2; i++) {
            check(slot.program == order.get(i % order.size()),
                    "Walking the cycle keeps the frozen order at " + i);
            check(slot.startsAtMs == expectedStart,
                    "Slot start is placeable while walking at " + i);
            expectedStart += slot.durationMs;
            LiveTvSchedule.Slot next = slot.next();
            check(next.startsAtMs == slot.endsAtMs, "Slot.next starts exactly at the previous end");
            check(next.offsetMs == 0L, "Slot.next starts at offset zero");
            check(office.next(slot).startsAtMs == next.startsAtMs,
                    "Channel.next agrees with Slot.next");
            slot = next;
        }
        check(slot.program == order.get(0), "Two full cycles return to the first program");
        check(slot.startsAtMs == EPOCH + cycle * 2, "Two full cycles land on the same boundary");
        check(expectedStart == EPOCH + cycle * 2, "Walked starts advance by exactly two cycles");

        try {
            other.next(office.at(EPOCH));
            throw new AssertionError("Channel.next must reject a slot from another channel");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            office.next(null);
            throw new AssertionError("Channel.next must reject a null slot");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void testIndependentClientsAndTimeZones() {
        String json = scheduleObject(EPOCH).toString();
        LiveTvSchedule clientA = LiveTvSchedule.parse(json);
        LiveTvSchedule clientB = LiveTvSchedule.parse(json);
        long when = EPOCH + 1500L;
        LiveTvSchedule.Slot a = clientA.channel("office").at(when);
        LiveTvSchedule.Slot b = clientB.channel("office").at(when);
        check(a.program.videoId.equals(b.program.videoId), "Independent clients agree on the program");
        check(a.startsAtMs == b.startsAtMs, "Independent clients agree on the start");
        check(a.endsAtMs == b.endsAtMs, "Independent clients agree on the end");
        check(a.offsetMs == b.offsetMs, "Independent clients agree on the offset");

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            LiveTvSchedule.Slot pacific = clientA.channel("office").at(when);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"));
            LiveTvSchedule.Slot kathmandu = clientA.channel("office").at(when);
            check(pacific.program.videoId.equals(kathmandu.program.videoId),
                    "Timezone does not change the program");
            check(pacific.startsAtMs == kathmandu.startsAtMs,
                    "Timezone does not change slot starts");
            check(pacific.endsAtMs == kathmandu.endsAtMs,
                    "Timezone does not change slot ends");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static void testUnrepresentableTimes() {
        JSONArray programs = new JSONArray();
        programs.put(program("edge:1", "Edge", 1000L));
        JSONArray channels = new JSONArray();
        channels.put(channel("edge", 0L, programs));
        JSONObject root = new JSONObject()
                .put("version", 1).put("revision", "edge")
                .put("epochMs", 0L).put("channels", channels);
        LiveTvSchedule.Channel edge = LiveTvSchedule.parse(root.toString()).channel("edge");

        expectTimeInvalid(edge, Long.MIN_VALUE, "A time whose slot starts below Long.MIN is rejected");
        expectTimeInvalid(edge, Long.MAX_VALUE, "A time whose slot ends above Long.MAX is rejected");

        long nearMax = Long.MAX_VALUE - 2000L;
        LiveTvSchedule.Slot high = edge.at(nearMax);
        check(high.startsAtMs <= nearMax && nearMax < high.endsAtMs,
                "Representable near-max time still resolves");
        check(high.offsetMs == nearMax - high.startsAtMs, "Near-max offset is exact");
        check(high.endsAtMs == high.startsAtMs + high.durationMs, "Near-max end is exact");

        long nearMin = Long.MIN_VALUE + 2000L;
        LiveTvSchedule.Slot low = edge.at(nearMin);
        check(low.startsAtMs <= nearMin && nearMin < low.endsAtMs,
                "Representable near-min time still resolves");
        check(low.offsetMs == nearMin - low.startsAtMs, "Near-min offset is exact");

        LiveTvSchedule.Slot next = high.next();
        check(next.startsAtMs == high.endsAtMs, "Near-max next starts at the previous end");
        expectTimeInvalid(edge, next.endsAtMs,
                "The following slot is rejected when its end would overflow");
    }

    private static void testValidation() {
        expectInvalid((String) null, "Null manifest is rejected");
        expectInvalid("", "Empty input is rejected");
        expectInvalid("[]", "A JSON array is not a manifest");
        expectInvalid("not json", "Garbage is rejected");

        JSONObject root = scheduleObject(EPOCH);
        root.remove("version");
        expectInvalid(root, "Missing version is rejected");

        root = scheduleObject(EPOCH);
        root.put("version", 2);
        expectInvalid(root, "Unsupported version is rejected");

        root = scheduleObject(EPOCH);
        root.remove("epochMs");
        expectInvalid(root, "Missing epoch is rejected");

        root = scheduleObject(EPOCH);
        root.put("epochMs", "1700000000000");
        expectInvalid(root, "String epoch is rejected");

        root = scheduleObject(EPOCH);
        root.remove("channels");
        expectInvalid(root, "Missing channels is rejected");

        root = scheduleObject(EPOCH);
        root.put("channels", "office");
        expectInvalid(root, "Non-array channels is rejected");

        root = scheduleObject(EPOCH);
        root.put("channels", new JSONArray());
        expectInvalid(root, "Empty channels is rejected");

        root = scheduleObject(EPOCH);
        JSONArray tooManyChannels = new JSONArray();
        for (int i = 0; i < LiveTvSchedule.MAX_CHANNELS + 1; i++) {
            tooManyChannels.put(new JSONObject());
        }
        root.put("channels", tooManyChannels);
        expectInvalid(root, "Too many channels is rejected");

        root = scheduleObject(EPOCH);
        root.getJSONArray("channels").put("not an object");
        expectInvalid(root, "A non-object channel is rejected");

        root = scheduleObject(EPOCH);
        root.getJSONArray("channels").put(firstChannel(scheduleObject(EPOCH)));
        expectInvalid(root, "Duplicate channel ids are rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).remove("id");
        expectInvalid(root, "Missing channel id is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).put("id", "bad id");
        expectInvalid(root, "Whitespace in a channel id is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).put("id", repeat('a', LiveTvSchedule.MAX_CHANNEL_ID_LENGTH + 1));
        expectInvalid(root, "Oversized channel id is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).remove("name");
        expectInvalid(root, "Missing channel name is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).put("name", repeat('n', LiveTvSchedule.MAX_CHANNEL_NAME_LENGTH + 1));
        expectInvalid(root, "Oversized channel name is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).remove("seed");
        expectInvalid(root, "Missing seed is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).put("seed", 1.5);
        expectInvalid(root, "Fractional seed is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).remove("programs");
        expectInvalid(root, "Missing programs is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).put("programs", new JSONArray());
        expectInvalid(root, "Empty programs is rejected");

        root = scheduleObject(EPOCH);
        JSONArray tooManyPrograms = new JSONArray();
        for (int i = 0; i < LiveTvSchedule.MAX_PROGRAMS_PER_CHANNEL + 1; i++) {
            tooManyPrograms.put(new JSONObject());
        }
        firstChannel(root).put("programs", tooManyPrograms);
        expectInvalid(root, "Too many programs is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).getJSONArray("programs").put("not an object");
        expectInvalid(root, "A non-object program is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).remove("videoId");
        expectInvalid(root, "Missing video id is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).remove("title");
        expectInvalid(root, "Missing program title is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).remove("durationMs");
        expectInvalid(root, "Missing duration is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("durationMs", 0L);
        expectInvalid(root, "Zero duration is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("durationMs", -1L);
        expectInvalid(root, "Negative duration is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("season", -1);
        expectInvalid(root, "Negative season is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("year", -1);
        expectInvalid(root, "Negative year is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("year", LiveTvSchedule.MAX_YEAR + 1);
        expectInvalid(root, "Year above 9999 is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("year", 1999.5);
        expectInvalid(root, "Fractional year is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("year", "1999");
        expectInvalid(root, "String year is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("year", true);
        expectInvalid(root, "Boolean year is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("videoId", repeat('v', LiveTvSchedule.MAX_VIDEO_ID_LENGTH + 1));
        expectInvalid(root, "Oversized video id is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("title", repeat('t', LiveTvSchedule.MAX_TITLE_LENGTH + 1));
        expectInvalid(root, "Oversized program title is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("seriesTitle", repeat('s', LiveTvSchedule.MAX_TITLE_LENGTH + 1));
        expectInvalid(root, "Oversized seriesTitle is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("seriesTitle", 1995);
        expectInvalid(root, "Non-string numeric seriesTitle is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("seriesTitle", true);
        expectInvalid(root, "Non-string boolean seriesTitle is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("seriesTitle", new JSONArray().put("The Office"));
        expectInvalid(root, "Non-string array seriesTitle is rejected");

        root = scheduleObject(EPOCH);
        firstProgram(root).put("thumbnail",
                repeat('u', LiveTvSchedule.MAX_THUMBNAIL_LENGTH + 1));
        expectInvalid(root, "Oversized thumbnail is rejected");

        root = scheduleObject(EPOCH);
        firstChannel(root).put("thumbnail",
                repeat('u', LiveTvSchedule.MAX_THUMBNAIL_LENGTH + 1));
        expectInvalid(root, "Oversized channel artwork is rejected");

        root = scheduleObject(EPOCH);
        JSONArray duplicateVideos = firstChannel(root).getJSONArray("programs");
        duplicateVideos.put(program("tt0386676:1:1", "Pilot Again", 1000L));
        expectInvalid(root, "Duplicate video ids within a channel are rejected");

        root = scheduleObject(EPOCH);
        JSONArray crossChannelPrograms = new JSONArray();
        crossChannelPrograms.put(program("tt0386676:1:1", "Pilot Shared", 1000L));
        root.getJSONArray("channels").put(channel("second", 3L, crossChannelPrograms));
        LiveTvSchedule shared = LiveTvSchedule.parse(root.toString());
        check(shared.channels().size() == 2, "Channels may share a video id across channels");
        check(shared.channel("office").program("tt0386676:1:1") != null,
                "Office channel keeps its copy of a shared video id");
        check(shared.channel("second").program("tt0386676:1:1") != null,
                "A later sitcom channel may include the same Office episode");

        root = scheduleObject(EPOCH);
        JSONArray overflowPrograms = new JSONArray();
        overflowPrograms.put(program("overflow:1", "One", Long.MAX_VALUE / 2L + 1L));
        overflowPrograms.put(program("overflow:2", "Two", Long.MAX_VALUE / 2L + 1L));
        firstChannel(root).put("programs", overflowPrograms);
        expectInvalid(root, "A cycle whose total duration overflows is rejected");

        root = scheduleObject(EPOCH);
        root.put("revision", repeat('r', LiveTvSchedule.MAX_REVISION_LENGTH + 1));
        expectInvalid(root, "Oversized revision is rejected");

        // Revision and optional metadata may be omitted; program season and
        // episode default to zero, and missing text becomes an empty string.
        root = scheduleObject(EPOCH);
        root.remove("revision");
        JSONObject minimal = new JSONObject()
                .put("id", "minimal")
                .put("name", "Minimal")
                .put("seed", 1L)
                .put("programs", new JSONArray().put(
                        new JSONObject()
                                .put("videoId", "minimal:1")
                                .put("title", "Only")
                                .put("durationMs", 500L)));
        root.put("channels", new JSONArray().put(minimal));
        LiveTvSchedule parsed = LiveTvSchedule.parse(root.toString());
        check(parsed.revision.equals(""), "Missing revision defaults to empty");
        LiveTvSchedule.Channel minimalChannel = parsed.channel("minimal");
        check(minimalChannel.description.equals(""), "Missing channel description defaults to empty");
        check(minimalChannel.type.equals(""), "Missing channel type defaults to empty");
        check(minimalChannel.imdbId.equals(""), "Missing imdb id defaults to empty");
        LiveTvSchedule.Program only = minimalChannel.programs().get(0);
        check(only.season == 0 && only.episode == 0, "Missing season and episode default to zero");
        check(only.seriesTitle.equals(""), "Missing seriesTitle defaults to empty");
        check(only.year == 0, "Missing year defaults to zero");
        check(only.thumbnail.equals(""), "Missing thumbnail defaults to empty");
        check(only.description.equals(""), "Missing program description defaults to empty");
    }

    private static void testOptionalProgramMetadataAndMixedLineups() {
        String maxSeriesTitle = repeat('S', LiveTvSchedule.MAX_TITLE_LENGTH);

        JSONArray sitcomPrograms = new JSONArray()
                .put(program("tt0386676:2:1", "The Dundies", 1260000L)
                        .put("seriesTitle", "The Office")
                        .put("season", 2)
                        .put("episode", 1)
                        .put("year", 2005))
                .put(program("tt0098904:4:11", "The Contest", 1380000L)
                        .put("seriesTitle", "Seinfeld")
                        .put("season", 4)
                        .put("episode", 11)
                        .put("year", 1992))
                .put(program("tt1266020:3:2", "Flu Season", 1320000L)
                        .put("seriesTitle", maxSeriesTitle)
                        .put("season", 3)
                        .put("episode", 2)
                        .put("year", LiveTvSchedule.MAX_YEAR));
        JSONObject sitcoms = new JSONObject()
                .put("id", "sitcoms")
                .put("name", "Sitcoms")
                .put("description", "Mixed classic sitcoms")
                .put("type", "series")
                .put("seed", 424242L)
                .put("programs", sitcomPrograms);

        JSONArray moviePrograms = new JSONArray()
                .put(new JSONObject()
                        .put("videoId", "tt0099785")
                        .put("title", "Home Alone")
                        .put("year", 1990)
                        .put("durationMs", 6180000L)
                        .put("thumbnail", "https://example.test/tt0099785.jpg")
                        .put("description", "A constructive holiday defense"))
                .put(new JSONObject()
                        .put("videoId", "tt0319343")
                        .put("title", "Elf")
                        .put("seriesTitle", JSONObject.NULL)
                        .put("season", 0)
                        .put("episode", 0)
                        .put("year", 0)
                        .put("durationMs", 5820000L))
                .put(new JSONObject()
                        .put("videoId", "tt0097958")
                        .put("title", "Christmas Vacation")
                        .put("seriesTitle", "")
                        .put("year", JSONObject.NULL)
                        .put("durationMs", 5820000L));
        JSONObject christmasMovies = new JSONObject()
                .put("id", "christmas-movies")
                .put("name", "Christmas Movies")
                .put("type", "movie")
                .put("seed", 1225L)
                .put("programs", moviePrograms);

        JSONObject root = new JSONObject()
                .put("version", 1)
                .put("revision", "multi-channel-v1")
                .put("epochMs", EPOCH)
                .put("channels", new JSONArray().put(sitcoms).put(christmasMovies));

        LiveTvSchedule schedule = LiveTvSchedule.parse(root.toString());
        check(schedule.channels().size() == 2, "Mixed-series and movie channels parse together");

        LiveTvSchedule.Channel sitcomChannel = schedule.channel("sitcoms");
        check(sitcomChannel != null, "Sitcoms channel exists");
        check(sitcomChannel.type.equals("series"), "Mixed-show channel keeps homogeneous series type");
        check(sitcomChannel.imdbId.equals(""), "Mixed-show channel omits channel-level imdbId");
        LiveTvSchedule.Program dundies = sitcomChannel.program("tt0386676:2:1");
        LiveTvSchedule.Program contest = sitcomChannel.program("tt0098904:4:11");
        LiveTvSchedule.Program fluSeason = sitcomChannel.program("tt1266020:3:2");
        check(dundies != null && dundies.seriesTitle.equals("The Office") && dundies.year == 2005
                && dundies.season == 2 && dundies.episode == 1,
                "First show retains full IMDb:season:episode id, seriesTitle, and year");
        check(contest != null && contest.seriesTitle.equals("Seinfeld") && contest.year == 1992
                && contest.season == 4 && contest.episode == 11,
                "Second show in the same channel retains its own seriesTitle and year");
        check(fluSeason != null && fluSeason.seriesTitle.equals(maxSeriesTitle)
                && fluSeason.year == LiveTvSchedule.MAX_YEAR,
                "Boundary MAX_TITLE_LENGTH seriesTitle and MAX_YEAR=9999 are accepted");

        LiveTvSchedule.Channel movieChannel = schedule.channel("christmas-movies");
        check(movieChannel != null, "Movie channel exists");
        check(movieChannel.type.equals("movie"), "Movie channel keeps homogeneous movie type");
        check(movieChannel.imdbId.equals(""), "Movie channel omits channel-level imdbId");
        LiveTvSchedule.Program homeAlone = movieChannel.program("tt0099785");
        LiveTvSchedule.Program elf = movieChannel.program("tt0319343");
        LiveTvSchedule.Program vacation = movieChannel.program("tt0097958");
        check(homeAlone != null && homeAlone.videoId.equals("tt0099785")
                && homeAlone.season == 0 && homeAlone.episode == 0
                && homeAlone.seriesTitle.equals("") && homeAlone.year == 1990,
                "Movie program uses bare tt... videoId, defaults season/episode/seriesTitle, and keeps year");
        check(elf != null && elf.seriesTitle.equals("") && elf.year == 0
                && elf.season == 0 && elf.episode == 0,
                "Explicit zero year and null seriesTitle normalize to defaults");
        check(vacation != null && vacation.seriesTitle.equals("") && vacation.year == 0,
                "Empty seriesTitle and null year normalize to defaults");

        LiveTvSchedule.Slot movieSlot = movieChannel.at(EPOCH);
        check(movieSlot.startsAtMs == EPOCH, "Movie channel slot starts at epoch");
        check(movieSlot.next().startsAtMs == movieSlot.endsAtMs,
                "Movie channel advances across slots seamlessly");

        // Adding optional seriesTitle/year metadata must not alter seeded shuffle,
        // epoch, or cycle positions compared to an identical manifest without them.
        JSONObject withoutOptional = scheduleObject(EPOCH);
        JSONObject withOptional = scheduleObject(EPOCH);
        JSONArray enriched = firstChannel(withOptional).getJSONArray("programs");
        for (int i = 0; i < enriched.length(); i++) {
            enriched.getJSONObject(i)
                    .put("seriesTitle", "The Office")
                    .put("year", 2005);
        }
        LiveTvSchedule.Channel plain = LiveTvSchedule.parse(withoutOptional.toString()).channel("office");
        LiveTvSchedule.Channel withMeta = LiveTvSchedule.parse(withOptional.toString()).channel("office");
        check(plain.cycleMs() == withMeta.cycleMs(), "Optional metadata does not alter cycleMs");
        check(plain.epochMs == withMeta.epochMs, "Optional metadata does not alter epochMs");
        for (int i = 0; i < plain.programs().size(); i++) {
            check(plain.programs().get(i).videoId.equals(withMeta.programs().get(i).videoId),
                    "Optional metadata does not alter seeded shuffle order at " + i);
        }
        LiveTvSchedule.Slot plainSlot = plain.at(EPOCH + 2500L);
        LiveTvSchedule.Slot metaSlot = withMeta.at(EPOCH + 2500L);
        check(plainSlot.program.videoId.equals(metaSlot.program.videoId)
                && plainSlot.startsAtMs == metaSlot.startsAtMs
                && plainSlot.endsAtMs == metaSlot.endsAtMs
                && plainSlot.offsetMs == metaSlot.offsetMs,
                "Optional metadata leaves slot resolution identical");
    }

    private static void testBundledOfficeManifest() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/live-tv.json")), "UTF-8");
        LiveTvSchedule.Channel office = LiveTvSchedule.parse(json).channel("office");
        check(office != null && office.programs().size() == 195, "Bundled Office lineup is complete");
        check(office.seed == 3846676L && office.epochMs == 1767225600000L
                && office.cycleMs() == 274800000L, "Adding channels preserves the Office broadcast");
        check(office.program("tt0386676:5:14").durationMs == 22L * 60000L,
                "Lecture Circuit part one keeps its separate episode runtime");
        check(office.program("tt0386676:5:15").durationMs == 22L * 60000L,
                "Lecture Circuit part two keeps its separate episode runtime");
        check(office.program("tt0386676:4:1").durationMs == 21L * 60000L,
                "A combined Fun Run runtime is divided between its two parts");
        for (LiveTvSchedule.Program program : office.programs()) {
            check(program.thumbnail.startsWith("https://image.tmdb.org/t/p/w780/"),
                    "Every bundled episode has a still image: " + program.videoId);
        }
        LiveTvSchedule expanded = LiveTvSchedule.parse(json);
        String[] expected = {"office", "sitcoms", "christmas-movies", "comedy-movies", "cartoons"};
        for (String id : expected) {
            LiveTvSchedule.Channel channel = expanded.channel(id);
            check(channel != null && !channel.programs().isEmpty(), "Shared channel exists: " + id);
            for (LiveTvSchedule.Program program : channel.programs()) {
                check(LiveTvStreamRules.validVideoId(channel.type, program.videoId), "Typed program ID: " + program.videoId);
                check(!program.thumbnail.isEmpty(), "Program has artwork: " + program.videoId);
                if (channel.type.equals("movie")) {
                    check(program.year > 0 && program.season == 0 && program.episode == 0,
                            "Movie display metadata: " + program.videoId);
                } else if (!id.equals("office")) {
                    check(!program.seriesTitle.isEmpty(), "Mixed channel identifies the show: " + program.videoId);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        testFieldsAndImmutability();
        testDeterministicShuffle();
        testScheduleTimeBoundaries();
        testCycleWrapAndNegativeTimes();
        testNextAndChannelMismatch();
        testIndependentClientsAndTimeZones();
        testUnrepresentableTimes();
        testValidation();
        testOptionalProgramMetadataAndMixedLineups();
        testBundledOfficeManifest();
        System.out.println("PASS: Live TV manifest parsing, seeded cycles, boundaries, wrap, "
                + "client agreement, timezone independence, overflow, and validation");
    }
}
