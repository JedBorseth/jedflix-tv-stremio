package com.jedflix.test;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Read-only instrumentation probe for the installed Jedflix Live TV build.
 *
 * It reads only the target app's live-tv.json asset, loads the shipped
 * schedule and native model classes through the target ClassLoader, and
 * parses two independent schedules to prove the manifest contract is
 * deterministic. It never opens a UI, starts playback, performs network I/O,
 * reads preferences, or reads saved account credentials.
 *
 * Run it from the parent script with --run-device. Instrumentation may restart
 * the target process, as with any Android instrumentation target.
 */
public final class LiveTvProbe extends Instrumentation {
    private static final String TAG = "JedflixLiveTvProbe";
    private static final String TARGET_PACKAGE = "com.jedflix.stremio";
    private static final String ASSET = "live-tv.json";
    private static final String OFFICE_CHANNEL = "office";
    private static final int MIN_OFFICE_PROGRAMS = 190;
    private static final long MAX_ASSET_BYTES = 2 * 1024 * 1024L;
    private static final String STREAM_URL = "https://example.invalid/live-tv-test.mp4";
    private static final String STREAM_NAME = "Live TV compatibility probe";
    private static final String[] OPTIONAL_HELPERS = {
        "com.jedflix.tv.LiveTvPlayback",
        "com.jedflix.tv.LiveTvControls",
        "com.jedflix.tv.LiveTvGuide",
    };

    private ClassLoader loader;
    private int checks;
    private int helpers;
    private final StringBuilder details = new StringBuilder();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            if (!TARGET_PACKAGE.equals(getTargetContext().getPackageName())) {
                throw new AssertionError("probe targets " + getTargetContext().getPackageName()
                    + " instead of " + TARGET_PACKAGE);
            }
            String asset = readAsset();
            verifySchedule(asset);
            verifyOptionalHelpers();
            verifyPlaybackController();
            report.putString("result", "PASS: " + checks + " live TV checks");
            report.putString("details", details.toString());
            finish(-1, report);
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            Log.e(TAG, "FAIL after " + checks + " checks", cause);
            report.putString("result", "FAIL after " + checks + " checks: "
                + describe(cause));
            report.putString("details", details.toString());
            finish(0, report);
        }
    }

    private String readAsset() throws Exception {
        try (InputStream input = getTargetContext().getAssets().open(ASSET);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_ASSET_BYTES) {
                    throw new AssertionError("installed live TV asset is too large");
                }
                output.write(buffer, 0, count);
            }
            String json = new String(output.toByteArray(), "UTF-8");
            check(json.length() > 0, "installed app exposes the live TV asset");
            JSONObject root = new JSONObject(json);
            check(root.has("epochMs"), "installed asset declares a shared epoch");
            check(root.optJSONArray("channels") != null,
                "installed asset declares channels");
            details.append("assetBytes=").append(output.size());
            return json;
        }
    }

    private void verifySchedule(String json) throws Exception {
        Class<?> scheduleClass = type("com.jedflix.tv.LiveTvSchedule");
        Object first = parse(scheduleClass, json);
        Object second = parse(scheduleClass, json);
        check(first != null && second != null, "LiveTvSchedule.parse returns schedules");
        check(first != second, "the probe parsed two independent schedules");

        int supported = scheduleClass.getField("VERSION").getInt(null);
        check(supported == intField(first, "version"),
            "schedule version matches LiveTvSchedule.VERSION");
        check(stringField(first, "revision").length() > 0,
            "schedule revision is present");
        check(longField(first, "epochMs") == longField(second, "epochMs"),
            "both schedules share the manifest epoch");
        String revision = stringField(first, "revision");
        check(revision.equals(stringField(second, "revision")),
            "both schedules share the manifest revision");

        List<?> firstChannels = (List<?>) call(first, "channels");
        List<?> secondChannels = (List<?>) call(second, "channels");
        check(firstChannels != null && !firstChannels.isEmpty(),
            "schedule contains at least one channel");
        check(firstChannels.size() == secondChannels.size(),
            "both schedules contain the same channel count");

        Object officeFirst = call(first, "channel", String.class, OFFICE_CHANNEL);
        Object officeSecond = call(second, "channel", String.class, OFFICE_CHANNEL);
        check(officeFirst != null, "Office channel exists");
        check(officeSecond != null, "Office channel exists in both schedules");
        check(OFFICE_CHANNEL.equals(stringField(officeFirst, "id")),
            "Office channel keeps its id");
        check(officeFirst.getClass().getField("seed").getType() == long.class,
            "Office channel exposes its seed");
        check(longField(officeFirst, "seed") == longField(officeSecond, "seed"),
            "both Office channels share the same seed");
        check(longField(officeFirst, "epochMs") == longField(first, "epochMs"),
            "Office channel inherits the schedule epoch");

        List<?> firstPrograms = (List<?>) call(officeFirst, "programs");
        List<?> secondPrograms = (List<?>) call(officeSecond, "programs");
        check(firstPrograms != null, "Office channel exposes programs");
        check(firstPrograms.size() >= MIN_OFFICE_PROGRAMS,
            "Office channel has at least " + MIN_OFFICE_PROGRAMS + " regular episodes");
        check(firstPrograms.size() == secondPrograms.size(),
            "both Office parses keep every program");
        check(sameVideoOrder(firstPrograms, secondPrograms),
            "both parses freeze the same seeded program order");

        Set<String> videoIds = new HashSet<String>();
        boolean regularEpisodes = true;
        for (Object program : firstPrograms) {
            String videoId = stringField(program, "videoId");
            regularEpisodes &= stringField(program, "title").length() > 0
                && intField(program, "season") > 0
                && intField(program, "episode") > 0
                && longField(program, "durationMs") > 0
                && videoId.matches("tt[0-9]+:[0-9]+:[0-9]+");
            videoIds.add(videoId);
        }
        check(regularEpisodes, "Office programs are regular episodes with durations");
        check(videoIds.size() == firstPrograms.size(),
            "Office programs have unique video ids");

        long cycle = ((Number) call(officeFirst, "cycleMs")).longValue();
        check(cycle > 0, "Office channel exposes a positive cycle");
        check(cycle == ((Number) call(officeSecond, "cycleMs")).longValue(),
            "both Office parses share the cycle duration");

        verifySlots(first, second, officeFirst, officeSecond, cycle);
        verifyAllChannels(firstChannels, second);
        details.append(" revision=").append(revision)
            .append(" channels=").append(firstChannels.size())
            .append(" officePrograms=").append(firstPrograms.size())
            .append(" cycleMs=").append(cycle);
    }

    private void verifyAllChannels(List<?> channels, Object otherSchedule) throws Exception {
        Method validId = type("com.jedflix.tv.LiveTvStreamRules").getDeclaredMethod(
            "validVideoId", String.class, String.class);
        validId.setAccessible(true);
        long now = System.currentTimeMillis();
        for (Object channel : channels) {
            String id = stringField(channel, "id");
            String kind = stringField(channel, "type");
            Object other = call(otherSchedule, "channel", String.class, id);
            List<?> programs = (List<?>) call(channel, "programs");
            List<?> otherPrograms = (List<?>) call(other, "programs");
            check(sameVideoOrder(programs, otherPrograms), id + " has the same shared cycle on both clients");
            boolean metadata = !programs.isEmpty();
            for (Object program : programs) {
                metadata &= ((Boolean) callStatic(validId, kind, stringField(program, "videoId"))).booleanValue();
                metadata &= longField(program, "durationMs") > 0;
                metadata &= stringField(program, "thumbnail").startsWith("https://image.tmdb.org/");
                if ("movie".equals(kind)) {
                    metadata &= intField(program, "year") > 0 && intField(program, "season") == 0;
                } else if (!OFFICE_CHANNEL.equals(id)) {
                    metadata &= !stringField(program, "seriesTitle").isEmpty();
                }
            }
            check(metadata, id + " contains typed programs with artwork and display metadata");
            Object slot = call(channel, "at", long.class, now);
            compareSlots(slot, call(other, "at", long.class, now), id + " current slot");
            verifySlotBounds(slot, now, id + " current slot");
        }
    }

    private void verifySlots(Object first, Object second, Object officeFirst,
            Object officeSecond, long cycle) throws Exception {
        long epoch = longField(first, "epochMs");
        Method nowMethod = type("com.jedflix.tv.LiveTv").getMethod("now");
        long now = ((Number) callStatic(nowMethod)).longValue();
        check(now > 0, "LiveTv.now returns a wall-clock timestamp");

        long half = cycle / 2;
        long[] samples = {
            epoch,
            epoch + 1,
            epoch - 1,
            epoch + half,
            epoch + cycle - 1,
            epoch + cycle,
            epoch + cycle + 1,
            epoch + cycle + half,
            epoch - half,
            epoch - cycle - 1,
            now,
        };
        for (int i = 0; i < samples.length; i++) {
            long time = samples[i];
            Object firstSlot = call(officeFirst, "at", long.class, time);
            Object secondSlot = call(officeSecond, "at", long.class, time);
            String label = "sample " + i;
            compareSlots(firstSlot, secondSlot, label);
            verifySlotBounds(firstSlot, time, label);
        }

        // Explicit boundaries and the forward cycle wrap.
        Object firstAtEpoch = call(officeFirst, "at", long.class, epoch);
        Object lastAtCycleEnd = call(officeFirst, "at", long.class, epoch + cycle - 1);
        Object firstAtCycleEnd = call(officeFirst, "at", long.class, epoch + cycle);
        Object beforeEpoch = call(officeFirst, "at", long.class, epoch - 1);
        check(videoId(beforeEpoch).equals(videoId(lastAtCycleEnd)),
            "time just before the epoch wraps to the last program");
        check(longField(beforeEpoch, "endsAtMs") == epoch,
            "the wrapped slot ends exactly at the epoch");
        check(videoId(firstAtEpoch).equals(videoId(firstAtCycleEnd)),
            "the next cycle starts with the same first program");
        check(longField(firstAtCycleEnd, "startsAtMs") == epoch + cycle,
            "the next cycle starts one cycle after the epoch");
        check(longField(firstAtCycleEnd, "offsetMs") == 0,
            "the next cycle's first slot has zero offset");

        Object next = call(firstAtEpoch, "next");
        check(next != null, "Slot.next returns the following slot");
        check(longField(next, "startsAtMs") == longField(firstAtEpoch, "endsAtMs"),
            "the next slot starts where the current slot ends");
        check(longField(next, "offsetMs") == 0,
            "the next slot starts at offset zero");
        long boundary = longField(firstAtEpoch, "endsAtMs");
        check(videoId(call(officeFirst, "at", long.class, boundary - 1))
                .equals(videoId(firstAtEpoch)),
            "a slot includes the millisecond before its end");
        check(videoId(call(officeFirst, "at", long.class, boundary))
                .equals(videoId(next)),
            "a slot excludes its end boundary");
        Object wrapped = call(lastAtCycleEnd, "next");
        check(videoId(wrapped).equals(videoId(firstAtEpoch)),
            "Slot.next wraps at the cycle end");
        check(longField(wrapped, "startsAtMs") == epoch + cycle,
            "the wrapped next slot starts at the next cycle");

        // The facade must resolve the installed asset and the current slot the
        // same way as the separately parsed schedules.
        Class<?> facade = type("com.jedflix.tv.LiveTv");
        Method schedule = facade.getMethod("schedule", Context.class);
        Object facaded = callStatic(schedule, getTargetContext());
        Object facadedAgain = callStatic(schedule, getTargetContext());
        check(facaded != null, "LiveTv.schedule loads the installed manifest");
        check(facaded == facadedAgain, "LiveTv.schedule caches its schedule");
        check(longField(facaded, "epochMs") == epoch,
            "the facade uses the installed manifest epoch");
        Object facadedOffice = call(facaded, "channel", String.class, OFFICE_CHANNEL);
        Object current = call(facadedOffice, "at", long.class, now);
        Object parsedCurrent = call(officeFirst, "at", long.class, now);
        check(videoId(current).equals(videoId(parsedCurrent)),
            "the current slot matches the parsed schedule");
        verifySlotBounds(current, now, "current slot");

        verifyStreamData();
        verifyPlayerArgs();
    }

    private void compareSlots(Object first, Object second, String label) throws Exception {
        check(first != null && second != null, label + " resolves to a slot");
        check(videoId(first).equals(videoId(second)),
            label + " selects the same program");
        check(longField(first, "startsAtMs") == longField(second, "startsAtMs"),
            label + " shares the same start time");
        check(longField(first, "endsAtMs") == longField(second, "endsAtMs"),
            label + " shares the same end time");
        check(longField(first, "offsetMs") == longField(second, "offsetMs"),
            label + " shares the same offset");
        check(longField(first, "durationMs") == longField(second, "durationMs"),
            label + " shares the same duration");
    }

    private void verifySlotBounds(Object slot, long time, String label) throws Exception {
        long start = longField(slot, "startsAtMs");
        long end = longField(slot, "endsAtMs");
        long offset = longField(slot, "offsetMs");
        long duration = longField(slot, "durationMs");
        Object program = field(slot, "program");
        check(start <= time && time < end, label + " contains the sampled time");
        check(offset == time - start, label + " offset matches the sample");
        check(end - start == duration, label + " duration matches its bounds");
        check(duration == longField(program, "durationMs"),
            label + " duration matches the program");
        check(offset >= 0 && offset < duration,
            label + " offset lies inside the slot");
    }

    private void verifyStreamData() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("url", STREAM_URL);
        payload.put("name", STREAM_NAME);
        String encoded = encodeStreamData(payload.toString());

        Class<?> coreClass = type("com.stremio.core.Core");
        Object core = staticField(coreClass, "INSTANCE");
        check(core != null, "native Core instance is available");
        Object stream = call(core, "decodeStreamData", String.class, encoded);
        check(stream != null, "native decodeStreamData accepts Java zlib Base64 JSON");
        check(STREAM_NAME.equals(call(stream, "getName")),
            "decoded stream keeps its display name");
        Object source = call(stream, "getUrl");
        check(source != null, "decoded stream uses a URL source");
        check(STREAM_URL.equals(call(source, "getUrl")),
            "decoded stream keeps its URL");
        Log.i(TAG, "STREAM decode accepted; display name and URL match");
    }

    private void verifyPlayerArgs() throws Exception {
        String encoded = encodeStreamData("{\"url\":\"" + STREAM_URL
            + "\",\"name\":\"" + STREAM_NAME + "\"}");
        Class<?> argsClass = type("com.stremio.tv.views.player.PlayerFragmentArgs");
        Object companion = staticField(argsClass, "Companion");
        check(companion != null, "PlayerFragmentArgs companion is available");

        Bundle onlyStreamData = new Bundle();
        onlyStreamData.putString("streamData", encoded);
        Object args = call(companion, "fromBundle", Bundle.class, onlyStreamData);
        check(args != null, "PlayerFragmentArgs.fromBundle accepts streamData only");
        check(encoded.equals(call(args, "getStreamData")),
            "PlayerFragmentArgs keeps the stream data");
        check(call(args, "getId") == null, "stream-only args have no id");
        check(call(args, "getType") == null, "stream-only args have no type");
        check(call(args, "getVideoId") == null, "stream-only args have no video id");
        check(call(args, "getMetaAddonUrl") == null,
            "stream-only args have no meta addon URL");
        check(call(args, "getStreamAddonUrl") == null,
            "stream-only args have no stream addon URL");
        Log.i(TAG, "PLAYER_ARGS stream-only history isolation matches");
    }

    private void verifyOptionalHelpers() {
        for (String name : OPTIONAL_HELPERS) {
            try {
                loader.loadClass(name);
                helpers++;
                Log.i(TAG, "HELPER present " + name);
            } catch (ClassNotFoundException missing) {
                Log.i(TAG, "HELPER absent " + name);
            }
        }
        details.append(" helpers=").append(helpers).append('/')
            .append(OPTIONAL_HELPERS.length);
    }

    private void verifyPlaybackController() throws Throwable {
        final Class<?> facade = type("com.jedflix.tv.LiveTv");
        final Method nowMethod = facade.getMethod("now");
        final Method scheduleMethod = facade.getMethod("schedule", Context.class);
        final Object schedule = callStatic(scheduleMethod, getTargetContext());
        final Object officeChannel = call(schedule, "channel", String.class, OFFICE_CHANNEL);

        JSONObject payload = new JSONObject();
        payload.put("url", STREAM_URL);
        payload.put("name", STREAM_NAME);
        final String encoded = encodeStreamData(payload.toString());
        Class<?> coreClass = type("com.stremio.core.Core");
        Object core = staticField(coreClass, "INSTANCE");
        final Object stream = call(core, "decodeStreamData", String.class, encoded);

        final Class<?> playbackClass = type("com.jedflix.tv.LiveTvPlayback");
        final Method bindPlayerFragment = playbackClass.getMethod(
            "bindPlayerFragment", Object.class);
        final Method unbindPlayerFragment = playbackClass.getMethod(
            "unbindPlayerFragment", Object.class);
        final Method attach = playbackClass.getMethod(
            "attach", Object.class, Object.class);
        final Method detach = playbackClass.getMethod(
            "detach", Object.class);
        final Method adjustInitialPosition = playbackClass.getMethod(
            "adjustInitialPosition", Object.class, long.class);
        final Method onUserPause = playbackClass.getMethod(
            "onUserPause", Object.class);
        final Method onUserSeek = playbackClass.getMethod(
            "onUserSeek", Object.class);
        final Method goLive = playbackClass.getMethod("goLive");
        final Method onEnded = playbackClass.getMethod(
            "onEnded", Object.class);
        final Method diagnostics = playbackClass.getMethod("diagnostics");
        final Method isActive = playbackClass.getMethod("isActive");
        final Method isLive = playbackClass.getMethod("isLive");

        // If wall-clock time is extremely close to the end of the current slot,
        // wait briefly off the main thread so the synchronous controller assertions
        // stay inside a single scheduled program without triggering a transition.
        long preNow = ((Number) callStatic(nowMethod)).longValue();
        Object preSlot = call(officeChannel, "at", long.class, preNow);
        long remainingMs = longField(preSlot, "endsAtMs") - preNow;
        if (remainingMs > 0L && remainingMs < 2000L) {
            Thread.sleep(remainingMs + 50L);
        } else if (longField(preSlot, "offsetMs") < 5L) {
            Thread.sleep(10L);
        }

        final Throwable[] mainFailure = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                FakeManager cleanupManager = null;
                FakeFragment cleanupFragment = null;
                try {
                    long sampleNow = ((Number) callStatic(nowMethod)).longValue();
                    Object currentSlot = call(officeChannel, "at", long.class, sampleNow);
                    String programVideoId = videoId(currentSlot);
                    long slotStartsAtMs = longField(currentSlot, "startsAtMs");
                    long slotEndsAtMs = longField(currentSlot, "endsAtMs");
                    long slotDurationMs = longField(currentSlot, "durationMs");
                    boolean nearBoundary = (slotEndsAtMs - sampleNow) < 10000L;

                    FakePlaybackState vodState = new FakePlaybackState(0L, slotDurationMs);
                    FakePlayer vodPlayer = new FakePlayer(vodState);
                    FakeManager vodManager = new FakeManager(vodPlayer, new FakeFlow(vodState));
                    FakeFragment vodFragment = new FakeFragment(
                        getTargetContext(), buildVodBundle(encoded));

                    // 1. Clean baseline and normal VOD preservation when no live session is active.
                    callStatic(bindPlayerFragment, vodFragment);
                    check(!booleanStatic(isActive),
                        "controller is inactive before live session starts");
                    check(!booleanStatic(isLive),
                        "controller is not live when inactive");
                    check(!((Boolean) callStatic(onEnded, vodManager)).booleanValue(),
                        "onEnded returns false when no live session is active");
                    long vodInitial = ((Number) callStatic(
                        adjustInitialPosition, stream, 43210L)).longValue();
                    check(vodInitial == 43210L,
                        "adjustInitialPosition preserves normal VOD position when inactive");

                    // 2. Exiting a marked live fragment before attach clears the unbound session.
                    FakeFragment unboundLiveFragment = new FakeFragment(
                        getTargetContext(),
                        buildLiveBundle(encoded, programVideoId, slotStartsAtMs));
                    cleanupFragment = unboundLiveFragment;
                    callStatic(bindPlayerFragment, unboundLiveFragment);
                    check(booleanStatic(isActive),
                        "bindPlayerFragment activates a restored live session");
                    check(!booleanStatic(isLive),
                        "unbound restored live session is not live before attach");
                    callStatic(unbindPlayerFragment, unboundLiveFragment);
                    cleanupFragment = null;
                    check(!booleanStatic(isActive),
                        "exiting a marked live fragment before attach clears the unbound session");

                    // 3. Opening a generic normal VOD fragment clears a stale active live session.
                    FakeFragment staleLiveFragment = new FakeFragment(
                        getTargetContext(),
                        buildLiveBundle(encoded, programVideoId, slotStartsAtMs));
                    cleanupFragment = staleLiveFragment;
                    callStatic(bindPlayerFragment, staleLiveFragment);
                    check(booleanStatic(isActive),
                        "stale live fragment creates an active session");
                    callStatic(bindPlayerFragment, vodFragment);
                    cleanupFragment = null;
                    check(!booleanStatic(isActive),
                        "binding a normal VOD fragment without jedflix_live clears stale session");
                    check(!booleanStatic(isLive),
                        "binding a normal VOD fragment leaves controller not live");

                    // 4. Restored live fragment + attach + adjustInitialPosition recomputes
                    //    the current shared wall-clock offset.
                    FakeFragment liveFragment = new FakeFragment(
                        getTargetContext(),
                        buildLiveBundle(encoded, programVideoId, slotStartsAtMs));
                    FakePlaybackState state = new FakePlaybackState(0L, slotDurationMs);
                    FakePlayer player = new FakePlayer(state);
                    FakeFlow flow = new FakeFlow(state);
                    FakeManager manager = new FakeManager(player, flow);
                    cleanupFragment = liveFragment;
                    cleanupManager = manager;

                    callStatic(bindPlayerFragment, liveFragment);
                    callStatic(attach, manager, stream);
                    check(booleanStatic(isActive),
                        "attached live session is active");
                    check(booleanStatic(isLive),
                        "attached live session starts in live mode");

                    long beforeAdjustNow = ((Number) callStatic(nowMethod)).longValue();
                    Object beforeAdjustSlot = call(officeChannel, "at", long.class, beforeAdjustNow);
                    long offsetBefore = longField(beforeAdjustSlot, "offsetMs");
                    long adjusted = ((Number) callStatic(
                        adjustInitialPosition, stream, 0L)).longValue();
                    long afterAdjustNow = ((Number) callStatic(nowMethod)).longValue();
                    Object afterAdjustSlot = call(officeChannel, "at", long.class, afterAdjustNow);
                    long offsetAfter = longField(afterAdjustSlot, "offsetMs");

                    if (!nearBoundary && videoId(beforeAdjustSlot).equals(videoId(afterAdjustSlot))) {
                        check(adjusted >= offsetBefore && adjusted <= offsetAfter,
                            "adjustInitialPosition recomputes current shared offset within wall-clock bounds");
                        check(adjusted > 0L && adjusted < slotDurationMs,
                            "recomputed live offset is non-zero and inside the slot duration");
                    } else {
                        check(adjusted >= 0L && adjusted < slotDurationMs,
                            "adjustInitialPosition returns a valid in-slot offset near boundary");
                        check(booleanStatic(isActive),
                            "session remains active after boundary-tolerant adjustInitialPosition");
                    }
                    player.setTime(adjusted);

                    JSONObject liveDiag = (JSONObject) callStatic(diagnostics);
                    check(liveDiag != null && liveDiag.optBoolean("active")
                            && liveDiag.optBoolean("bound")
                            && liveDiag.optBoolean("live"),
                        "diagnostics report active, bound, and live session");
                    check(OFFICE_CHANNEL.equals(liveDiag.optString("channelId"))
                            && programVideoId.equals(liveDiag.optString("programVideoId")),
                        "diagnostics report the bound channel and program");
                    check(!liveDiag.toString().contains(STREAM_URL),
                        "diagnostics never expose stream URLs");

                    // 5. Explicit user pause leaves live (state.paused initially false, then set true).
                    check(!state.getPaused() && player.isPlaying(),
                        "fake playback state starts unpaused before explicit pause");
                    callStatic(onUserPause, player);
                    player.pause();
                    check(state.getPaused() && !player.isPlaying(),
                        "fake playback state reflects paused player after explicit pause");
                    check(booleanStatic(isActive),
                        "session remains active after explicit pause");
                    check(!booleanStatic(isLive),
                        "explicit user pause leaves live mode");
                    check("delayed".equals(
                            liveFragment.getArguments().getString("jedflix_live_mode")),
                        "explicit pause persists delayed mode in fragment arguments");

                    // 6. onUserSeek stays delayed and updates the persisted offset.
                    long seekPosition = Math.min(15000L, Math.max(1000L, slotDurationMs / 4L));
                    player.setTime(seekPosition);
                    callStatic(onUserSeek, player);
                    check(booleanStatic(isActive),
                        "session remains active after user seek");
                    check(!booleanStatic(isLive),
                        "onUserSeek keeps the session in delayed mode");
                    check(liveFragment.getArguments().getLong("jedflix_live_offset") == seekPosition,
                        "onUserSeek persists the delayed seek offset");

                    // 7. goLive on the same episode seeks FakePlayer to the current offset
                    //    and resumes playback when paused.
                    long beforeGoLiveNow = ((Number) callStatic(nowMethod)).longValue();
                    Object beforeGoLiveSlot = call(officeChannel, "at", long.class, beforeGoLiveNow);
                    long goLiveOffsetBefore = longField(beforeGoLiveSlot, "offsetMs");
                    callStatic(goLive);
                    long afterGoLiveNow = ((Number) callStatic(nowMethod)).longValue();
                    Object afterGoLiveSlot = call(officeChannel, "at", long.class, afterGoLiveNow);
                    long goLiveOffsetAfter = longField(afterGoLiveSlot, "offsetMs");

                    check(booleanStatic(isLive),
                        "goLive restores live mode on the same episode");
                    check(player.isPlaying() && !state.getPaused(),
                        "goLive calls play on a paused FakePlayer");
                    if (!nearBoundary && videoId(beforeGoLiveSlot).equals(videoId(afterGoLiveSlot))) {
                        check(player.getTime() >= goLiveOffsetBefore
                                && player.getTime() <= goLiveOffsetAfter,
                            "goLive seeks FakePlayer to current expected wall-clock offset");
                    } else {
                        check(player.getTime() >= 0L && player.getTime() < slotDurationMs,
                            "goLive seeks FakePlayer to a valid in-slot offset");
                    }

                    // 8. Live onEnded returns true and enters waiting; goLive clears waiting
                    //    when fake playback time is not at actual EOF.
                    boolean endedIntercepted = ((Boolean) callStatic(onEnded, manager)).booleanValue();
                    check(endedIntercepted,
                        "onEnded returns true for an active live session");
                    JSONObject waitingDiag = (JSONObject) callStatic(diagnostics);
                    check(waitingDiag.optBoolean("waiting"),
                        "live onEnded enters the waiting state");
                    check(booleanStatic(isActive) && !booleanStatic(isLive),
                        "waiting session stays active but isLive reports false");

                    long nonEofTime = Math.min(10000L, Math.max(1000L, slotDurationMs / 3L));
                    player.setTime(nonEofTime);
                    callStatic(goLive);
                    JSONObject clearedDiag = (JSONObject) callStatic(diagnostics);
                    check(!clearedDiag.optBoolean("waiting") && booleanStatic(isLive),
                        "goLive clears waiting state when fake time is not at actual EOF");

                    // 9. unbindPlayerFragment and detach clear the active session and stop tickers.
                    callStatic(unbindPlayerFragment, liveFragment);
                    cleanupFragment = null;
                    cleanupManager = null;
                    check(!booleanStatic(isActive) && !booleanStatic(isLive),
                        "unbindPlayerFragment clears the active live session");

                    FakeFragment detachFragment = new FakeFragment(
                        getTargetContext(),
                        buildLiveBundle(encoded, programVideoId, slotStartsAtMs));
                    cleanupFragment = detachFragment;
                    cleanupManager = manager;
                    callStatic(bindPlayerFragment, detachFragment);
                    callStatic(attach, manager, stream);
                    check(booleanStatic(isActive) && booleanStatic(isLive),
                        "re-bound and attached live session is active before detach");
                    callStatic(detach, manager);
                    callStatic(unbindPlayerFragment, detachFragment);
                    cleanupManager = null;
                    cleanupFragment = null;
                    check(!booleanStatic(isActive) && !booleanStatic(isLive),
                        "detach clears the active live session");
                } catch (Throwable failure) {
                    mainFailure[0] = failure;
                } finally {
                    try {
                        if (cleanupManager != null) callStatic(detach, cleanupManager);
                    } catch (Throwable ignored) {}
                    try {
                        if (cleanupFragment != null) callStatic(unbindPlayerFragment, cleanupFragment);
                    } catch (Throwable ignored) {}
                    try {
                        callStatic(bindPlayerFragment,
                            new FakeFragment(getTargetContext(), buildVodBundle(encoded)));
                    } catch (Throwable ignored) {}
                }
            }
        });
        if (mainFailure[0] != null) {
            throw mainFailure[0];
        }
        details.append(" controller=ok");
        Log.i(TAG, "PLAYBACK_CONTROLLER runtime seam checks passed");
    }

    private static Bundle buildLiveBundle(String encodedStream, String videoId, long slotStartsAtMs) {
        Bundle bundle = new Bundle();
        bundle.putString("streamData", encodedStream);
        bundle.putBoolean("jedflix_live", true);
        bundle.putString("jedflix_live_channel", OFFICE_CHANNEL);
        bundle.putString("jedflix_live_program", videoId);
        bundle.putString("jedflix_live_mode", "live");
        bundle.putLong("jedflix_live_slot_start", slotStartsAtMs);
        return bundle;
    }

    private static Bundle buildVodBundle(String encodedStream) {
        Bundle bundle = new Bundle();
        bundle.putString("streamData", encodedStream);
        return bundle;
    }

    private static boolean booleanStatic(Method method, Object... arguments) throws Exception {
        return ((Boolean) callStatic(method, arguments)).booleanValue();
    }

    private static String encodeStreamData(String json) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes,
                new Deflater(Deflater.DEFAULT_COMPRESSION))) {
            deflater.write(json.getBytes("UTF-8"));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static boolean sameVideoOrder(List<?> first, List<?> second)
            throws Exception {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!stringField(first.get(i), "videoId")
                    .equals(stringField(second.get(i), "videoId"))) return false;
        }
        return true;
    }

    private static String videoId(Object slot) throws Exception {
        Object program = field(slot, "program");
        return stringField(program, "videoId");
    }

    private static Object parse(Class<?> scheduleClass, String json) throws Exception {
        Method parse = scheduleClass.getMethod("parse", String.class);
        try {
            return parse.invoke(null, json);
        } catch (InvocationTargetException failure) {
            throw propagate("LiveTvSchedule.parse", failure);
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    private static Object call(Object target, String name) throws Exception {
        return invoke(target.getClass().getMethod(name), target);
    }

    private static Object call(Object target, String name, Class<?> parameter,
            Object argument) throws Exception {
        return invoke(target.getClass().getMethod(name, parameter), target, argument);
    }

    private static Object callStatic(Method method, Object... arguments) throws Exception {
        return invoke(method, null, arguments);
    }

    private static Object invoke(Method method, Object target, Object... arguments)
            throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw propagate(method.getName(), failure);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        return type.getField(name).get(null);
    }

    private static String stringField(Object target, String name) throws Exception {
        return (String) field(target, name);
    }

    private static int intField(Object target, String name) throws Exception {
        return ((Number) field(target, name)).intValue();
    }

    private static long longField(Object target, String name) throws Exception {
        return ((Number) field(target, name)).longValue();
    }

    private static AssertionError propagate(String action, InvocationTargetException failure) {
        Throwable cause = unwrap(failure);
        return new AssertionError(action + " failed: " + describe(cause), cause);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown failure";
        String message = failure.getMessage();
        if (message != null) {
            // Never surface schedules, stream payloads, or account-bearing URLs.
            message = message.replaceAll("https?://\\S+", "<redacted-url>");
        }
        return failure.getClass().getName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
        Log.i(TAG, "PASS " + description);
    }

    public static final class FakeFragment {
        private final Context context;
        private final Bundle arguments;

        public FakeFragment(Context context, Bundle arguments) {
            this.context = context;
            this.arguments = arguments;
        }

        public Bundle getArguments() {
            return arguments;
        }

        public Context getContext() {
            return context;
        }
    }

    public static final class FakeManager {
        private final FakePlayer player;
        private final FakeFlow state;

        public FakeManager(FakePlayer player, FakeFlow state) {
            this.player = player;
            this.state = state;
        }

        public FakePlayer getPlayer() {
            return player;
        }

        public FakeFlow getState() {
            return state;
        }
    }

    public static final class FakeFlow {
        private final FakePlaybackState value;

        public FakeFlow(FakePlaybackState value) {
            this.value = value;
        }

        public FakePlaybackState getValue() {
            return value;
        }
    }

    public static final class FakePlayer {
        private final FakePlaybackState state;
        private long time;
        private final long duration;
        private boolean playing = true;

        public FakePlayer(FakePlaybackState state) {
            this.state = state;
            this.time = state != null ? state.getTime() : 0L;
            this.duration = state != null ? state.getDuration() : 0L;
        }

        public long getTime() {
            return time;
        }

        public long getDuration() {
            return duration;
        }

        public boolean isPlaying() {
            return playing;
        }

        public void setTime(long time) {
            this.time = time;
            if (state != null) {
                state.setTime(time);
            }
        }

        public void play() {
            this.playing = true;
            if (state != null) {
                state.setPaused(false);
            }
        }

        public void pause() {
            this.playing = false;
            if (state != null) {
                state.setPaused(true);
            }
        }
    }

    public static final class FakePlaybackState {
        private boolean loaded = true;
        private boolean buffering = false;
        private boolean paused = false;
        private long time;
        private long duration;
        private Object error;

        public FakePlaybackState(long time, long duration) {
            this.time = time;
            this.duration = duration;
        }

        public boolean getLoaded() {
            return loaded;
        }

        public void setLoaded(boolean loaded) {
            this.loaded = loaded;
        }

        public boolean getBuffering() {
            return buffering;
        }

        public void setBuffering(boolean buffering) {
            this.buffering = buffering;
        }

        public boolean getPaused() {
            return paused;
        }

        public void setPaused(boolean paused) {
            this.paused = paused;
        }

        public long getTime() {
            return time;
        }

        public void setTime(long time) {
            this.time = time;
        }

        public long getDuration() {
            return duration;
        }

        public void setDuration(long duration) {
            this.duration = duration;
        }

        public Object getError() {
            return error;
        }

        public void setError(Object error) {
            this.error = error;
        }
    }
}
