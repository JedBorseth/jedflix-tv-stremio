package com.jedflix.test;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;

/** A separate, locally signed instrumentation APK; never shipped in the TV app. */
public final class AddonLockProbe extends Instrumentation {
    private ClassLoader loader;
    private int checks;
    private String cometPhase;

    @Override public void onCreate(Bundle args) { super.onCreate(args); cometPhase = args.getString("cometPhase"); start(); }

    private Class<?> type(String name) throws Exception { return loader.loadClass(name); }
    private Object construct(String name, Object... args) throws Exception {
        for (Constructor<?> constructor : type(name).getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                if (args[i] != null && !parameters[i].isInstance(args[i]) &&
                        !(parameters[i] == boolean.class && args[i] instanceof Boolean)) matches = false;
            }
            if (matches) return constructor.newInstance(args);
        }
        throw new AssertionError("No matching constructor: " + name);
    }

    private Object decodeProto(String name, byte[] data) throws Exception {
        Object companion = type(name).getField("Companion").get(null);
        return type("pbandk.MessageKt").getMethod("decodeFromByteArray",
            type("pbandk.Message$Companion"), byte[].class).invoke(null, companion, data);
    }

    private Object action(String category, String operation, Object value) throws Exception {
        String prefix = "com.stremio.core.runtime.msg.Action";
        String model = prefix + category;
        Object args = construct(model + "$Args$" + operation, value);
        Object body = construct(model, args, Collections.emptyMap());
        Object union = construct(prefix + "$Type$" + category, body);
        return construct(prefix, union, Collections.emptyMap());
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
        android.util.Log.i("JedflixAddonTest", "PASS " + description);
    }

    private void checkAddonList(List<?> addons) throws Exception {
        StringBuilder identifiers = new StringBuilder();
        for (Object item : addons) {
            Object manifest = item.getClass().getMethod("getManifest").invoke(item);
            identifiers.append(manifest.getClass().getMethod("getId").invoke(manifest)).append(' ');
        }
        check(addons.size() == 2, "native profile has exactly two managed addons (found " + addons.size() + ": " + identifiers + ")");
        String[] expected = {"com.linvo.cinemeta", "comet.elfhosted.com"};
        for (int i = 0; i < expected.length; i++) {
            Object manifest = addons.get(i).getClass().getMethod("getManifest").invoke(addons.get(i));
            check(expected[i].equals(manifest.getClass().getMethod("getId").invoke(manifest)), "native preset " + expected[i]);
            if (i == 1) {
                String url = (String) addons.get(i).getClass().getMethod("getTransportUrl").invoke(addons.get(i));
                check(url.equals(type("com.jedflix.tv.CometConfig").getMethod("manifestUrl").invoke(null)),
                    "native Comet uses saved configuration");
            }
        }
    }

    private void checkNativePresets(Object core, Object facade) throws Exception {
        Object path = construct("com.stremio.core.types.addon.ResourcePath", "addon_catalog", "", "",
            Collections.emptyList(), Collections.emptyMap());
        Object request = construct("com.stremio.core.types.addon.ResourceRequest", "", path, Collections.emptyMap());
        facade.getClass().getMethod("loadAddons", request.getClass()).invoke(facade, request);
        Object addonField = type("com.stremio.core.Field$ADDONS").getField("INSTANCE").get(null);
        byte[] initialBytes = (byte[]) core.getClass().getMethod("getStateNative", type("com.stremio.core.Field")).invoke(core, addonField);
        Object initialModel = decodeProto("com.stremio.core.models.AddonsWithFilters", initialBytes);
        Object selectable = initialModel.getClass().getMethod("getSelectable").invoke(initialModel);
        List<?> catalogs = (List<?>) selectable.getClass().getMethod("getCatalogs").invoke(selectable);
        for (Object catalog : catalogs) {
            String name = (String) catalog.getClass().getMethod("getName").invoke(catalog);
            android.util.Log.i("JedflixAddonTest", "Source selector: " + name);
            if (name.toLowerCase().contains("installed")) {
                request = catalog.getClass().getMethod("getRequest").invoke(catalog);
                facade.getClass().getMethod("loadAddons", request.getClass()).invoke(facade, request);
            }
        }
        Object ready = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (ready == null && System.currentTimeMillis() < deadline) {
            byte[] bytes = (byte[]) core.getClass().getMethod("getStateNative", type("com.stremio.core.Field")).invoke(core, addonField);
            Object model = decodeProto("com.stremio.core.models.AddonsWithFilters", bytes);
            Object selected = model.getClass().getMethod("getSelected").invoke(model);
            if (selected == null || !request.equals(selected.getClass().getMethod("getRequest").invoke(selected))) {
                Thread.sleep(25);
                continue;
            }
            Object catalog = model.getClass().getMethod("getCatalog").invoke(model);
            if (catalog != null) ready = catalog.getClass().getMethod("getReady").invoke(catalog);
            if (ready == null) Thread.sleep(25);
        }
        check(ready != null, "native installed-source catalog loaded");
        checkAddonList((List<?>) ready.getClass().getMethod("getItems").invoke(ready));
        Object field = type("com.stremio.core.Field$CTX").getField("INSTANCE").get(null);
        byte[] state = (byte[]) core.getClass().getMethod("getStateNative", type("com.stremio.core.Field")).invoke(core, field);
        Object context = decodeProto("com.stremio.core.models.Ctx", state);
        List<?> profiles = (List<?>) context.getClass().getMethod("getUserProfiles").invoke(context);
        for (Object profile : profiles) {
            List<?> addons = (List<?>) profile.getClass().getMethod("getAddons").invoke(profile);
            checkAddonList(addons);
            check(!(Boolean) profile.getClass().getMethod("getCanManageAddons").invoke(profile), "addon permission hidden for profile");
        }
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            Class<?> actionClass = type("com.stremio.core.runtime.msg.Action");
            Class<?> policy = type("com.jedflix.tv.ManagedAddons");
            Method blocked = policy.getMethod("blocked", actionClass);
            Object core = type("com.stremio.core.Core").getField("INSTANCE").get(null);
            Method dispatch = core.getClass().getMethod("dispatch", actionClass, type("com.stremio.core.Field"));
            String addon = "com.stremio.core.types.addon.";
            Object hints = construct(addon + "ManifestBehaviorHints", false, false, false, false, Collections.emptyMap());
            Object manifest = construct(addon + "Manifest", "jedflix.test", "1.0.0", "Test addon",
                null, null, null, Collections.emptyList(), null, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), hints, Collections.emptyMap());
            Object flags = construct(addon + "DescriptorFlags", false, false, Collections.emptyMap());
            Object descriptor = construct(addon + "AddonDescriptor", manifest, "https://example.invalid/manifest.json",
                flags, false, true, true, true, Collections.emptyMap());
            Object empty = construct("pbandk.wkt.Empty");
            for (String operation : new String[]{"InstallAddon", "UninstallAddon", "UpgradeAddon",
                    "InstallAddonAllProfiles", "UninstallAddonAllProfiles", "PullAddonsFromApi", "PushAddonsToApi"}) {
                Object value = operation.endsWith("Api") ? empty : descriptor;
                Object attempt = action("Ctx", operation, value);
                check((Boolean) blocked.invoke(null, attempt), operation + " denied at dispatch boundary");
                dispatch.invoke(core, attempt, null);
            }
            for (String operation : new String[]{"InstallAddonForProfile", "UninstallAddonForProfile"}) {
                Object request = construct("com.stremio.core.runtime.msg.ActionProfilesManager$" + operation,
                    "test-profile", descriptor, Collections.emptyMap());
                Object attempt = action("ProfilesManager", operation, request);
                check((Boolean) blocked.invoke(null, attempt), operation + " denied at dispatch boundary");
                dispatch.invoke(core, attempt, null);
            }
            check(!(Boolean) blocked.invoke(null, construct("com.stremio.core.runtime.msg.Action")), "unrelated actions allowed");
            check(!(Boolean) blocked.invoke(null, action("Ctx", "SyncLibraryWithApi", empty)), "library synchronization unaffected");

            // Resolve the real facade from the target app's dependency container.
            Object platform = type("org.koin.mp.KoinPlatformTools").getField("INSTANCE").get(null);
            Object context = platform.getClass().getMethod("defaultContext").invoke(platform);
            Object koin = type("org.koin.core.context.KoinContext").getMethod("get").invoke(context);
            Object registry = koin.getClass().getMethod("getScopeRegistry").invoke(koin);
            Object scope = registry.getClass().getMethod("getRootScope").invoke(registry);
            Class<?> facadeType = type("com.stremio.common.api.StremioApi");
            Object kotlinClass = type("kotlin.jvm.internal.Reflection").getMethod("getOrCreateKotlinClass", Class.class).invoke(null, facadeType);
            Object facade = scope.getClass().getMethod("get", type("kotlin.reflect.KClass"),
                type("org.koin.core.qualifier.Qualifier"), type("kotlin.jvm.functions.Function0")).invoke(scope, kotlinClass, null, null);
            // Seed a promotion instead of relying on today's remote feed or a
            // previously dismissed campaign in this installation's storage.
            Object announcements = construct("com.stremio.common.viewmodels.AnnouncementsViewModel", facade);
            Class<?> announcementsType = announcements.getClass();
            Object announcementState = announcementsType.getMethod("getState").invoke(announcements);
            Method stateValue = type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
            check(stateValue.invoke(announcementState) == null, "startup has no addon announcement");
            Object promotion = construct("com.stremio.common.viewmodels.state.Announcement",
                "jedflix.test.promotion", "Test promotion", "Install an addon", "", "", "", "");
            java.lang.reflect.Field queue = announcementsType.getDeclaredField("announcements");
            queue.setAccessible(true);
            queue.set(announcements, Collections.singletonList(promotion));
            announcementsType.getMethod("loadNext").invoke(announcements);
            check(stateValue.invoke(announcementState) == null, "queued addon promotion cannot become visible");
            announcementsType.getMethod("installAddon", type("kotlin.jvm.functions.Function1")).invoke(announcements, new Object[]{null});
            check(stateValue.invoke(announcementState) == null, "promotion install callback is disabled");
            Object loading = facadeType.getMethod("getLoadCore").invoke(facade);
            Method completed = type("kotlinx.coroutines.Job").getMethod("isCompleted");
            long deadline = System.currentTimeMillis() + 10000;
            while (!(Boolean) completed.invoke(loading) && System.currentTimeMillis() < deadline) Thread.sleep(25);
            check((Boolean) completed.invoke(loading), "native initialization completed");
            Object loadError = type("kotlinx.coroutines.Deferred").getMethod("getCompleted").invoke(loading);
            check(loadError == null, "native core accepted managed profile storage");
            checkNativePresets(core, facade);
            Method normalize = type("com.jedflix.tv.ManagedAddonConfig").getMethod("normalize", String.class, String.class);
            JSONObject fresh = new JSONObject((String) normalize.invoke(null, "profile", null));
            check(fresh.getJSONArray("addons").length() == 2 && fresh.isNull("auth"), "fresh storage gets managed guest preset");
            JSONObject changed = new JSONObject(fresh.toString());
            changed.put("addons", new JSONArray());
            changed.getJSONObject("settings").put("hideSpoilers", true);
            JSONObject restored = new JSONObject((String) normalize.invoke(null, "profile", changed.toString()));
            check(restored.getJSONArray("addons").length() == 2, "empty stored addon list is replaced");
            check(restored.getJSONObject("settings").getBoolean("hideSpoilers"), "existing player preferences preserved");
            check("untouched".equals(normalize.invoke(null, "library", "untouched")), "library storage unaffected");
            for (String methodName : new String[]{"installAddon-gIAlu-s", "uninstallAddon-gIAlu-s", "upgradeAddon-gIAlu-s",
                    "syncAddons-IoAF18A", "traktInstallAddon-IoAF18A"}) {
                for (Method method : facadeType.getMethods()) {
                    if (!method.getName().equals(methodName)) continue;
                    Object[] args = new Object[method.getParameterTypes().length];
                    Object result = method.invoke(facade, args);
                    check(result != null && result.getClass().getName().equals("kotlin.Result$Failure"), methodName + " returns failure");
                }
            }
            for (String methodName : new String[]{"installAddonForProfile", "uninstallAddonForProfile"}) {
                facadeType.getMethod(methodName, String.class, descriptor.getClass()).invoke(facade, "test-profile", descriptor);
                check(true, methodName + " safely rejected");
            }
            checkNativePresets(core, facade);
            if (cometPhase != null) checkCometSettings();
            report.putString("result", "PASS: " + checks + " addon policy checks");
            finish(-1, report);
        } catch (Throwable error) {
            android.util.Log.e("JedflixAddonTest", "FAIL", error);
            report.putString("result", "FAIL after " + checks + " checks: " + error);
            finish(0, report);
        }
    }

    private void field(Object object, String name, Object value) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }

    private void checkCometSettings() throws Exception {
        Class<?> config = type("com.jedflix.tv.CometConfig");
        Method settings = config.getMethod("settings");
        Method url = config.getMethod("manifestUrl");
        Method save = config.getMethod("save", String.class, int.class, double.class,
            String.class, boolean.class, boolean.class, boolean.class);
        // Run only in the runner's disposable fresh-install package.
        check(getTargetContext().getPackageName().equals("com.jedflix.presettest"), "Comet tests use isolated app storage");
        if (cometPhase.equals("restored")) {
            JSONObject restored = (JSONObject) settings.invoke(null);
            check("JEDFLIX_TEST_KEY".equals(restored.getString("apiKey")), "key persists across process restart");
            check(restored.getJSONObject("account").getString("username").equals("TestViewer"), "cached account persists across process restart");
            check(restored.getInt("maxResults") == 7 && restored.getString("quality").equals("1080p"), "Comet options persist across restart");
            config.getMethod("clear").invoke(null);
            check(url.invoke(null).equals("https://comet.elfhosted.com/manifest.json"), "removing key restores managed default endpoint");
            return;
        }
        config.getMethod("saveVerified", String.class, int.class, double.class, String.class, boolean.class, boolean.class, boolean.class, JSONObject.class)
            .invoke(null, "JEDFLIX_TEST_KEY", 7, 12.5, "1080p", true, true, true,
                new JSONObject().put("username", "TestViewer").put("validatedAt", System.currentTimeMillis()));
        String manifestUrl = (String) url.invoke(null);
        String encoded = manifestUrl.substring("https://comet.elfhosted.com/".length(), manifestUrl.length() - "/manifest.json".length());
        JSONObject payload = new JSONObject(new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), "UTF-8"));
        JSONObject service = payload.getJSONArray("debridServices").getJSONObject(0);
        check(service.getString("service").equals("realdebrid") && service.getString("apiKey").equals("JEDFLIX_TEST_KEY"), "hosted Comet debridServices schema matches");
        check(payload.getInt("maxResultsPerResolution") == 7 && payload.getLong("maxSize") == 13421772800L, "Comet result and byte-size limits encoded");
        check(!payload.getJSONObject("resolutions").getBoolean("r2160p") && !payload.getJSONObject("resolutions").getBoolean("r1440p"), "Comet uses named resolution filter keys");
        check(payload.getBoolean("cachedOnly") && !payload.getBoolean("enableTorrent"), "configured Comet uses cached debrid streams");
        java.io.File saved = new java.io.File(getTargetContext().getNoBackupFilesDir(), "comet-settings");
        byte[] savedBytes = new byte[(int) saved.length()];
        try (java.io.FileInputStream stream = new java.io.FileInputStream(saved)) {
            int offset = 0, read;
            while ((read = stream.read(savedBytes, offset, savedBytes.length - offset)) > 0) offset += read;
        }
        String encrypted = new String(savedBytes, "UTF-8");
        check(encrypted.startsWith("jedflix-encrypted-v1:") && !encrypted.contains("JEDFLIX_TEST_KEY"), "saved credentials encrypted outside Android backup");
        String profile = (String) type("com.jedflix.tv.ManagedAddonConfig").getMethod("normalize", String.class, String.class).invoke(null, "profile", null);
        String protectedProfile = (String) config.getMethod("protectProfile", String.class, String.class).invoke(null, "profile", profile);
        check(!protectedProfile.contains(encoded), "native profile does not store encoded key in plaintext");
        check(config.getMethod("unseal", String.class).invoke(null, protectedProfile).equals(profile), "encrypted native profile round trips");
        JSONObject normalized = new JSONObject(profile);
        check(normalized.getJSONArray("addons").length() == 2 && normalized.getJSONArray("addons").getJSONObject(1).getString("transportUrl").equals(manifestUrl), "normalization retains fixed sources and configured Comet URL");
        try {
            save.invoke(null, "", 0, 0d, "all", true, true, true);
            throw new AssertionError("Empty key accepted");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException, "empty key rejected without replacing saved configuration");
        }
        check(url.invoke(null).equals(manifestUrl), "invalid configuration leaves saved configuration intact");
        Object state = construct("com.stremio.common.viewmodels.state.MetaDetailsState");
        Method empty = type("com.jedflix.tv.CometSettings").getMethod("isEmptyResult", android.content.Context.class, Object.class);
        check(!(Boolean) empty.invoke(null, getTargetContext(), state), "no shortcut before streams are selected");
        field(state, "streamListVisible", true);
        field(state, "streamsLabel", "Loading…");
        check(!(Boolean) empty.invoke(null, getTargetContext(), state), "no shortcut during stream loading");
        int noStreams = getTargetContext().getResources().getIdentifier("label_no_stream", "string", getTargetContext().getPackageName());
        field(state, "streamsLabel", getTargetContext().getString(noStreams));
        check((Boolean) empty.invoke(null, getTargetContext(), state), "completed no-streams result shows settings shortcut");
        field(state, "streams", Collections.singletonList(new Object()));
        check(!(Boolean) empty.invoke(null, getTargetContext(), state), "available streams hide settings shortcut");
    }
}
