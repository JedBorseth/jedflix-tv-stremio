package com.jedflix.tv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** One broadcast contract shared by the guide and player for this app build. */
public final class LiveTv {
    private static LiveTvSchedule schedule;
    private LiveTv() {}

    public static synchronized LiveTvSchedule schedule(Context context) {
        if (schedule != null) return schedule;
        try (InputStream in = context.getAssets().open("live-tv.json");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > 2 * 1024 * 1024)
                    throw new IllegalArgumentException("Channel manifest is too large");
                bytes.write(buffer, 0, count);
            }
            schedule = LiveTvSchedule.parse(new String(bytes.toByteArray(), "UTF-8"));
            return schedule;
        } catch (Exception invalid) {
            throw new IllegalStateException("The Live TV schedule could not be loaded.", invalid);
        }
    }

    public static long now() { return System.currentTimeMillis(); }
}
