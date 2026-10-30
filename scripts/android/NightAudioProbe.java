package com.jedflix.test;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Separate verification APK. Exercises the real bundled libmpv and NightAudio. */
public final class NightAudioProbe extends Instrumentation {
    private Class<?> night, mpvClass;
    private Object mpv;
    private int checks;
    private boolean originalEnabled;
    private boolean expectSpdif;
    private final List<String> errors = Collections.synchronizedList(new ArrayList<String>());

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        expectSpdif = Boolean.parseBoolean(args.getString("expectSpdif", "true"));
        start();
    }

    private Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return mpvClass.getMethod(name, types).invoke(target, args);
    }
    private Object night(String name, Class<?>[] types, Object... args) throws Exception {
        return night.getMethod(name, types).invoke(null, args);
    }
    private void mainNight(String name, Class<?>[] types, Object... args) throws Exception {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { night(name, types, args); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError(name, failure[0]);
    }
    private void command(String... args) throws Exception {
        call(mpv, "command", new Class<?>[]{String[].class}, (Object) args);
    }
    private String property(String name) throws Exception {
        return (String) call(mpv, "getPropertyString", new Class<?>[]{String.class}, name);
    }
    private double position() throws Exception {
        Double value = (Double) call(mpv, "getPropertyDouble", new Class<?>[]{String.class}, "time-pos");
        return value == null ? -1 : value;
    }
    private void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
        android.util.Log.i("JedflixAudioTest", "PASS " + message);
    }
    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition condition, String message) throws Exception {
        long end = System.currentTimeMillis() + 7000;
        while (System.currentTimeMillis() < end) {
            if (condition.ready()) { check(true, message); return; }
            Thread.sleep(50);
        }
        throw new AssertionError(message + "; af=" + property("af") + "; status="
            + night("getStatus", new Class<?>[0]) + "; output=" + property("audio-out-params")
            + "; codec=" + property("audio-codec-name") + "; spdif=" + property("audio-spdif")
            + "; speed=" + property("speed") + "; pause=" + property("pause")
            + "; aid=" + property("aid") + "; time=" + position()
            + "; eof=" + property("eof-reached") + "; errors=" + errors);
    }
    private File fixture(String name) throws Exception {
        File file = new File(getTargetContext().getCacheDir(), name);
        try (InputStream in = getContext().getAssets().open(name);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        return file;
    }
    private void option(String name, String value) throws Exception {
        int result = (Integer) call(mpv, "setOptionString", new Class<?>[]{String.class, String.class}, name, value);
        check(result >= 0, "mpv option " + name + " accepted");
    }
    private void enabled(boolean value) throws Exception {
        mainNight("setEnabled", new Class<?>[]{boolean.class}, value);
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            night = loader.loadClass("com.jedflix.tv.NightAudio");
            mpvClass = loader.loadClass("dev.jdtech.mpv.MPVLib");
            mainNight("init", new Class<?>[]{Context.class}, getTargetContext());
            originalEnabled = (Boolean) night("isEnabled", new Class<?>[0]);
            enabled(false);
            mpv = mpvClass.getMethod("create", Context.class).invoke(null, getTargetContext());
            check(mpv != null, "bundled mpv and replacement avfilter load together");
            Class<?> logType = loader.loadClass("dev.jdtech.mpv.MPVLib$LogObserver");
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{logType}, (proxy, method, args) -> {
                if (method.getName().equals("logMessage")) {
                    String prefix = (String) args[0], text = (String) args[2];
                    int level = (Integer) args[1];
                    if (level <= 20) errors.add(prefix + ": " + text);
                    android.util.Log.d("JedflixAudioMPV", prefix + ": " + text);
                }
                return null;
            });
            call(mpv, "addLogObserver", new Class<?>[]{logType}, observer);
            option("vo", "null"); option("vid", "no"); option("ao", "null");
            option("keep-open", "yes"); option("audio-spdif", "ac3,dts");
            option("hr-seek", "no");
            call(mpv, "init", new Class<?>[0]);
            mainNight("attach", new Class<?>[]{Object.class}, mpv);
            File stereo = fixture("night-stereo.wav"), surround = fixture("night-surround.wav");
            command("af", "add", "@jedflix_probe:lavfi=[anull]");
            command("loadfile", stereo.getAbsolutePath());
            await(() -> position() > 0.3, "unprocessed stereo playback advances");
            enabled(true);
            await(() -> (Boolean) night("isApplied", new Class<?>[0]), "Night mode reports verified PCM filter active");
            check(property("af").contains("jedflix_probe"), "enabling preserves another audio filter");
            String label = (String) night.getField("LABEL").get(null);
            check(property("af").contains(label), "mpv exposes Night mode filter label");
            check("".equals(property("audio-spdif")), "Night mode disables compressed passthrough");
            check("yes".equals(property("hr-seek")), "Night mode enables precise seeking before scene resume");
            String format = property("audio-out-params/format");
            check(format != null && !format.contains("spdif"), "filtered output is decoded PCM: " + format);
            double before = position();
            Thread.sleep(400);
            check(position() > before, "filtered playback continues");
            command("seek", "5", "absolute+exact");
            await(() -> position() >= 5, "seek works with Night mode");
            call(mpv, "setPropertyDouble", new Class<?>[]{String.class, double.class}, "speed", 1.25);
            before = position(); Thread.sleep(500);
            check(position() > before + 0.3, "speed change keeps filtered audio advancing");
            call(mpv, "setPropertyBoolean", new Class<?>[]{String.class, boolean.class}, "pause", true);
            Thread.sleep(150); before = position(); Thread.sleep(250);
            check(Math.abs(position() - before) < 0.08, "pause holds playback position");
            enabled(false);
            enabled(true);
            Thread.sleep(2500);
            check((Boolean) night("isEnabled", new Class<?>[0]), "enabling while paused does not falsely disable Night mode");
            call(mpv, "setPropertyBoolean", new Class<?>[]{String.class, boolean.class}, "pause", false);
            await(() -> (Boolean) night("isApplied", new Class<?>[0]), "paused toggle becomes active after resume");
            command("loadfile", surround.getAbsolutePath());
            await(() -> position() > 0.3 && "6".equals(property("audio-params/channel-count")), "5.1 track loads with Night mode");
            await(() -> (Boolean) night("isApplied", new Class<?>[0]), "filter remains active after track replacement");
            enabled(false);
            await(() -> !property("af").contains(label), "Off removes Night mode filter live");
            check(property("af").contains("jedflix_probe"), "Off preserves another audio filter");
            check("ac3,dts".equals(property("audio-spdif")), "Off restores previous passthrough preference");
            check("no".equals(property("hr-seek")), "Off restores original seek behavior");
            check(!(Boolean) night("isApplied", new Class<?>[0]), "Off is not reported as applied");
            enabled(true);
            await(() -> (Boolean) night("isApplied", new Class<?>[0]), "Night mode can be enabled again without restart");
            check(errors.isEmpty(), "mpv reported no native errors: " + errors);
            enabled(false);
            command("af", "remove", "@jedflix_probe");
            // Encoded passthrough cannot preserve the earlier 1.25x PCM speed.
            call(mpv, "setPropertyDouble", new Class<?>[]{String.class, double.class}, "speed", 1.0);
            String ac3Passthrough = expectSpdif ? "ac3,dts" : "";
            // A build with every muxer disabled cannot open a SPDIF decoder.
            // Its supported AC3 path is decoded PCM, as used by Night mode.
            call(mpv, "setPropertyString", new Class<?>[]{String.class, String.class}, "audio-spdif", ac3Passthrough);
            File ac3 = fixture("night-passthrough.ac3");
            command("loadfile", ac3.getAbsolutePath());
            await(() -> position() > 0.3 && "ac3".equals(property("audio-codec-name")), "AC3 audio playback advances");
            if (expectSpdif) {
                await(() -> property("audio-out-params/format") != null
                    && property("audio-out-params/format").contains("spdif"), "AC3 opens the actual passthrough decoder");
            } else {
                check(!property("audio-out-params/format").contains("spdif"),
                    "upstream build without the SPDIF muxer decodes AC3 to PCM");
                report.putString("passthrough", "Option restoration verified; actual SPDIF unavailable in upstream FFmpeg (--disable-muxers)");
            }
            enabled(true);
            await(() -> (Boolean) night("isApplied", new Class<?>[0])
                && !property("audio-out-params/format").contains("spdif"), "On processes AC3 as decoded PCM");
            enabled(false);
            if (expectSpdif) {
                await(() -> property("audio-out-params/format") != null
                    && property("audio-out-params/format").contains("spdif"), "Off restores the actual AC3 passthrough decoder");
            }
            check(ac3Passthrough.equals(property("audio-spdif")), "Off restores AC3 passthrough option after AC3 playback");
            check(errors.isEmpty(), "passthrough transitions reported no native errors: " + errors);
            enabled(true);
            await(() -> (Boolean) night("isApplied", new Class<?>[0]), "Night mode resumes after passthrough restoration");
            // The JNI command returns void. Deliberately break a labelled graph
            // to prove configured presence cannot falsely report healthy audio.
            command("af", "add", "@" + label + ":lavfi=[aresample=resampler=soxr]");
            await(() -> !(Boolean) night("isEnabled", new Class<?>[0])
                && "Unavailable".equals(night("getStatus", new Class<?>[0])), "failed native graph disables Night mode instead of reporting On");
            check(!property("af").contains(label), "native failure removes the failed graph");
            check(ac3Passthrough.equals(property("audio-spdif")), "native failure restores original passthrough setting");
            check("no".equals(property("hr-seek")), "native failure restores original seek behavior");
            enabled(true);
            await(() -> (Boolean) night("isApplied", new Class<?>[0]), "Night mode recovers after a native graph failure");
            report.putString("graph", (String) night.getField("GRAPH").get(null));
            report.putString("result", "PASS: " + checks + " audio integration checks");
        } catch (Throwable failure) {
            android.util.Log.e("JedflixAudioTest", "FAIL", failure);
            report.putString("result", "FAIL: " + failure);
        } finally {
            try {
                if (mpv != null) {
                    mainNight("detach", new Class<?>[]{Object.class}, mpv);
                    call(mpv, "destroy", new Class<?>[0]);
                }
                if (night != null) enabled(originalEnabled);
            } catch (Throwable ignored) { android.util.Log.e("JedflixAudioTest", "Cleanup failed", ignored); }
        }
        finish(report.getString("result", "FAIL").startsWith("PASS:") ? -1 : 0, report);
    }
}
