package com.jedflix.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.URI;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Resolves one scheduled video through the already-configured Comet add-on. */
public final class LiveTvStreams {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final OkHttpClient mediaHttp = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private Call active;
    private boolean cancelled;

    public JSONObject resolve(String manifest, String type, String videoId) throws IOException {
        return resolve(manifest, type, videoId, null);
    }

    public JSONObject resolve(String manifest, String type, String videoId,
            java.util.Set<String> excluded) throws IOException {
        if (!LiveTvStreamRules.validVideoId(type, videoId))
            throw new IOException("This channel has an invalid program.");
        if (manifest == null || manifest.equals(CometConfig.BASE + "/manifest.json")
                || !manifest.startsWith(CometConfig.BASE + "/") || !manifest.endsWith("/manifest.json"))
            throw new IOException("Set up Real-Debrid in Settings to watch this channel.");
        String address;
        try {
            address = manifest.substring(0, manifest.length() - "manifest.json".length())
                + "stream/" + type + "/" + URLEncoder.encode(videoId, "UTF-8") + ".json";
        } catch (Exception invalid) { throw new IOException("Could not load this channel."); }
        Call call = http.newCall(new Request.Builder().url(address)
            .header("Accept", "application/json").header("Cache-Control", "no-store").build());
        synchronized (this) {
            if (cancelled) throw new IOException("Channel request cancelled.");
            active = call;
        }
        Response fetched;
        try { fetched = call.execute(); }
        catch (IOException unavailable) {
            synchronized (this) { if (active == call) active = null; }
            // Comet's path contains the account configuration. Do not surface
            // transport exceptions that may include the full request address.
            throw new IOException("The source did not respond. Try this channel again.");
        }
        try (Response response = fetched) {
            if (response.code() != 200 || response.body() == null)
                throw new IOException("The source is unavailable. Try this channel again.");
            try (InputStream in = response.body().byteStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (body.size() + count > MAX_BYTES) throw new IOException("The source response was too large.");
                    body.write(buffer, 0, count);
                }
                JSONObject root = new JSONObject(new String(body.toByteArray(), "UTF-8"));
                java.util.List<JSONObject> ordered =
                        LiveTvStreamRules.candidates(root.optJSONArray("streams"), excluded);
                if (ordered.isEmpty() && excluded != null && !excluded.isEmpty()) {
                    // Nothing else is available: keep the channel playing rather
                    // than failing because every alternative was rejected.
                    ordered = LiveTvStreamRules.candidates(root.optJSONArray("streams"), null);
                }
                if (ordered.isEmpty())
                    throw new IOException("No playable source for this program. Check Real-Debrid in Settings or try again.");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                for (int i = 0; i < Math.min(ordered.size(), 8); i++) {
                    synchronized (this) {
                        if (cancelled) throw new IOException("Channel request cancelled.");
                    }
                    if (System.nanoTime() >= deadline) break;
                    JSONObject stream = ordered.get(i);
                    String playable = mediaAddress(stream, deadline);
                    if (playable != null) {
                        // Reuse the inspected redirect target. Otherwise a second
                        // Comet lookup could return a different response at launch.
                        JSONObject resolved = new JSONObject(stream.toString()).put("url", playable);
                        JSONObject resultHints = resolved.optJSONObject("behaviorHints");
                        JSONObject resultProxy = resultHints == null ? null : resultHints.optJSONObject("proxyHeaders");
                        if (resultProxy != null && !sameOrigin(stream.optString("url", ""), playable))
                            resultProxy.put("request", new JSONObject());
                        return resolved;
                    }
                }
                throw new IOException("No playable source for this program. Try this channel again.");
            } catch (org.json.JSONException invalid) { throw new IOException("The source returned an invalid response."); }
        } finally { synchronized (this) { if (active == call) active = null; } }
    }

    private static boolean sameOrigin(String first, String second) {
        try {
            URI a = new URI(first), b = new URI(second);
            return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
        } catch (Exception invalid) { return false; }
    }

    /** Inspect headers only; a one-byte range avoids downloading an episode. */
    private String mediaAddress(JSONObject stream, long deadline) {
        String address = stream.optString("url", "");
        JSONObject hints = stream.optJSONObject("behaviorHints");
        JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
        JSONObject headers = proxy == null ? null : proxy.optJSONObject("request");
        for (int hop = 0; hop < 5 && System.nanoTime() < deadline; hop++) {
            if (!LiveTvStreamRules.playableUrl(address)) return null;
            Call call = null;
            try {
                Request.Builder request = new Request.Builder().url(address);
                if (headers != null) {
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        request.header(key, headers.optString(key, ""));
                    }
                }
                request.header("Range", "bytes=0-0");
                call = mediaHttp.newCall(request.build());
                synchronized (this) {
                    if (cancelled) return null;
                    active = call;
                }
                try (Response response = call.execute()) {
                    int code = response.code();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = response.header("Location");
                        if (location == null) return null;
                        URI previous = new URI(address);
                        String next = previous.resolve(location).toString();
                        // Proxy credentials belong to the initial origin. Never
                        // carry arbitrary headers across an origin redirect.
                        if (!sameOrigin(address, next)) headers = null;
                        address = next;
                        continue;
                    }
                    if (code != 200 && code != 206) return null;
                    String contentType = response.header("Content-Type");
                    if (contentType != null) {
                        String type = contentType.toLowerCase(java.util.Locale.US);
                        if (type.startsWith("text/html") || type.startsWith("application/json")) return null;
                    }
                    return address;
                }
            } catch (Exception unavailable) {
                // Transport exceptions can contain personal playback URLs.
                return null;
            } finally {
                synchronized (this) { if (active == call) active = null; }
            }
        }
        return null;
    }

    public void cancel() {
        Call call;
        synchronized (this) { cancelled = true; call = active; }
        if (call != null) call.cancel();
    }
}
