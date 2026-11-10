package com.jedflix.tv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.Deflater;
import org.json.JSONObject;

/**
 * Drives one shared live TV broadcast through the stock stream-only player.
 *
 * <p>The guide calls {@link #tune(Object, LiveTvSchedule.Channel, Listener)} with
 * a channel. This class resolves the scheduled episode through the user's
 * configured Comet source, launches the built-in player with only stream data,
 * and then follows the deterministic wall-clock schedule: joining midway,
 * correcting slow drift, and moving to the next scheduled episode at the real
 * broadcast boundary. User pause and seek actions leave the broadcast and stay
 * delayed until Go Live is pressed.</p>
 *
 * <p>Normal video-on-demand playback is untouched: the playback hooks only
 * match a live session to the exact resolved stream URL (or the exact display
 * name this class generated for a restored process). No URL, account data or
 * credential is ever logged or exposed through diagnostics.</p>
 */
public final class LiveTvPlayback {
    /** Guide callback; invoked on the main thread only while a tune/session lives. */
    public interface Listener {
        void onStatus(String message, boolean loading);
    }

    private static final String TAG = "JedflixLiveTv";
    private static final Object LOCK = new Object();

    private static final String KEY_STREAM = "streamData";
    private static final String KEY_LIVE = "jedflix_live";
    private static final String KEY_CHANNEL = "jedflix_live_channel";
    private static final String KEY_PROGRAM = "jedflix_live_program";
    private static final String KEY_MODE = "jedflix_live_mode";
    private static final String KEY_OFFSET = "jedflix_live_offset";
    private static final String KEY_SLOT_START = "jedflix_live_slot_start";

    private static final long TICK_MS = 750L;
    // A live stream is allowed to sit up to 10 seconds off the wall clock.
    // Tight thresholds turned normal buffer stalls into repeated forward seeks,
    // which caused more buffering and visible skipping. Ten seconds keeps every
    // client together while letting the player recover on its own.
    private static final long DRIFT_LIVE_MS = 10000L;
    private static final long DRIFT_FIRST_MS = 10000L;
    private static final long USER_GRACE_MS = 2500L;
    private static final long SEEK_COOLDOWN_MS = 10000L;
    private static final long NAV_TIMEOUT_MS = 25000L;
    private static final long TRANSITION_TIMEOUT_MS = 75000L;
    private static final long RETRY_MS = 6000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jedflix-live-tv");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static long generation;
    private static Session session;
    private static Launch pending;
    private static LiveTvStreams resolver;
    private static boolean resolving;
    private static WeakReference<Object> tuneOwner = new WeakReference<Object>(null);
    private static WeakReference<Object> navController = new WeakReference<Object>(null);
    private static WeakReference<Object> playerFragment = new WeakReference<Object>(null);
    // Held only for the lifetime of one tune/session so the guide receives its
    // status callbacks; cleared on cancel/teardown (never retained permanently).
    private static Listener listener;
    private static String lastStatus = "";
    private static boolean lastLoading;
    private static boolean tickerScheduled;
    private static boolean watchdogScheduled;

    private LiveTvPlayback() {}

    // ---------------------------------------------------------------- guide API

    /** Resolves the current episode and opens the stock built-in player. */
    public static void tune(final Object fragment, final LiveTvSchedule.Channel channel,
            final Listener statusListener) {
        if (fragment == null || channel == null) return;
        runOnMain(new Runnable() {
            @Override public void run() { startTune(fragment, channel, statusListener); }
        });
    }

    /** Cancels a tune that has not reached an attached live player yet. */
    public static void cancelTune(final Object fragment) {
        runOnMain(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    Object owner = tuneOwner.get();
                    if (owner != null && fragment != null && owner != fragment) return;
                    // A bound player owns its own teardown (release -> detach); the
                    // guide's view/fragment lifecycle must not stop active playback
                    // or invalidate an in-flight next-program transition. Once a
                    // navigation is armed, the player page owns the session too.
                    if (session != null && session.bound) {
                        // The player owns teardown. The guide that started this
                        // tune is going away, so stop holding its callback/view.
                        listener = null;
                        return;
                    }
                    if (pending != null) {
                        listener = null;
                        return;
                    }
                    generation++;
                    cancelResolverLocked();
                    pending = null;
                    session = null;
                    listener = null;
                    clearStatusLocked();
                }
                stopTickerLocked();
            }
        });
    }

    /** Returns whether a live session exists (bound or still opening). */
    public static boolean isActive() {
        synchronized (LOCK) { return session != null || pending != null; }
    }

    /** Returns whether the broadcast is currently followed live. */
    public static boolean isLive() {
        synchronized (LOCK) {
            Session s = session;
            return s != null && s.bound && s.live && !s.waiting && !s.loading && !s.failed;
        }
    }

    /** Re-joins the current wall-clock episode, or moves to the current one. */
    public static void goLive() {
        runOnMain(new Runnable() {
            @Override public void run() { goLiveMain(); }
        });
    }

    /** Sanitized state for parent verification. Contains no stream URLs. */
    public static JSONObject diagnostics() {
        JSONObject root = new JSONObject();
        synchronized (LOCK) {
            Session s = session;
            Launch p = pending;
            put(root, "active", s != null || p != null);
            put(root, "bound", s != null && s.bound);
            put(root, "live", s != null && s.live);
            put(root, "waiting", s != null && s.waiting);
            put(root, "loading", s != null && s.loading);
            put(root, "failed", s != null && s.failed);
            put(root, "pending", p != null);
            put(root, "generation", generation);
            put(root, "status", s != null ? s.status : lastStatus);
            put(root, "revision", revisionValue());
            if (s != null) {
                put(root, "channelId", s.channel.id);
                put(root, "channelName", s.channel.name);
                if (s.slot != null) {
                    put(root, "programVideoId", s.slot.program.videoId);
                    put(root, "programTitle", s.slot.program.title);
                    put(root, "slotStartsAtMs", s.slot.startsAtMs);
                    put(root, "slotEndsAtMs", s.slot.endsAtMs);
                    put(root, "slotOffsetMs", s.slot.offsetMs);
                    put(root, "slotDurationMs", s.slot.durationMs);
                }
                put(root, "mediaTimeMs", s.lastMediaTimeMs);
                put(root, "mediaDurationMs", s.lastDurationMs);
                put(root, "lastError", s.error == null ? "" : s.error);
            }
        }
        return root;
    }

    // -------------------------------------------------------------- playback hooks

    /**
     * Applied from PlaybackManager.createPlayer. Returns the initial media time
     * for a live session and leaves every other stream untouched.
     */
    public static long adjustInitialPosition(Object stream, long initialPosition) {
        synchronized (LOCK) {
            String url = streamUrl(stream);
            String name = streamName(stream);
            Session s = session;
            Launch p = pending;

            if (p != null && p.generation == generation && matches(url, name, p.expectedUrl, p.displayName)) {
                s = ensureSessionLocked(p);
                s.slot = p.slot;
                s.live = p.live;
                s.displayName = p.displayName;
                s.expectedUrl = p.expectedUrl;
                s.streamKey = p.streamKey;
                s.durationChecked = false;
                s.waiting = false;
                s.loading = false;
                s.failed = false;
                s.delayedPositionValid = !p.live;
                if (!p.live) s.delayedPositionMs = p.startOffsetMs;
                s.restored = false;
                pending = null;
                long now = LiveTv.now();
                if (p.live) {
                    LiveTvSchedule.Slot current = safeAt(p.channel, now);
                    if (current != null && current.program.videoId.equals(p.slot.program.videoId)) {
                        s.slot = current;
                        return Math.max(0, current.offsetMs);
                    }
                    // The schedule crossed while the player was starting; use the
                    // normal replacement path rather than an offset in the wrong file.
                    beginLiveTransitionLocked(s, current != null ? current : p.slot);
                    return initialPosition;
                }
                return Math.max(0, p.startOffsetMs);
            }

            if (s != null && s.expectedUrl != null && matches(url, name, s.expectedUrl, s.displayName)) {
                // Engine recreation for the same live session. Recompute live
                // offset at the last possible moment.
                if (s.live) {
                    LiveTvSchedule.Slot current = safeAt(s.channel, LiveTv.now());
                    if (current != null && current.program.videoId.equals(s.slot.program.videoId)) {
                        s.slot = current;
                        return Math.max(0, current.offsetMs);
                    }
                    beginLiveTransitionLocked(s, current != null ? current : s.slot);
                    return initialPosition;
                }
                return s.delayedPositionValid ? Math.max(0, s.delayedPositionMs) : initialPosition;
            }

            if (s != null && s.restored
                    && (s.expectedUrl == null || matches(url, name, s.expectedUrl, s.displayName))) {
                if (url != null) s.expectedUrl = url;
                s.displayName = s.displayName == null ? name : s.displayName;
                s.restored = false;
                if (s.live) {
                    LiveTvSchedule.Slot current = safeAt(s.channel, LiveTv.now());
                    if (current != null && current.program.videoId.equals(s.slot.program.videoId)) {
                        s.slot = current;
                        return Math.max(0, current.offsetMs);
                    }
                    beginLiveTransitionLocked(s, current != null ? current : s.slot);
                    return initialPosition;
                }
                return s.delayedPositionValid ? Math.max(0, s.delayedPositionMs) : initialPosition;
            }
            return initialPosition;
        }
    }

    /** Applied from PlaybackManager.update before the player is created. */
    public static void attach(Object manager, Object stream) {
        if (manager == null || stream == null) return;
        synchronized (LOCK) {
            String url = streamUrl(stream);
            String name = streamName(stream);
            if (url == null) return;

            Launch p = pending;
            if (p != null && p.generation == generation && matches(url, name, p.expectedUrl, p.displayName)) {
                Session s = ensureSessionLocked(p);
                s.manager = manager;
                s.bound = true;
                s.boundAtMs = LiveTv.now();
                s.expectedUrl = p.expectedUrl;
                s.displayName = p.displayName;
                s.slot = p.slot;
                s.live = p.live;
                s.streamKey = p.streamKey;
                s.durationChecked = false;
                s.waiting = false;
                s.loading = false;
                s.failed = false;
                s.aligned = false;
                s.retrySlot = null;
                s.transitionStartedAtMs = 0L;
                scheduleTickerLocked();
                scheduleWatchdogLocked();
                publishLocked(p.live ? "Live" : "Delayed", false);
                return;
            }

            Session s = session;
            if (s != null && s.restored && s.manager == null) {
                s.manager = manager;
                s.bound = true;
                s.boundAtMs = LiveTv.now();
                s.expectedUrl = url;
                s.restored = false;
                if (s.displayName == null) s.displayName = name;
                scheduleTickerLocked();
                scheduleWatchdogLocked();
                publishLocked(s.live ? "Live" : "Delayed", false);
                return;
            }

            if (s != null && matches(url, name, s.expectedUrl, s.displayName)) {
                s.manager = manager;
                s.bound = true;
                s.boundAtMs = LiveTv.now();
                scheduleTickerLocked();
                scheduleWatchdogLocked();
            }
        }
    }

    /**
     * Applied from PlaybackManager.release. Clears a finished session unless a
     * player-to-player replacement is already armed.
     */
    public static void detach(Object manager) {
        synchronized (LOCK) {
            Session s = session;
            if (s == null || s.manager != manager) return;
            if (pending != null) {
                // The next live page is replacing this one; keep the session but
                // stop touching the released engine.
                s.manager = null;
                s.bound = false;
                stopTickerLocked();
                return;
            }
            teardownLocked();
        }
    }

    /** Applied from PlaybackManager$playerListener$1.onEnded; true suppresses stock. */
    public static boolean onEnded(Object manager) {
        synchronized (LOCK) {
            Session s = session;
            if (s == null || !s.bound || s.manager != manager || s.failed) return false;
            if (s.loading || pending != null) return true;
            if (s.live) {
                if (s.waiting) return true;
                s.waiting = true;
                publishLocked("Waiting for the next program", false);
                return true;
            }
            if (s.loading) return true;
            LiveTvSchedule.Slot next;
            try { next = s.slot.next(); }
            catch (Throwable invalid) { return true; }
            s.retrySlot = next;
            s.retryAtMs = 0L;
            beginDelayedTransitionLocked(s, next);
            return true;
        }
    }

    /** Applied from the explicit pause toggle and media-session pause. */
    public static void onUserPause(Object player) {
        synchronized (LOCK) {
            Session s = session;
            if (s == null || !s.bound || s.manager == null) return;
            Object current = playerForLocked(s);
            if (player == null || current == null || current != player) return;
            long now = LiveTv.now();
            s.lastUserActionAtMs = now;
            Playback playback = readPlayback(s.manager);
            boolean wasPaused = playback != null && playback.paused;
            if (s.live && !wasPaused) {
                markDelayedLocked(s, now, playback);
            } else if (s.live && wasPaused) {
                // Resuming after an automatic (background) pause. Stay live when
                // close to the broadcast; otherwise treat it as an explicit delay.
                LiveTvSchedule.Slot live = safeAt(s.channel, now);
                if (playback != null && live != null
                        && live.program.videoId.equals(s.slot.program.videoId)
                        && Math.abs(playback.time - live.offsetMs) > 5000L) {
                    markDelayedLocked(s, now, playback);
                }
            } else if (!s.live && playback != null) {
                s.delayedPositionMs = Math.max(0, playback.time);
                s.delayedPositionValid = true;
            }
        }
    }

    /** Applied from the timeline, restart, chapter and media-session seek paths. */
    public static void onUserSeek(final Object player) {
        synchronized (LOCK) {
            final Session s = session;
            if (s == null || !s.bound || s.manager == null) return;
            Object current = playerForLocked(s);
            if (player == null || current == null || current != player) return;
            long now = LiveTv.now();
            s.lastUserActionAtMs = now;
            Playback playback = readPlayback(s.manager);
            if (s.live) {
                markDelayedLocked(s, now, playback);
            } else if (playback != null) {
                s.delayedPositionMs = Math.max(0, playback.time);
                s.delayedPositionValid = true;
                persistPlayerPositionLocked(s);
            }
            // Native callbacks notify us immediately before setTime(). Capture
            // the resulting position on the next main-loop turn, including
            // paused seeks where playback may never advance another frame.
            main.post(new Runnable() {
                @Override public void run() {
                    synchronized (LOCK) {
                        if (session != s || s.live || playerForLocked(s) != player) return;
                        Object time = call(player, "getTime");
                        if (!(time instanceof Number)) return;
                        s.delayedPositionMs = Math.max(0, ((Number) time).longValue());
                        s.delayedPositionValid = true;
                        persistPlayerPositionLocked(s);
                    }
                }
            });
        }
    }

    /**
     * Applied from PlayerFragment.onCreateView. Keeps a weak navigation handle
     * and reconstructs the live session after process death from the bundle
     * fields this class wrote when it navigated.
     */
    public static void bindPlayerFragment(Object fragment) {
        if (fragment == null) return;
        Object nav = findNavController(fragment);
        Object args = call(fragment, "getArguments");
        synchronized (LOCK) {
            if (nav != null) navController = new WeakReference<Object>(nav);
            if (!(args instanceof Bundle)) return;
            Bundle bundle = (Bundle) args;
            // A normal player destination always ends any stale tune, including
            // leaving Live TV before its asynchronous player attachment.
            if (!bundle.getBoolean(KEY_LIVE, false)) {
                if (session != null || pending != null || resolving) teardownLocked();
                playerFragment.clear();
                return;
            }
            playerFragment = new WeakReference<Object>(fragment);
            if (session != null || pending != null) return;
            String channelId = bundle.getString(KEY_CHANNEL);
            String videoId = bundle.getString(KEY_PROGRAM);
            if (channelId == null || videoId == null) return;
            Context context = fragmentContext(fragment);
            if (context == null) return;
            LiveTvSchedule schedule;
            try { schedule = LiveTv.schedule(context); }
            catch (Exception invalid) { return; }
            LiveTvSchedule.Channel channel = schedule.channel(channelId);
            if (channel == null) return;
            boolean live = !"delayed".equals(bundle.getString(KEY_MODE));
            LiveTvSchedule.Slot slot = safeAt(channel, bundle.getLong(KEY_SLOT_START, LiveTv.now()));
            if (slot == null || !slot.program.videoId.equals(videoId))
                slot = canonicalSlot(channel, videoId);
            if (live) {
                LiveTvSchedule.Slot current = safeAt(channel, LiveTv.now());
                if (current != null && current.program.videoId.equals(videoId)) slot = current;
            }
            if (slot == null) return;
            Session s = new Session(generation, channel, null);
            s.slot = slot;
            s.live = live;
            s.restored = true;
            s.expectedUrl = null;
            s.delayedPositionMs = Math.max(0, bundle.getLong(KEY_OFFSET, 0L));
            s.delayedPositionValid = true;
            s.status = live ? "Live" : "Delayed";
            session = s;
        }
    }

    /** Ends an unbound tune too, while preserving an armed replacement page. */
    public static void unbindPlayerFragment(Object fragment) {
        synchronized (LOCK) {
            if (playerFragment.get() != fragment) return;
            Bundle args = (Bundle) call(fragment, "getArguments");
            if (pending != null && args != null
                    && !pending.encoded.equals(args.getString(KEY_STREAM))) return;
            playerFragment.clear();
            teardownLocked();
        }
    }

    // -------------------------------------------------------------- live control UI

    /** Label used by the stock player's Go Live control. */
    static String controlLabel() {
        synchronized (LOCK) {
            Session s = session;
            if (s == null) return "";
            if (s.loading) return "Tuning";
            if (s.waiting) return "Waiting";
            return s.live ? "LIVE" : "Go Live";
        }
    }

    /** Whether the stock player's Go Live control should be actionable. */
    static boolean controlActionable() {
        synchronized (LOCK) {
            Session s = session;
            return s != null && s.bound && !s.live && !s.loading && !s.waiting && !s.failed;
        }
    }

    /** Recomposition token; bumped whenever control text or state changes. */
    static int revision() { return revisionValue(); }

    // ---------------------------------------------------------------- tuning flow

    private static Set<String> snapshotExclusionsLocked(Session s) {
        return s == null ? new HashSet<String>() : new HashSet<String>(s.rejectedStreams);
    }

    private static void startTune(Object fragment, LiveTvSchedule.Channel channel,
            Listener statusListener) {
        long gen;
        LiveTvStreams streams = new LiveTvStreams();
        synchronized (LOCK) {
            generation++;
            gen = generation;
            cancelResolverLocked();
            pending = null;
            session = null;
            resolving = true;
            resolver = streams;
            tuneOwner = new WeakReference<Object>(fragment);
            listener = statusListener;
            navController = new WeakReference<Object>(findNavController(fragment));
            lastStatus = "";
            lastLoading = false;
            stopTickerLocked();
        }
        publish("Tuning " + channel.name, true);
        scheduleWatchdogLocked();
        final long token = gen;
        worker.execute(new Runnable() {
            @Override public void run() { resolveInitial(token, channel, streams); }
        });
    }

    private static void resolveInitial(long gen, LiveTvSchedule.Channel channel, LiveTvStreams streams) {        try {
            LiveTvSchedule.Slot slot = channel.at(LiveTv.now());
            JSONObject raw = null;
            Set<String> excluded;
            synchronized (LOCK) { excluded = snapshotExclusionsLocked(null); }
            for (int attempt = 0; attempt < 4; attempt++) {
                raw = streams.resolve(CometConfig.manifestUrl(), channel.type, slot.program.videoId, excluded);
                LiveTvSchedule.Slot after = channel.at(LiveTv.now());
                if (after.program.videoId.equals(slot.program.videoId)) break;
                slot = after;
                raw = null;
            }
            if (raw == null) throw new IOException("This channel is changing programs. Try again.");
            final JSONObject resolved = raw;
            final LiveTvSchedule.Slot target = slot;
            main.post(new Runnable() {
                @Override public void run() {
                    finishResolve(gen, null, 0L, channel, target, resolved, true, false, 0L);
                }
            });
        } catch (Throwable error) {
            final String message = safeMessage(error);
            main.post(new Runnable() {
                @Override public void run() { failResolve(gen, null, 0L, message); }
            });
        }
    }

    private static void beginLiveTransitionLocked(final Session s, LiveTvSchedule.Slot target) {
        if (s == null || s.loading || s.failed) return;
        if (target == null) return;
        if (!target.program.videoId.equals(s.slot.program.videoId)) {
            // A new episode is a new set of releases; previous rejections do not apply.
            s.rejectedProgramId = target.program.videoId;
            s.rejectedStreams.clear();
        }
        s.loading = true;
        s.waiting = false;
        s.transitionId++;
        s.transitionStartedAtMs = LiveTv.now();
        final long transition = s.transitionId;
        final long gen = generation;
        LiveTvStreams streams = new LiveTvStreams();
        resolver = streams;
        publishLocked("Tuning the next program", true);
        scheduleWatchdogLocked();
        worker.execute(new Runnable() {
            @Override public void run() { resolveLiveTransition(gen, s, transition, streams); }
        });
    }

    private static void resolveLiveTransition(long gen, final Session expected, final long transition,
            LiveTvStreams streams) {
        try {
            LiveTvSchedule.Channel channel = expected.channel;
            LiveTvSchedule.Slot slot = channel.at(LiveTv.now());
            JSONObject raw = null;
            Set<String> excluded;
            synchronized (LOCK) { excluded = snapshotExclusionsLocked(expected); }
            for (int attempt = 0; attempt < 4; attempt++) {
                raw = streams.resolve(CometConfig.manifestUrl(), channel.type, slot.program.videoId, excluded);
                LiveTvSchedule.Slot after = channel.at(LiveTv.now());
                if (after.program.videoId.equals(slot.program.videoId)) break;
                slot = after;
                raw = null;
            }
            if (raw == null) throw new IOException("This channel is changing programs. Try again.");
            final JSONObject resolved = raw;
            final LiveTvSchedule.Slot target = slot;
            main.post(new Runnable() {
                @Override public void run() {
                    finishResolve(gen, expected, transition, expected.channel, target, resolved,
                            true, true, 0L);
                }
            });
        } catch (Throwable error) {
            final String message = safeMessage(error);
            main.post(new Runnable() {
                @Override public void run() { failResolve(gen, expected, transition, message); }
            });
        }
    }

    private static void beginDelayedTransitionLocked(final Session s, LiveTvSchedule.Slot target) {
        if (s == null || s.loading || s.failed) return;
        s.loading = true;
        s.waiting = false;
        s.transitionId++;
        s.transitionStartedAtMs = LiveTv.now();
        final long transition = s.transitionId;
        final long gen = generation;
        final LiveTvSchedule.Slot fixedTarget = target;
        LiveTvStreams streams = new LiveTvStreams();
        resolver = streams;
        publishLocked("Tuning the next program", true);
        scheduleWatchdogLocked();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject raw = streams.resolve(CometConfig.manifestUrl(),
                            s.channel.type, fixedTarget.program.videoId);
                    final JSONObject resolved = raw;
                    main.post(new Runnable() {
                        @Override public void run() {
                            finishResolve(gen, s, transition, s.channel, fixedTarget, resolved,
                                    false, true, 0L);
                        }
                    });
                } catch (Throwable error) {
                    final String message = safeMessage(error);
                    main.post(new Runnable() {
                        @Override public void run() { failResolve(gen, s, transition, message); }
                    });
                }
            }
        });
    }

    private static void finishResolve(long gen, Session expected, long transition,
            LiveTvSchedule.Channel channel, LiveTvSchedule.Slot slot, JSONObject raw,
            boolean live, boolean replace, long startOffsetMs) {
        String url = rawUrl(raw);
        String encoded;
        String displayName;
        synchronized (LOCK) {
            if (gen != generation) return;
            if (expected == null) {
                if (!resolving) return;
                resolving = false;
            } else if (session != expected || expected.transitionId != transition) {
                return;
            }
            resolver = null;
            if (url == null) {
                if (expected != null) {
                    expected.loading = false;
                    expected.transitionStartedAtMs = 0L;
                }
                publishLocked("This channel is unavailable right now.", false);
                return;
            }
            JSONObject stream = buildStream(raw, channel, slot);
            if (stream == null) {
                if (expected != null) {
                    expected.loading = false;
                    expected.transitionStartedAtMs = 0L;
                }
                publishLocked("This channel is unavailable right now.", false);
                return;
            }
            encoded = encodeStream(stream);
            displayName = stream.optString("name", "");
            if (encoded == null) {
                if (expected != null) {
                    expected.loading = false;
                    expected.transitionStartedAtMs = 0L;
                }
                publishLocked("This channel is unavailable right now.", false);
                return;
            }
            pending = new Launch(gen, channel, slot, live, replace, startOffsetMs, url, displayName,
                    encoded, LiveTvStreamRules.key(raw));
        }
        if (!navigate(channel, slot, encoded, live, replace, startOffsetMs)) {
            synchronized (LOCK) {
                pending = null;
                if (expected != null) {
                    expected.loading = false;
                    expected.transitionStartedAtMs = 0L;
                    expected.retryAtMs = LiveTv.now() + RETRY_MS;
                }
            }
            publish("Live TV could not start. Try again.", false);
        }
    }

    private static void failResolve(long gen, Session expected, long transition, String message) {
        synchronized (LOCK) {
            if (gen != generation) return;
            if (expected == null) {
                if (!resolving) return;
                resolving = false;
                resolver = null;
            } else {
                if (session != expected || expected.transitionId != transition) return;
                expected.loading = false;
                expected.transitionStartedAtMs = 0L;
                expected.retryAtMs = LiveTv.now() + RETRY_MS;
                expected.error = message;
            }
            publishLocked(message, false);
        }
    }

    // ---------------------------------------------------------------- navigation

    private static boolean navigate(LiveTvSchedule.Channel channel, LiveTvSchedule.Slot slot,
            String encoded, boolean live, boolean replace, long startOffsetMs) {
        Object nav = navController.get();
        if (nav == null) return false;
        Object owner = tuneOwner.get();
        if (!replace && owner != null && !isAdded(owner)) return false;
        Context context = navContext(nav);
        if (context == null) return false;
        int playerId = context.getResources().getIdentifier("player", "id", context.getPackageName());
        if (playerId == 0) return false;

        Bundle args = new Bundle();
        args.putString(KEY_STREAM, encoded);
        args.putBoolean(KEY_LIVE, true);
        args.putString(KEY_CHANNEL, channel.id);
        args.putString(KEY_PROGRAM, slot.program.videoId);
        args.putString(KEY_MODE, live ? "live" : "delayed");
        args.putLong(KEY_OFFSET, Math.max(0, startOffsetMs));
        args.putLong(KEY_SLOT_START, slot.startsAtMs);
        try {
            if (replace) {
                Object options = navOptions(playerId);
                if (options != null) {
                    nav.getClass().getMethod("navigate", int.class, Bundle.class,
                            Class.forName("androidx.navigation.NavOptions"))
                            .invoke(nav, playerId, args, options);
                } else {
                    popPlayer(nav, playerId);
                    nav.getClass().getMethod("navigate", int.class, Bundle.class)
                            .invoke(nav, playerId, args);
                }
            } else {
                nav.getClass().getMethod("navigate", int.class, Bundle.class)
                        .invoke(nav, playerId, args);
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Live TV navigation failed");
            return false;
        }
    }

    private static Object navOptions(int playerId) {
        try {
            Class<?> builderType = Class.forName("androidx.navigation.NavOptions$Builder");
            Object builder = builderType.getConstructor().newInstance();
            builderType.getMethod("setPopUpTo", int.class, boolean.class)
                    .invoke(builder, playerId, true);
            return builderType.getMethod("build").invoke(builder);
        } catch (Throwable error) { return null; }
    }

    private static void popPlayer(Object nav, int playerId) {
        try {
            nav.getClass().getMethod("popBackStack", int.class, boolean.class)
                    .invoke(nav, playerId, true);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- go live

    private static void goLiveMain() {
        Session s;
        synchronized (LOCK) { s = session; }
        if (s == null) return;
        long now = LiveTv.now();
        LiveTvSchedule.Slot target = safeAt(s.channel, now);
        if (target == null) return;
        boolean same;
        synchronized (LOCK) { same = target.program.videoId.equals(s.slot.program.videoId); }

        if (same && s.bound && s.manager != null) {
            Playback playback = readPlayback(s.manager);
            boolean ended = playback != null && playback.duration > 0
                    && playback.time >= playback.duration - 1200L;
            if (ended && now < target.endsAtMs) {
                // The file ended before the broadcast boundary; never advance the
                // channel early. Keep showing the waiting status.
                synchronized (LOCK) {
                    s.waiting = true;
                    publishLocked("Waiting for the next program", false);
                }
                return;
            }
            synchronized (LOCK) {
                s.live = true;
                s.waiting = false;
                s.loading = false;
                s.failed = false;
                s.slot = target;
                s.delayedPositionValid = false;
                s.lastUserActionAtMs = 0L;
                s.lastSetTimeAtMs = 0L;
                s.aligned = false;
                s.retrySlot = null;
                s.endObservedAtMs = 0L;
                s.delayedPositionMs = target.offsetMs;
                persistPlayerPositionLocked(s);
                publishLocked("Live", false);
            }
            Object player = playerForLocked(s);
            if (player != null) {
                long offset = Math.max(0, target.offsetMs);
                // Already close enough to the broadcast: do not seek and throw
                // away a healthy buffer just to shave off a second.
                if (playback == null
                        || Math.abs(playback.time - offset) > DRIFT_LIVE_MS) {
                    setTime(player, offset);
                }
                if (playback != null && playback.paused) play(player);
            }
            return;
        }
        if (!same) {
            synchronized (LOCK) { beginLiveTransitionLocked(s, target); }
        }
    }

    // ---------------------------------------------------------------- ticker

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            boolean again;
            synchronized (LOCK) {
                tickerScheduled = false;
                Session s = session;
                if (s == null || !s.bound || s.manager == null || s.failed) {
                    again = false;
                } else {
                    tickLocked(s);
                    again = session == s && s.bound && !s.failed;
                }
            }
            if (again) {
                synchronized (LOCK) { tickerScheduled = true; }
                main.postDelayed(this, TICK_MS);
            }
        }
    };

    private static void tickLocked(Session s) {
        long now = LiveTv.now();
        s.lastTickAtMs = now;
        Object activePlayer = playerForLocked(s);
        if (activePlayer == null) {
            s.nullPlayerTicks++;
            if (s.nullPlayerTicks > 12) {
                s.failed = true;
                s.error = "Player unavailable";
                publishLocked("Playback didn't start. Try again.", false);
            }
            return;
        }
        s.nullPlayerTicks = 0;
        Playback playback = readPlayback(s.manager);
        if (playback == null) return;
        s.lastMediaTimeMs = Math.max(0, playback.time);
        s.lastDurationMs = Math.max(0, playback.duration);
        if (playback.error) {
            s.failed = true;
            s.error = "Playback error";
            publishLocked("Playback stopped. Press Back and tune again.", false);
            return;
        }

        if (s.live && !s.durationChecked && s.streamKey != null && s.slot != null
                && playback.loaded && playback.duration > 0) {
            s.durationChecked = true;
            if (!LiveTvStreamRules.durationCompatible(playback.duration, s.slot.durationMs)
                    && s.rejectedStreams.size() < 2) {
                // The source returned a different cut (for example an unlabelled
                // extended edit). It cannot follow the shared timeline, so try the
                // next stable release for the same episode.
                if (!s.slot.program.videoId.equals(s.rejectedProgramId)) {
                    s.rejectedProgramId = s.slot.program.videoId;
                    s.rejectedStreams.clear();
                }
                s.rejectedStreams.add(s.streamKey);
                beginLiveTransitionLocked(s, s.slot);
                publishLocked("Finding a compatible source", true);
                return;
            }
        }

        if (!s.live) {
            if (s.loading) return;
            if (playback.duration > 0) {
                s.delayedPositionMs = Math.max(0, playback.time);
                s.delayedPositionValid = true;
                persistPlayerPositionLocked(s);
            }
            if (s.retrySlot != null && now >= s.retryAtMs) {
                beginDelayedTransitionLocked(s, s.retrySlot);
                return;
            }
            // A delayed episode ends at its actual file end (onEnded) or at the
            // scheduled program duration when the file runs longer. Never use
            // the wall clock here: delayed playback is intentionally not live.
            long programEnd = s.slot.durationMs;
            if (!playback.paused && programEnd > 0
                    && playback.time >= programEnd - 500L) {
                if (s.endObservedAtMs == 0L) {
                    s.endObservedAtMs = now;
                } else if (now - s.endObservedAtMs > 1500L) {
                    s.endObservedAtMs = 0L;
                    LiveTvSchedule.Slot next;
                    try { next = s.slot.next(); }
                    catch (Throwable invalid) { return; }
                    s.retrySlot = next;
                    s.retryAtMs = 0L;
                    beginDelayedTransitionLocked(s, next);
                    return;
                }
            } else {
                s.endObservedAtMs = 0L;
            }
            publishLocked(playback.paused ? "Paused" : "Delayed", false);
            return;
        }
        s.endObservedAtMs = 0L;

        if (s.loading) return;

        LiveTvSchedule.Slot current = safeAt(s.channel, now);
        if (current == null) return;

        if (now >= s.slot.endsAtMs
                && !current.program.videoId.equals(s.slot.program.videoId)) {
            if (now < s.retryAtMs) return;
            beginLiveTransitionLocked(s, current);
            return;
        }
        s.slot = current;

        if (s.waiting) {
            publishLocked("Waiting for the next program", false);
            return;
        }

        if (playback.loaded && !playback.buffering && !playback.paused) {
            long sinceBound = now - s.boundAtMs;
            long sinceUser = s.lastUserActionAtMs == 0L ? Long.MAX_VALUE : now - s.lastUserActionAtMs;
            long sinceSet = now - s.lastSetTimeAtMs;
            if (sinceBound > 900L && sinceUser > USER_GRACE_MS && sinceSet > SEEK_COOLDOWN_MS) {
                long drift = playback.time - current.offsetMs;
                long threshold = s.aligned ? DRIFT_LIVE_MS : DRIFT_FIRST_MS;
                if (Math.abs(drift) > threshold) {
                    Object player = playerForLocked(s);
                    if (player != null) {
                        setTime(player, Math.max(0, current.offsetMs));
                        s.lastSetTimeAtMs = now;
                    }
                } else {
                    s.aligned = true;
                }
            }
        }
        publishLocked("Live", false);
    }

    // ---------------------------------------------------------------- lifecycle

    private static void markDelayedLocked(Session s, long now, Playback playback) {
        // Keep the program currently on screen. The ticker may not have crossed
        // a broadcast boundary yet, and a user pause must never skip an episode.
        if (s.loading && pending == null) {
            s.transitionId++;
            s.transitionStartedAtMs = 0L;
            s.retrySlot = null;
            cancelResolverLocked();
        }
        s.live = false;
        s.waiting = false;
        s.loading = false;
        s.delayedPositionValid = playback != null;
        if (playback != null) s.delayedPositionMs = Math.max(0, playback.time);
        persistPlayerPositionLocked(s);
        publishLocked(playback != null && playback.paused ? "Paused" : "Delayed", false);
    }

    private static void persistPlayerPositionLocked(Session s) {
        Object value = call(playerFragment.get(), "getArguments");
        if (!(value instanceof Bundle) || s.slot == null) return;
        Bundle args = (Bundle) value;
        args.putString(KEY_MODE, s.live ? "live" : "delayed");
        args.putLong(KEY_OFFSET, s.delayedPositionMs);
        args.putLong(KEY_SLOT_START, s.slot.startsAtMs);
    }

    private static Session ensureSessionLocked(Launch launch) {
        Session s = session;
        if (s == null || s.generation != launch.generation) {
            s = new Session(launch.generation, launch.channel, launch.expectedUrl);
            session = s;
        }
        return s;
    }

    private static void teardownLocked() {
        generation++;
        cancelResolverLocked();
        session = null;
        pending = null;
        tuneOwner.clear();
        stopTickerLocked();
        lastStatus = "";
        lastLoading = false;
        // Deliver the final idle status to the guide that started this tune,
        // then release it. The captured callback is not retained afterwards.
        final Listener callback = listener;
        listener = null;
        publishTo(callback, "", false);
    }

    private static void cancelResolverLocked() {
        LiveTvStreams current = resolver;
        resolver = null;
        resolving = false;
        if (current != null) {
            try { current.cancel(); } catch (Throwable ignored) {}
        }
    }

    private static void scheduleTickerLocked() {
        if (tickerScheduled) return;
        tickerScheduled = true;
        main.removeCallbacks(TICK);
        main.postDelayed(TICK, TICK_MS);
    }

    private static void stopTickerLocked() {
        tickerScheduled = false;
        main.removeCallbacks(TICK);
    }

    private static void scheduleWatchdogLocked() {
        if (watchdogScheduled) return;
        watchdogScheduled = true;
        main.postDelayed(WATCHDOG, 5000L);
    }

    private static final Runnable WATCHDOG = new Runnable() {
        @Override public void run() {
            boolean again;
            synchronized (LOCK) {
                watchdogScheduled = false;
                long now = LiveTv.now();
                if (pending != null && now - pending.createdAtMs > NAV_TIMEOUT_MS) {
                    pending = null;
                    if (session != null && !session.bound) session = null;
                    publishLocked("Live TV could not start. Try again.", false);
                }
                Session s = session;
                if (s != null && s.transitionStartedAtMs > 0L
                        && now - s.transitionStartedAtMs > TRANSITION_TIMEOUT_MS) {
                    s.transitionStartedAtMs = 0L;
                    s.loading = false;
                    s.retryAtMs = now + RETRY_MS;
                    publishLocked("This channel is unavailable right now.", false);
                }
                again = pending != null || (s != null && s.bound);
                if (again) watchdogScheduled = true;
            }
            if (again) main.postDelayed(this, 5000L);
        }
    };

    // ---------------------------------------------------------------- status

    private static void publishLocked(String message, boolean loading) {
        if (session != null) {
            session.status = message;
            session.loading = loading;
        }
        if (message.equals(lastStatus) && loading == lastLoading) return;
        lastStatus = message;
        lastLoading = loading;
        // The guide renders any idle non-empty status as an error/retry state.
        // Its successful state is the empty string; the player's own Go Live
        // control already shows LIVE/Waiting/Delayed inside the player.
        publish(isInformational(message) ? "" : message, loading);
    }

    private static boolean isInformational(String message) {
        return message == null || message.length() == 0
                || "Live".equals(message)
                || "Delayed".equals(message)
                || "Paused".equals(message)
                || "Waiting for the next program".equals(message);
    }

    private static void publish(String message, boolean loading) {
        final Listener callback;
        synchronized (LOCK) { callback = listener; }
        publishTo(callback, message, loading);
    }

    private static void publishTo(final Listener callback, final String message,
            final boolean loading) {
        main.post(new Runnable() {
            @Override public void run() {
                bumpRevision();
                if (callback != null) {
                    try { callback.onStatus(message, loading); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static void clearStatusLocked() {
        lastStatus = "";
        lastLoading = false;
        main.post(new Runnable() {
            @Override public void run() { bumpRevision(); }
        });
    }

    // Compose state lives in LiveTvControls so this class stays testable without
    // the Compose runtime. The revision token is the shared invalidation source.
    private static int revisionValue() { return LiveTvControls.revisionValue(); }

    private static void bumpRevision() { LiveTvControls.bumpRevision(); }

    // ---------------------------------------------------------------- reflection

    private static Object findNavController(Object fragment) {
        if (fragment == null) return null;
        try {
            Class<?> fragmentType = Class.forName("androidx.fragment.app.Fragment");
            return Class.forName("androidx.navigation.fragment.NavHostFragment")
                    .getMethod("findNavController", fragmentType).invoke(null, fragment);
        } catch (Throwable error) { return null; }
    }

    private static Context navContext(Object nav) {
        Object value = call(nav, "getContext");
        return value instanceof Context ? (Context) value : null;
    }

    private static Context fragmentContext(Object fragment) {
        Object value = call(fragment, "getContext");
        return value instanceof Context ? (Context) value : null;
    }

    private static boolean isAdded(Object fragment) {
        Object value = call(fragment, "isAdded");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static Object call(Object target, String method) {
        if (target == null) return null;
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (Throwable error) { return null; }
    }

    private static String streamUrl(Object stream) {
        Object url = call(stream, "getUrl");
        if (url == null) return null;
        Object value = call(url, "getUrl");
        return value instanceof String ? (String) value : null;
    }

    private static String streamName(Object stream) {
        Object value = call(stream, "getName");
        return value instanceof String ? (String) value : null;
    }

    private static boolean matches(String url, String name, String expectedUrl, String expectedName) {
        if (url != null && expectedUrl != null && url.equals(expectedUrl)) return true;
        return name != null && expectedName != null && name.equals(expectedName);
    }

    private static String rawUrl(JSONObject stream) {
        if (stream == null) return null;
        String url = stream.optString("url", "");
        if (!LiveTvStreamRules.playableUrl(url)) return null;
        return url;
    }

    private static Object playerForLocked(Session s) {
        if (s == null || s.manager == null) return null;
        return call(s.manager, "getPlayer");
    }

    private static Playback readPlayback(Object manager) {
        if (manager == null) return null;
        try {
            Object flow = manager.getClass().getMethod("getState").invoke(manager);
            Object value = flow.getClass().getMethod("getValue").invoke(flow);
            if (value == null) return null;
            Playback playback = new Playback();
            playback.loaded = booleanValue(call(value, "getLoaded"));
            playback.buffering = booleanValue(call(value, "getBuffering"));
            playback.paused = booleanValue(call(value, "getPaused"));
            playback.time = longValue(call(value, "getTime"));
            playback.duration = longValue(call(value, "getDuration"));
            playback.error = call(value, "getError") != null;
            return playback;
        } catch (Throwable error) { return null; }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static void setTime(Object player, long time) {
        try { player.getClass().getMethod("setTime", long.class).invoke(player, time); }
        catch (Throwable ignored) {}
    }

    private static void play(Object player) {
        try { player.getClass().getMethod("play").invoke(player); }
        catch (Throwable ignored) {}
    }

    private static LiveTvSchedule.Slot safeAt(LiveTvSchedule.Channel channel, long now) {
        try { return channel.at(now); }
        catch (Throwable error) { return null; }
    }

    private static LiveTvSchedule.Slot canonicalSlot(LiveTvSchedule.Channel channel, String videoId) {
        try {
            LiveTvSchedule.Slot slot = channel.at(channel.epochMs);
            int count = channel.programs().size() + 1;
            for (int i = 0; i < count; i++) {
                if (slot.program.videoId.equals(videoId)) return slot;
                slot = slot.next();
            }
        } catch (Throwable ignored) {}
        return safeAt(channel, LiveTv.now());
    }

    // ---------------------------------------------------------------- stream data

    private static JSONObject buildStream(JSONObject raw, LiveTvSchedule.Channel channel,
            LiveTvSchedule.Slot slot) {
        try {
            String url = rawUrl(raw);
            if (url == null) return null;
            JSONObject stream = new JSONObject();
            stream.put("url", url);
            // Preserve the add-on's request headers, filename and subtitles.
            // The library stays isolated because navigation has no meta IDs.
            if (raw.optJSONObject("behaviorHints") != null)
                stream.put("behaviorHints", raw.getJSONObject("behaviorHints"));
            if (raw.optJSONArray("subtitles") != null)
                stream.put("subtitles", raw.getJSONArray("subtitles"));
            stream.put("name", displayName(channel, slot));
            StringBuilder description = new StringBuilder("Live TV");
            if (slot.program.season > 0) {
                description.append(" · S").append(slot.program.season);
                if (slot.program.episode > 0) description.append(" E").append(slot.program.episode);
            }
            if (!slot.program.description.isEmpty()) {
                description.append(" · ").append(slot.program.description);
            }
            stream.put("description", trim(description.toString(), 300));
            return stream;
        } catch (Throwable error) { return null; }
    }

    private static String displayName(LiveTvSchedule.Channel channel, LiveTvSchedule.Slot slot) {
        String series = slot.program.seriesTitle.isEmpty() ? "" : slot.program.seriesTitle + " · ";
        return trim(channel.name + " | " + series + slot.program.title, 120);
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String encodeStream(JSONObject stream) {
        try {
            byte[] raw = stream.toString().getBytes("UTF-8");
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length / 2 + 64);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            deflater.end();
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable error) { return null; }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message != null) {
            if (message.startsWith("Set up Real-Debrid")
                    || message.startsWith("No playable stream")
                    || message.startsWith("The source is unavailable")
                    || message.startsWith("The source returned")
                    || message.startsWith("The source response")
                    || message.startsWith("This channel has an invalid")
                    || message.startsWith("This channel is changing")
                    || message.startsWith("Channel request cancelled")) {
                return message;
            }
        }
        return "This channel is unavailable right now.";
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Throwable ignored) {}
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    // ---------------------------------------------------------------- state

    private static final class Playback {
        boolean loaded;
        boolean buffering;
        boolean paused;
        boolean error;
        long time;
        long duration;
    }

    private static final class Launch {
        final long generation;
        final LiveTvSchedule.Channel channel;
        final LiveTvSchedule.Slot slot;
        final boolean live;
        final boolean replace;
        final long startOffsetMs;
        final String expectedUrl;
        final String displayName;
        final String encoded;
        final String streamKey;
        final long createdAtMs = LiveTv.now();

        Launch(long generation, LiveTvSchedule.Channel channel, LiveTvSchedule.Slot slot,
                boolean live, boolean replace, long startOffsetMs, String expectedUrl,
                String displayName, String encoded, String streamKey) {
            this.generation = generation;
            this.channel = channel;
            this.slot = slot;
            this.live = live;
            this.replace = replace;
            this.startOffsetMs = startOffsetMs;
            this.expectedUrl = expectedUrl;
            this.displayName = displayName;
            this.encoded = encoded;
            this.streamKey = streamKey;
        }
    }

    private static final class Session {
        final long generation;
        final LiveTvSchedule.Channel channel;
        LiveTvSchedule.Slot slot;
        String expectedUrl;
        String displayName;
        Object manager;
        boolean bound;
        boolean live;
        boolean waiting;
        boolean loading;
        boolean failed;
        boolean restored;
        boolean aligned;
        int nullPlayerTicks;
        boolean delayedPositionValid;
        long delayedPositionMs;
        long boundAtMs;
        long lastUserActionAtMs;
        long lastSetTimeAtMs;
        long lastTickAtMs;
        long lastMediaTimeMs;
        long lastDurationMs;
        long transitionId;
        long transitionStartedAtMs;
        long retryAtMs;
        long endObservedAtMs;
        LiveTvSchedule.Slot retrySlot;
        String status = "";
        String error;
        String streamKey;
        String rejectedProgramId;
        boolean durationChecked;
        final Set<String> rejectedStreams = new HashSet<String>();

        Session(long generation, LiveTvSchedule.Channel channel, String expectedUrl) {
            this.generation = generation;
            this.channel = channel;
            this.expectedUrl = expectedUrl;
        }
    }
}
