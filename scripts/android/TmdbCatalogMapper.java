package com.jedflix.tv;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TmdbCatalogMapper {
    private static final String TAG = "JedflixDiscovery";
    private static final String CATALOG_PREFIX = "jedflix-";
    private static final String CINEMETA_BASE = "https://v3-cinemeta.strem.io";
    private static final String ADDON_ID = "tmdb";
    private static final String DETAIL_PREFIX = "stremio:///detail/";

    private static volatile Native nativeTypes;

    private TmdbCatalogMapper() {}

    public static List<Object> catalogs(JSONArray rows) throws Exception {
        List<Object> catalogs = new ArrayList<Object>();
        if (rows == null) return catalogs;
        Native nativeTypes = nativeTypes();
        for (int rowIndex = 0; rowIndex < rows.length(); rowIndex++) {
            JSONObject row = rows.optJSONObject(rowIndex);
            if (row == null) {
                Log.w(TAG, "Skipping discovery row " + rowIndex + ": not an object");
                continue;
            }
            try {
                Object catalog = catalog(nativeTypes, row);
                if (catalog != null) catalogs.add(catalog);
            } catch (Exception failure) {
                Log.w(TAG, "Skipping discovery row " + rowIndex + ": " + message(failure));
            }
        }
        return catalogs;
    }

    /** Keep complete TMDB artwork while accepting native library flags and IMDb data. */
    public static Object preserveArtwork(Object fresh, Object previous) throws Exception {
        if (fresh == null) return previous;
        if (!get(previous, "Id").equals(get(fresh, "Id"))) return previous;
        List<Object> links = new ArrayList<Object>((List<?>) get(previous, "Links"));
        for (Object link : (List<?>) get(fresh, "Links")) {
            if ("imdb".equals(get(link, "Category"))) links.add(link);
        }
        Object preview = nativeTypes().metaItemPreview.newInstance(
            get(previous, "Id"), get(previous, "Type"), get(previous, "Name"),
            get(previous, "PosterShape"), get(previous, "Poster"), get(previous, "Background"),
            get(previous, "Logo"), get(previous, "Description"), get(previous, "ReleaseInfo"),
            get(previous, "Runtime"), get(previous, "Released"), links,
            get(previous, "BehaviorHints"), get(previous, "DeepLinks"),
            get(fresh, "InLibrary"), get(fresh, "Watched"), get(fresh, "InCinema"), Collections.emptyMap());
        return HomeDiscovery.registerPreview(preview);
    }

    private static Object get(Object object, String property) throws Exception {
        return object.getClass().getMethod("get" + property).invoke(object);
    }

    private static Object catalog(Native nativeTypes, JSONObject row) throws Exception {
        String rowId = text(row, "id");
        String title = first(text(row, "title"), text(row, "name"));
        String type = normalizeType(text(row, "type"));
        JSONArray metas = row.optJSONArray("metas");
        if (rowId.isEmpty() || title.isEmpty() || type == null || metas == null) return null;

        String catalogId = catalogId(rowId);
        List<Object> items = new ArrayList<Object>();
        for (int metaIndex = 0; metaIndex < metas.length(); metaIndex++) {
            JSONObject meta = metas.optJSONObject(metaIndex);
            if (meta == null) {
                Log.w(TAG, "Skipping row " + catalogId + " item " + metaIndex + ": not an object");
                continue;
            }
            try {
                Object item = preview(nativeTypes, meta, type);
                if (item != null) items.add(item);
            } catch (Exception failure) {
                Log.w(TAG, "Skipping row " + catalogId + " item " + metaIndex + ": "
                    + message(failure));
            }
        }
        if (items.isEmpty()) return null;

        Object page = nativeTypes.page.newInstance(items, Collections.emptyMap());
        Object content = nativeTypes.contentReady.newInstance(page);
        Object path = nativeTypes.resourcePath.newInstance(
            "catalog", type, catalogId, Collections.emptyList(), Collections.emptyMap());
        Object request = nativeTypes.resourceRequest.newInstance(
            CINEMETA_BASE, path, Collections.emptyMap());
        Object deepLinks = nativeTypes.discoverDeepLinks.newInstance(
            "", Collections.emptyMap());
        Object loadablePage = nativeTypes.loadablePage.newInstance(
            title, ADDON_ID, catalogId, title, type, request, deepLinks, content,
            Collections.emptyMap());

        List<Object> pages = new ArrayList<Object>(1);
        pages.add(loadablePage);
        return nativeTypes.catalog.newInstance(pages, Collections.emptyMap());
    }

    private static Object preview(Native nativeTypes, JSONObject meta, String rowType)
            throws Exception {
        String id = text(meta, "id").toLowerCase(Locale.US);
        if (!id.matches("tt[0-9]+")) {
            Log.d(TAG, "Dropping a discovery item without an IMDb id");
            return null;
        }
        String type = normalizeType(text(meta, "type"));
        if (type == null) type = rowType;
        String name = first(text(meta, "name"), text(meta, "title"));
        if (name.isEmpty()) {
            Log.d(TAG, "Dropping a discovery item without a name");
            return null;
        }

        String poster = text(meta, "poster");
        String background = text(meta, "background");
        String logo = text(meta, "logo");
        if (logo.isEmpty()) logo = null;
        String description = text(meta, "description");
        String releaseInfo = text(meta, "releaseInfo");
        String runtime = text(meta, "runtime");
        List<Object> links = links(nativeTypes, meta);
        Object behaviorHints = nativeTypes.behaviorHints.newInstance(
            null, null, false, Collections.emptyMap());
        String detailUri = DETAIL_PREFIX + type + "/" + id;
        Object deepLinks = nativeTypes.deepLinks.newInstance(
            detailUri, detailUri, null, Collections.emptyMap());

        Object preview = nativeTypes.metaItemPreview.newInstance(
            id, type, name, nativeTypes.poster, poster, background, logo, description,
            releaseInfo, runtime, null, links, behaviorHints, deepLinks,
            false, false, false, Collections.emptyMap());
        return HomeDiscovery.registerPreview(preview);
    }

    private static List<Object> links(Native nativeTypes, JSONObject meta) throws Exception {
        List<Object> result = new ArrayList<Object>();
        Set<String> seen = new HashSet<String>();
        JSONArray links = meta.optJSONArray("links");
        if (links != null) {
            for (int index = 0; index < links.length(); index++) {
                JSONObject link = links.optJSONObject(index);
                if (link != null) {
                    addLink(nativeTypes, result, seen, text(link, "name"), text(link, "category"));
                } else {
                    addLink(nativeTypes, result, seen, text(links, index), "Links");
                }
            }
        }
        addArrayLinks(nativeTypes, result, seen, meta.optJSONArray("genres"), "Genres");
        addArrayLinks(nativeTypes, result, seen, meta.optJSONArray("cast"), "Cast");
        return result;
    }

    private static void addArrayLinks(Native nativeTypes, List<Object> result, Set<String> seen,
            JSONArray values, String category) throws Exception {
        if (values == null) return;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            String name = value == null ? text(values, index) : text(value, "name");
            addLink(nativeTypes, result, seen, name, category);
        }
    }

    private static void addLink(Native nativeTypes, List<Object> result, Set<String> seen,
            String name, String category) throws Exception {
        if (name.isEmpty()) return;
        String safeCategory = category.isEmpty() ? "Links" : category;
        if (!seen.add(safeCategory + "\n" + name)) return;
        result.add(nativeTypes.linkPreview.newInstance(
            name, safeCategory, Collections.emptyMap()));
    }

    private static String catalogId(String value) {
        String id = value.trim();
        return id.startsWith(CATALOG_PREFIX) ? id : CATALOG_PREFIX + id;
    }

    private static String normalizeType(String value) {
        String type = value.trim().toLowerCase(Locale.US);
        if ("movie".equals(type)) return "movie";
        if ("series".equals(type) || "show".equals(type) || "tv".equals(type)) return "series";
        return null;
    }

    private static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value == null ? "" : value.trim();
    }

    private static String text(JSONArray array, int index) {
        String value = array.optString(index, "");
        return value == null ? "" : value.trim();
    }

    private static String first(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private static String message(Exception failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty()
            ? cause.getClass().getSimpleName() : message;
    }

    private static Native nativeTypes() throws Exception {
        Native result = nativeTypes;
        if (result != null) return result;
        synchronized (TmdbCatalogMapper.class) {
            result = nativeTypes;
            if (result == null) {
                result = new Native();
                nativeTypes = result;
            }
            return result;
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static final class Native {
        final Constructor<?> catalog;
        final Constructor<?> loadablePage;
        final Constructor<?> contentReady;
        final Constructor<?> page;
        final Constructor<?> resourceRequest;
        final Constructor<?> resourcePath;
        final Constructor<?> discoverDeepLinks;
        final Constructor<?> metaItemPreview;
        final Constructor<?> linkPreview;
        final Constructor<?> behaviorHints;
        final Constructor<?> deepLinks;
        final Object poster;

        Native() throws Exception {
            Class<?> catalogClass = type("com.stremio.core.models.Catalog");
            Class<?> loadablePageClass = type("com.stremio.core.models.LoadablePage");
            Class<?> contentClass = type("com.stremio.core.models.LoadablePage$Content");
            Class<?> contentReadyClass = type("com.stremio.core.models.LoadablePage$Content$Ready");
            Class<?> pageClass = type("com.stremio.core.models.Page");
            Class<?> resourceRequestClass = type("com.stremio.core.types.addon.ResourceRequest");
            Class<?> resourcePathClass = type("com.stremio.core.types.addon.ResourcePath");
            Class<?> discoverDeepLinksClass = type("com.stremio.core.models.DiscoverDeepLinks");
            Class<?> metaItemPreviewClass = type("com.stremio.core.types.resource.MetaItemPreview");
            Class<?> linkPreviewClass = type("com.stremio.core.types.resource.LinkPreview");
            Class<?> posterShapeClass = type("com.stremio.core.types.resource.PosterShape");
            Class<?> posterShapePosterClass =
                type("com.stremio.core.types.resource.PosterShape$POSTER");
            Class<?> behaviorHintsClass =
                type("com.stremio.core.types.resource.MetaItemBehaviorHints");
            Class<?> deepLinksClass = type("com.stremio.core.types.resource.MetaItemDeepLinks");
            Class<?> timestampClass = type("pbandk.wkt.Timestamp");

            catalog = catalogClass.getConstructor(List.class, Map.class);
            loadablePage = loadablePageClass.getConstructor(
                String.class, String.class, String.class, String.class, String.class,
                resourceRequestClass, discoverDeepLinksClass, contentClass, Map.class);
            contentReady = contentReadyClass.getConstructor(pageClass);
            page = pageClass.getConstructor(List.class, Map.class);
            resourceRequest = resourceRequestClass.getConstructor(
                String.class, resourcePathClass, Map.class);
            resourcePath = resourcePathClass.getConstructor(
                String.class, String.class, String.class, List.class, Map.class);
            discoverDeepLinks = discoverDeepLinksClass.getConstructor(String.class, Map.class);
            metaItemPreview = metaItemPreviewClass.getConstructor(
                String.class, String.class, String.class, posterShapeClass, String.class,
                String.class, String.class, String.class, String.class, String.class,
                timestampClass, List.class, behaviorHintsClass, deepLinksClass,
                boolean.class, boolean.class, boolean.class, Map.class);
            linkPreview = linkPreviewClass.getConstructor(String.class, String.class, Map.class);
            behaviorHints = behaviorHintsClass.getConstructor(
                String.class, String.class, boolean.class, Map.class);
            deepLinks = deepLinksClass.getConstructor(
                String.class, String.class, String.class, Map.class);
            poster = posterShapePosterClass.getField("INSTANCE").get(null);
        }
    }
}
