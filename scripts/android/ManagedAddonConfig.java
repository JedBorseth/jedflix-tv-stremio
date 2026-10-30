package com.jedflix.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Build-owned sources, with private configuration for the managed Comet source. */
public final class ManagedAddonConfig {
    private ManagedAddonConfig() {}

    public static String normalize(String key, String raw) {
        if (!"profile".equals(key)) return raw;
        try {
            raw = CometConfig.unseal(raw);
            JSONObject profile;
            if (raw == null) {
                profile = new JSONObject();
                profile.put("auth", JSONObject.NULL);
                profile.put("settings", new JSONObject(ManagedAddonPreset.DEFAULT_SETTINGS));
            } else {
                profile = new JSONObject(raw);
            }
            // Replace the whole list, including on upgrades from an older build.
            // Preserve auth, preferences and all unrelated library storage keys.
            JSONArray addons = new JSONArray(ManagedAddonPreset.ADDONS);
            addons.getJSONObject(1).put("transportUrl", CometConfig.manifestUrl());
            profile.put("addons", addons);
            profile.put("addonsLocked", true);
            return profile.toString();
        } catch (JSONException error) {
            // Never silently restore a user-editable/default addon list.
            throw new IllegalStateException("Cannot apply Jedflix content source policy", error);
        }
    }
}
