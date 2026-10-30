package com.jedflix.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the managed Comet endpoint can be configured; credentials stay on device. */
public final class CometConfig {
    public static final String BASE = "https://comet.elfhosted.com";
    private static final String PREFIX = "jedflix-encrypted-v1:";
    private static final String ALIAS = "jedflix-comet-v1";
    private static AtomicFile file;
    private static JSONObject current = new JSONObject();

    private CometConfig() {}

    public static synchronized void init(Context context) {
        if (file != null) return;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "comet-settings"));
        if (file.getBaseFile().exists()) {
            try {
                current = new JSONObject(unseal(new String(file.readFully(), StandardCharsets.UTF_8)));
            } catch (Exception error) {
                // Never discard credentials or silently fall back on a corrupt file.
                throw new IllegalStateException("Cannot read saved Comet settings", error);
            }
        }
    }

    private static SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    public static synchronized String seal(String raw) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
            return PREFIX + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot protect Comet settings", error);
        }
    }

    public static synchronized String unseal(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) return raw;
        try {
            String[] parts = raw.substring(PREFIX.length()).split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read protected profile", error);
        }
    }

    public static synchronized JSONObject settings() {
        try { return new JSONObject(current.toString()); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static synchronized void save(String apiKey, int maxResults, double maxSizeGb,
            String quality, boolean cachedOnly, boolean removeTrash, boolean deduplicate) {
        saveWithAccount(apiKey, maxResults, maxSizeGb, quality, cachedOnly, removeTrash, deduplicate,
            apiKey.trim().equals(current.optString("apiKey")) ? current.optJSONObject("account") : null);
    }

    public static synchronized void saveVerified(String apiKey, int maxResults, double maxSizeGb,
            String quality, boolean cachedOnly, boolean removeTrash, boolean deduplicate, JSONObject account) {
        if (account == null || account.optString("username").trim().isEmpty() || account.optLong("validatedAt", 0) <= 0)
            throw new IllegalArgumentException("Verify your Real-Debrid account before saving.");
        saveWithAccount(apiKey, maxResults, maxSizeGb, quality, cachedOnly, removeTrash, deduplicate, account);
    }

    private static void saveWithAccount(String apiKey, int maxResults, double maxSizeGb,
            String quality, boolean cachedOnly, boolean removeTrash, boolean deduplicate, JSONObject account) {
        apiKey = apiKey.trim();
        if (apiKey.isEmpty() || apiKey.length() > 256 || !apiKey.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Enter your Real-Debrid API key.");
        if (maxResults < 0 || maxResults > 100) throw new IllegalArgumentException("Results must be between 0 and 100.");
        if (Double.isNaN(maxSizeGb) || Double.isInfinite(maxSizeGb) || maxSizeGb < 0 || maxSizeGb > 1000)
            throw new IllegalArgumentException("Maximum size must be between 0 and 1000 GB.");
        if (!quality.equals("all") && !quality.equals("1080p") && !quality.equals("720p"))
            throw new IllegalArgumentException("Choose a supported quality limit.");
        try {
            JSONObject next = new JSONObject();
            next.put("apiKey", apiKey);
            next.put("maxResults", maxResults);
            next.put("maxSizeGb", maxSizeGb);
            next.put("quality", quality);
            next.put("cachedOnly", cachedOnly);
            next.put("removeTrash", removeTrash);
            next.put("deduplicate", deduplicate);
            if (account != null) next.put("account", new JSONObject()
                .put("username", account.getString("username")).put("validatedAt", account.getLong("validatedAt")));
            write(next);
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Cannot save Comet settings", error); }
    }

    public static synchronized void clear() { write(new JSONObject()); }

    private static void write(JSONObject next) {
        if (file == null) throw new IllegalStateException("Comet settings are not initialized");
        FileOutputStream stream = null;
        try {
            byte[] encrypted = seal(next.toString()).getBytes(StandardCharsets.UTF_8);
            stream = file.startWrite();
            stream.write(encrypted);
            file.finishWrite(stream);
            current = next;
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw new IllegalStateException("Cannot save Comet settings", error);
        }
    }

    public static synchronized String manifestUrl() {
        if (current.optString("apiKey").isEmpty()) return BASE + "/manifest.json";
        try {
            JSONObject config = new JSONObject();
            config.put("debridServices", new JSONArray().put(new JSONObject()
                .put("service", "realdebrid").put("apiKey", current.getString("apiKey"))));
            config.put("enableTorrent", false);
            config.put("maxResultsPerResolution", current.optInt("maxResults", 0));
            config.put("maxSize", Math.round(current.optDouble("maxSizeGb", 0) * 1073741824d));
            config.put("cachedOnly", current.optBoolean("cachedOnly", true));
            config.put("removeTrash", current.optBoolean("removeTrash", true));
            config.put("deduplicateStreams", current.optBoolean("deduplicate", true));
            config.put("resultFormat", new JSONArray().put("all"));
            JSONObject resolutions = new JSONObject();
            String quality = current.optString("quality", "all");
            if (!quality.equals("all")) {
                resolutions.put("r2160p", false);
                resolutions.put("r1440p", false);
                resolutions.put("unknown", false);
            }
            if (quality.equals("720p")) resolutions.put("r1080p", false);
            config.put("resolutions", resolutions);
            return BASE + "/" + Base64.encodeToString(config.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP) + "/manifest.json";
        } catch (Exception error) { throw new IllegalStateException("Cannot configure Comet", error); }
    }

    public static String protectProfile(String key, String value) {
        // Comet's manifest URL embeds credentials. Protect the native profile too.
        return "profile".equals(key) && value != null ? seal(value) : value;
    }
}
