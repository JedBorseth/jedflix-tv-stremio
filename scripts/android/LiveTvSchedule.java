package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable live TV schedule manifest.
 *
 * <p>A manifest defines one or more channels and a shared UTC epoch. Each
 * channel's programs are frozen into a deterministic cycle at parse time: the
 * manifest order is shuffled once with the channel's explicit seed, then every
 * client and every day walks the same cycle. Programs repeat forever, and a
 * slot's end is exactly the next program's start.</p>
 *
 * <p>Parsing is deliberately strict. The manifest must contain {@code version}
 * (currently {@code 1}) and {@code epochMs}; ids and text are bounded, durations
 * must be positive, channel ids and per-channel video ids must be unique, and the
 * total cycle must fit in a {@code long}. Times whose slot boundaries cannot be
 * represented as a {@code long} are rejected instead of silently overflowing.</p>
 *
 * <p>This class only depends on {@code org.json} and the Java 8 standard
 * library. It has no Android runtime dependency.</p>
 */
public final class LiveTvSchedule {
    /** Manifest schema version understood by this class. */
    public static final int VERSION = 1;

    static final int MAX_CHANNELS = 128;
    static final int MAX_PROGRAMS_PER_CHANNEL = 10000;
    static final int MAX_REVISION_LENGTH = 128;
    static final int MAX_CHANNEL_ID_LENGTH = 64;
    static final int MAX_CHANNEL_NAME_LENGTH = 128;
    static final int MAX_CHANNEL_DESCRIPTION_LENGTH = 2048;
    static final int MAX_TYPE_LENGTH = 32;
    static final int MAX_IMDB_ID_LENGTH = 32;
    static final int MAX_VIDEO_ID_LENGTH = 200;
    static final int MAX_TITLE_LENGTH = 300;
    static final int MAX_PROGRAM_DESCRIPTION_LENGTH = 4096;
    static final int MAX_THUMBNAIL_LENGTH = 2048;
    static final int MAX_YEAR = 9999;

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX_1 = 0xBF58476D1CE4E5B9L;
    private static final long MIX_2 = 0x94D049BB133111EBL;

    public final int version;
    public final String revision;
    public final long epochMs;

    private final List<Channel> channels;

    private LiveTvSchedule(int version, String revision, long epochMs, List<Channel> channels) {
        this.version = version;
        this.revision = revision;
        this.epochMs = epochMs;
        this.channels = channels;
    }

    /**
     * Parses and validates a manifest.
     *
     * @throws IllegalArgumentException if the manifest is null, malformed,
     *         unsupported, or violates any of the documented bounds
     */
    public static LiveTvSchedule parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Live TV manifest must not be null");
        }
        try {
            return readSchedule(new JSONObject(json));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            // Android's org.json declares JSONException as checked, while the
            // desktop org.json jar makes it unchecked. Catch Exception so this
            // source compiles against both without hiding validation errors.
            throw new IllegalArgumentException("Invalid Live TV manifest: " + error.getMessage(), error);
        }
    }

    /** Unmodifiable channels in manifest order. */
    public List<Channel> channels() {
        return channels;
    }

    /** Returns the channel with this id, or {@code null} when it is unknown. */
    public Channel channel(String id) {
        if (id == null) {
            return null;
        }
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            if (channel.id.equals(id)) {
                return channel;
            }
        }
        return null;
    }

    private static LiveTvSchedule readSchedule(JSONObject root) {
        long version = requiredIntegral(root, "version", "schedule");
        if (version != VERSION) {
            throw invalid("schedule.version must be " + VERSION + ", was " + version);
        }
        if (!root.has("epochMs")) {
            throw invalid("schedule.epochMs is required");
        }
        long epochMs = requiredIntegral(root, "epochMs", "schedule");

        String revision = "";
        if (root.has("revision") && !root.isNull("revision")) {
            revision = requiredText(root, "revision", MAX_REVISION_LENGTH, "schedule");
        }

        JSONArray array = requiredArray(root, "channels", "schedule");
        if (array.length() < 1 || array.length() > MAX_CHANNELS) {
            throw invalid("schedule.channels must contain 1-" + MAX_CHANNELS + " channels");
        }

        List<Channel> parsedChannels = new ArrayList<Channel>(array.length());
        Set<String> channelIds = new HashSet<String>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                throw invalid("schedule.channels[" + i + "] must be an object");
            }
            Channel channel = readChannel(object, epochMs, "channels[" + i + "]", channelIds);
            parsedChannels.add(channel);
        }
        return new LiveTvSchedule((int) version, revision, epochMs,
                Collections.unmodifiableList(parsedChannels));
    }

    private static Channel readChannel(JSONObject object, long scheduleEpochMs, String where,
            Set<String> channelIds) {
        String id = identifier(object, "id", MAX_CHANNEL_ID_LENGTH, where);
        if (!channelIds.add(id)) {
            throw invalid(where + ".id duplicates channel id " + quote(id));
        }
        String name = requiredText(object, "name", MAX_CHANNEL_NAME_LENGTH, where);
        String description = optionalText(object, "description", MAX_CHANNEL_DESCRIPTION_LENGTH, where);
        String type = optionalText(object, "type", MAX_TYPE_LENGTH, where);
        String imdbId = optionalText(object, "imdbId", MAX_IMDB_ID_LENGTH, where);
        // Presentation-only poster used on the guide's channel tile. It has no
        // effect on the frozen cycle or the broadcast timeline.
        String thumbnail = optionalText(object, "thumbnail", MAX_THUMBNAIL_LENGTH, where);
        if (imdbId.length() > 0) {
            checkIdentifier(imdbId, MAX_IMDB_ID_LENGTH, where + ".imdbId");
        }
        if (!object.has("seed")) {
            throw invalid(where + ".seed is required");
        }
        long seed = requiredIntegral(object, "seed", where);

        JSONArray array = requiredArray(object, "programs", where);
        if (array.length() < 1 || array.length() > MAX_PROGRAMS_PER_CHANNEL) {
            throw invalid(where + ".programs must contain 1-" + MAX_PROGRAMS_PER_CHANNEL + " programs");
        }

        Program[] programs = new Program[array.length()];
        Set<String> videoIds = new HashSet<String>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject program = array.optJSONObject(i);
            if (program == null) {
                throw invalid(where + ".programs[" + i + "] must be an object");
            }
            Program parsed = readProgram(program, where + ".programs[" + i + "]");
            if (!videoIds.add(parsed.videoId)) {
                throw invalid(where + ".programs[" + i + "].videoId duplicates video id "
                        + quote(parsed.videoId));
            }
            programs[i] = parsed;
        }

        // Freeze the canonical cycle once. The manifest order is not used
        // directly; the seeded shuffle makes every client agree on the lineup.
        shuffle(programs, seed);

        long totalMs = 0;
        long[] cumulativeEnds = new long[programs.length];
        List<Program> frozen = new ArrayList<Program>(programs.length);
        for (int i = 0; i < programs.length; i++) {
            Program program = programs[i];
            try {
                totalMs = Math.addExact(totalMs, program.durationMs);
            } catch (ArithmeticException overflow) {
                throw invalid(where + " total duration overflows a long");
            }
            cumulativeEnds[i] = totalMs;
            frozen.add(program);
        }
        if (totalMs <= 0) {
            throw invalid(where + " total duration must be positive");
        }

        return new Channel(id, name, description, type, imdbId, thumbnail, seed, scheduleEpochMs,
                Collections.unmodifiableList(frozen), cumulativeEnds, totalMs);
    }

    private static Program readProgram(JSONObject object, String where) {
        String videoId = identifier(object, "videoId", MAX_VIDEO_ID_LENGTH, where);
        String title = requiredText(object, "title", MAX_TITLE_LENGTH, where);
        String seriesTitle = optionalText(object, "seriesTitle", MAX_TITLE_LENGTH, where);
        String thumbnail = optionalText(object, "thumbnail", MAX_THUMBNAIL_LENGTH, where);
        String description = optionalText(object, "description", MAX_PROGRAM_DESCRIPTION_LENGTH, where);
        int season = optionalNonNegativeInt(object, "season", where);
        int episode = optionalNonNegativeInt(object, "episode", where);
        int year = optionalBoundedInt(object, "year", MAX_YEAR, where);
        long durationMs = requiredIntegral(object, "durationMs", where);
        if (durationMs <= 0) {
            throw invalid(where + ".durationMs must be positive");
        }
        return new Program(videoId, title, seriesTitle, season, episode, year, durationMs,
                thumbnail, description);
    }

    /** A channel and its frozen, repeating program cycle. */
    public static final class Channel {
        public final String id;
        public final String name;
        public final String description;
        public final String type;
        public final String imdbId;
        public final String thumbnail;
        public final long seed;
        public final long epochMs;

        private final List<Program> programs;
        private final long[] cumulativeEnds;
        private final long cycleMs;

        private Channel(String id, String name, String description, String type, String imdbId,
                String thumbnail, long seed, long epochMs, List<Program> programs,
                long[] cumulativeEnds, long cycleMs) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.imdbId = imdbId;
            this.thumbnail = thumbnail;
            this.seed = seed;
            this.epochMs = epochMs;
            this.programs = programs;
            this.cumulativeEnds = cumulativeEnds;
            this.cycleMs = cycleMs;
        }

        /** Unmodifiable programs in the frozen cycle order, starting at the epoch. */
        public List<Program> programs() {
            return programs;
        }

        /** Returns the program with this video id, or {@code null} when it is unknown. */
        public Program program(String videoId) {
            if (videoId == null) {
                return null;
            }
            for (int i = 0; i < programs.size(); i++) {
                Program program = programs.get(i);
                if (program.videoId.equals(videoId)) {
                    return program;
                }
            }
            return null;
        }

        /** Duration of one complete cycle, in milliseconds. */
        public long cycleMs() {
            return cycleMs;
        }

        /**
         * Resolves the slot that contains {@code utcMillis}.
         *
         * <p>Times before {@link #epochMs} wrap backwards through the same
         * cycle. The returned slot's start is inclusive and its end is
         * exclusive.</p>
         *
         * @throws IllegalArgumentException if either boundary of the containing
         *         slot cannot be represented as a {@code long}
         */
        public Slot at(long utcMillis) {
            BigInteger now = BigInteger.valueOf(utcMillis);
            BigInteger cycle = BigInteger.valueOf(cycleMs);
            BigInteger delta = now.subtract(BigInteger.valueOf(epochMs));
            // BigInteger.mod is always non-negative for a positive modulus,
            // which gives floorMod semantics for times before the epoch too.
            BigInteger position = delta.mod(cycle);
            long positionMs = position.longValue();
            BigInteger cycleStart = now.subtract(position);

            int low = 0;
            int high = programs.size() - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (cumulativeEnds[middle] > positionMs) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }
            int index = low;

            long startOffsetMs = index == 0 ? 0L : cumulativeEnds[index - 1];
            Program program = programs.get(index);
            BigInteger startsAt = cycleStart.add(BigInteger.valueOf(startOffsetMs));
            BigInteger endsAt = startsAt.add(BigInteger.valueOf(program.durationMs));
            long startsAtMs = checkedLong(startsAt, "slot start");
            long endsAtMs = checkedLong(endsAt, "slot end");
            return new Slot(this, program, startsAtMs, endsAtMs, utcMillis - startsAtMs,
                    program.durationMs);
        }

        /**
         * Returns the slot immediately after {@code slot} in this channel.
         *
         * @throws IllegalArgumentException if the slot belongs to another
         *         channel or the next slot cannot be represented
         */
        public Slot next(Slot slot) {
            if (slot == null) {
                throw new IllegalArgumentException("slot must not be null");
            }
            if (slot.channel != this) {
                throw new IllegalArgumentException("slot belongs to another channel");
            }
            return at(slot.endsAtMs);
        }
    }

    /** One immutable program in a channel's frozen cycle. */
    public static final class Program {
        public final String videoId;
        public final String title;
        public final String seriesTitle;
        public final int season;
        public final int episode;
        public final int year;
        public final long durationMs;
        public final String thumbnail;
        public final String description;

        private Program(String videoId, String title, String seriesTitle, int season, int episode,
                int year, long durationMs, String thumbnail, String description) {
            this.videoId = videoId;
            this.title = title;
            this.seriesTitle = seriesTitle;
            this.season = season;
            this.episode = episode;
            this.year = year;
            this.durationMs = durationMs;
            this.thumbnail = thumbnail;
            this.description = description;
        }
    }

    /** A program airing in a specific window of wall-clock time. */
    public static final class Slot {
        public final Channel channel;
        public final Program program;
        public final long startsAtMs;
        public final long endsAtMs;
        public final long offsetMs;
        public final long durationMs;

        private Slot(Channel channel, Program program, long startsAtMs, long endsAtMs,
                long offsetMs, long durationMs) {
            this.channel = channel;
            this.program = program;
            this.startsAtMs = startsAtMs;
            this.endsAtMs = endsAtMs;
            this.offsetMs = offsetMs;
            this.durationMs = durationMs;
        }

        /** The next slot, wrapping to the first program after the cycle end. */
        public Slot next() {
            return channel.next(this);
        }
    }

    private static void shuffle(Program[] programs, long seed) {
        // SplitMix64 supplies an explicit, platform-independent random stream;
        // Fisher-Yates then freezes one permutation per channel. Both the seed
        // and this algorithm are part of the broadcast contract.
        long state = seed;
        for (int i = programs.length - 1; i > 0; i--) {
            state += GOLDEN_GAMMA;
            int j = (int) Math.floorMod(mix(state), (long) (i + 1));
            Program swap = programs[i];
            programs[i] = programs[j];
            programs[j] = swap;
        }
    }

    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * MIX_1;
        z = (z ^ (z >>> 27)) * MIX_2;
        return z ^ (z >>> 31);
    }

    private static JSONArray requiredArray(JSONObject object, String key, String where) {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) {
            throw invalid(where + "." + key + " must be an array");
        }
        return (JSONArray) value;
    }

    private static String requiredText(JSONObject object, String key, int maxLength, String where) {
        Object value = object.opt(key);
        if (!(value instanceof String)) {
            throw invalid(where + "." + key + " must be a non-empty string");
        }
        String text = (String) value;
        if (text.trim().length() == 0) {
            throw invalid(where + "." + key + " must not be empty");
        }
        if (text.length() > maxLength) {
            throw invalid(where + "." + key + " must be at most " + maxLength + " characters");
        }
        return text;
    }

    private static String optionalText(JSONObject object, String key, int maxLength, String where) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (!(value instanceof String)) {
            throw invalid(where + "." + key + " must be a string");
        }
        String text = (String) value;
        if (text.length() > maxLength) {
            throw invalid(where + "." + key + " must be at most " + maxLength + " characters");
        }
        return text;
    }

    private static String identifier(JSONObject object, String key, int maxLength, String where) {
        String text = requiredText(object, key, maxLength, where);
        checkIdentifier(text, maxLength, where + "." + key);
        return text;
    }

    private static void checkIdentifier(String text, int maxLength, String what) {
        if (text.length() > maxLength) {
            throw invalid(what + " must be at most " + maxLength + " characters");
        }
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw invalid(what + " must not contain whitespace or control characters");
            }
        }
    }

    private static long requiredIntegral(JSONObject object, String key, String where) {
        Object value = object.opt(key);
        if (!isIntegral(value)) {
            throw invalid(where + "." + key + " must be an integer");
        }
        if (value instanceof BigInteger) {
            return checkedLong((BigInteger) value, where + "." + key);
        }
        return ((Number) value).longValue();
    }

    private static int optionalNonNegativeInt(JSONObject object, String key, String where) {
        return optionalBoundedInt(object, key, Integer.MAX_VALUE, where);
    }

    private static int optionalBoundedInt(JSONObject object, String key, int maxValue, String where) {
        if (!object.has(key) || object.isNull(key)) {
            return 0;
        }
        long value = requiredIntegral(object, key, where);
        if (value < 0 || value > maxValue) {
            throw invalid(where + "." + key + " must be between 0 and " + maxValue);
        }
        return (int) value;
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger;
    }

    private static long checkedLong(BigInteger value, String what) {
        // longValueExact was added in Android 12. bitLength also handles the
        // negative Long.MIN_VALUE endpoint and works on every supported TV.
        if (value.bitLength() > 63) {
            throw invalid(what + " is outside the representable time range");
        }
        return value.longValue();
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid Live TV manifest: " + message);
    }
}
