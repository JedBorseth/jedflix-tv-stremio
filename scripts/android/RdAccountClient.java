package com.jedflix.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Validates a key directly with Real-Debrid; caches only the username and validation time. */
public final class RdAccountClient {
    private final String endpoint;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private Call active;
    private boolean cancelled;
    public RdAccountClient() { this("https://api.real-debrid.com/rest/1.0/user"); }
    RdAccountClient(String endpoint) { this.endpoint = endpoint; }

    public JSONObject validate(String key) throws IOException {
        key = RdKeyPairing.validateKey(key);
        Call call = http.newCall(new Request.Builder().url(endpoint)
            .header("Authorization", "Bearer " + key).header("Accept", "application/json")
            .header("Cache-Control", "no-store").build());
        synchronized (this) {
            if (cancelled) throw new IOException("Validation cancelled.");
            active = call;
        }
        try (Response response = call.execute()) {
            if (response.code() == 401) throw new IOException("This Real-Debrid key is invalid or expired. Check your key and try again.");
            if (response.code() == 403) throw new IOException("Real-Debrid denied access to this account. Check your account on real-debrid.com.");
            if (response.code() != 200 || response.body() == null)
                throw new IOException("Could not verify your account. Please try again later.");
            try (InputStream stream = response.body().byteStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (body.size() + count > 16384) throw new IOException("Invalid account response.");
                    body.write(buffer, 0, count);
                }
                JSONObject user = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
                Object username = user.get("username");
                if (!(username instanceof String)) throw new IOException("Invalid account response.");
                String name = ((String)username).trim();
                if (name.isEmpty() || name.length() > 256 || name.matches("(?s).*[\\p{Cntrl}].*"))
                    throw new IOException("Invalid account response.");
                return new JSONObject().put("username", name).put("validatedAt", System.currentTimeMillis());
            } catch (org.json.JSONException error) { throw new IOException("Invalid account response."); }
        } finally { synchronized (this) { if (active == call) active = null; } }
    }
    public void cancel() {
        Call call;
        synchronized (this) { cancelled = true; call = active; }
        if (call != null) call.cancel();
    }
}
