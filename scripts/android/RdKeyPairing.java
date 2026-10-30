package com.jedflix.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.json.JSONObject;

/** One-time key transfer using the existing Jedflix phone page. Never logs URLs or keys. */
public final class RdKeyPairing {
    public static final String PAGE_BASE = "https://borseth.ddns.net/tv/rd/";
    public static final String API_BASE = "https://borseth.ddns.net/backend/api/v1/tv/rd-key/";
    public static final long TTL_MS = 10 * 60 * 1000L;
    private final String base;
    private volatile boolean cancelled;
    private Call active;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();

    public RdKeyPairing() { this(API_BASE); }
    // Package-private seam for isolated transport tests; the UI always uses the fixed HTTPS service.
    RdKeyPairing(String base) { this.base = base; }

    public static String newCode() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append("0123456789abcdef".charAt((value & 255) >>> 4));
            result.append("0123456789abcdef".charAt(value & 15));
        }
        return result.toString();
    }

    public static String validateKey(String value) throws IOException {
        String key = value.trim();
        if (key.isEmpty() || key.length() > 256 || !key.matches("[A-Za-z0-9_-]+"))
            throw new IOException("Invalid key response");
        return key;
    }

    public void open(String code) throws IOException { request(code, true); }
    /** null means waiting (204); an empty string means expired (404). */
    public String poll(String code) throws IOException { return request(code, false); }

    public void cancel() {
        Call call;
        synchronized (this) { cancelled = true; call = active; active = null; }
        if (call != null) call.cancel();
    }

    private String request(String code, boolean open) throws IOException {
        if (!code.matches("[A-Za-z0-9_-]{16,128}")) throw new IOException("Invalid code");
        Request.Builder request = new Request.Builder().url(base + code)
            .header("User-Agent", "Jedflix-TV-Stremio").header("Accept", "application/json")
            .header("Cache-Control", "no-store");
        if (open) request.put(RequestBody.create((MediaType) null, new byte[0]));
        Call call = http.newCall(request.build());
        synchronized (this) {
            if (cancelled) { call.cancel(); throw new IOException("Cancelled"); }
            active = call;
        }
        try (Response response = call.execute()) {
            int status = response.code();
            if (open && status == 201) return null;
            if (!open && status == 204) return null;
            if (!open && status == 404) return "";
            if (!open && status == 200) {
                if (response.body() == null) throw new IOException("Invalid key response");
                try (InputStream stream = response.body().byteStream();
                     ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (body.size() + count > 4096) throw new IOException("Invalid key response");
                        body.write(buffer, 0, count);
                    }
                    Object key = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)).get("apiKey");
                    if (!(key instanceof String)) throw new IOException("Invalid key response");
                    return validateKey((String) key);
                } catch (org.json.JSONException error) { throw new IOException("Invalid key response"); }
            }
            throw new IOException("Pairing service unavailable");
        } finally {
            synchronized (this) { if (active == call) active = null; }
        }
    }
}
