package com.jedflix.test;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only instrumentation probe for the Jedflix Home discovery layer.
 *
 * It runs inside the target app process, reads only the persisted discovery
 * rows under filesDir/jedflix-discovery/home-*.json, and exercises the native
 * Stremio model constructors used by TmdbCatalogMapper. It never opens a UI,
 * reads account preferences, or performs network I/O.
 */
public final class DiscoveryProbe extends Instrumentation {
    private static final String TAG = "JedflixDiscoveryProbe";
    private static final String TARGET_PACKAGE = "com.jedflix.stremio";
    private static final int MIN_RAW_ROWS = 14;
    private static final int MAX_RAW_ROWS = 15;
    private static final int ORDINARY_ROWS = 14;
    private static final int ORDINARY_RAW_POOL = 48;
    private static final int ORDINARY_VISIBLE_MIN = 4;
    private static final int ORDINARY_VISIBLE_MAX = 32;
    private static final int CURATED_VISIBLE_MIN = 1;
    private static final int CURATED_VISIBLE_MAX = 80;
    private static final String CURATED_ROW_ID = "home.jeds-picks";
    private static final String CURATED_ROW_TITLE = "Jed\u2019s Picks";
    private static final long MAX_SNAPSHOT_BYTES = 3000000L;
    private static final String CINEMETA_BASE = "https://v3-cinemeta.strem.io";
    private static final String DETAIL_PREFIX = "stremio:///detail/";

    private ClassLoader loader;
    private int checks;
    private int rawItems;
    private int ordinaryRows;
    private int curatedRows;
    private int movieItems;
    private int seriesItems;
    private int assembledRows;
    private int assembledOrdinaryRows;
    private int assembledCuratedRows;
    private int assembledItems;
    private int curatedWatchedItems;
    private final StringBuilder rowSummary = new StringBuilder();
    private String totals = "";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            if (!TARGET_PACKAGE.equals(getTargetContext().getPackageName())) {
                throw new AssertionError("probe targets " + getTargetContext().getPackageName()
                    + " instead of " + TARGET_PACKAGE);
            }
            verifySnapshot();
            report.putString("result", "PASS: " + checks + " discovery checks");
            report.putString("rows", rowSummary.toString());
            report.putString("total", totals);
            finish(-1, report);
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            Log.e(TAG, "FAIL after " + checks + " checks", cause);
            report.putString("result", "FAIL after " + checks + " checks: " + describe(cause));
            report.putString("rows", rowSummary.toString());
            report.putString("total", totals);
            finish(0, report);
        }
    }

    private void verifySnapshot() throws Exception {
        File directory = new File(getTargetContext().getFilesDir(), "jedflix-discovery");
        check(directory.isDirectory(), "discovery cache directory exists: " + directory);
        File[] listing = directory.listFiles();
        check(listing != null, "discovery cache directory is readable");

        List<File> snapshots = new ArrayList<File>();
        for (File file : listing) {
            if (file.isFile() && file.getName().startsWith("home-")
                    && file.getName().endsWith(".json")) {
                snapshots.add(file);
            }
        }
        check(!snapshots.isEmpty(), "no persisted discovery snapshot at " + directory
            + "/home-*.json; open Home once so the app can create it");
        Collections.sort(snapshots, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                int byTime = Long.compare(right.lastModified(), left.lastModified());
                return byTime != 0 ? byTime : left.getName().compareTo(right.getName());
            }
        });

        File file = snapshots.get(0);
        check(file.length() <= MAX_SNAPSHOT_BYTES, "snapshot stays under the 3 MB cache limit");
        Log.i(TAG, "SNAPSHOT " + file.getName() + " bytes=" + file.length()
            + " profiles=" + snapshots.size());
        JSONArray rows = new JSONArray(read(file));
        Log.i(TAG, "RAW rows=" + rows.length());
        check(rows.length() >= MIN_RAW_ROWS && rows.length() <= MAX_RAW_ROWS,
            "persisted raw rows are " + MIN_RAW_ROWS + "-" + MAX_RAW_ROWS
                + " (found " + rows.length() + ")");

        JSONArray mappable = verifyRawRows(rows);
        List<Object> catalogs = map(mappable);
        Map<String, Object> byId = verifyCatalogs(mappable, catalogs);
        verifyRowDefaults(mappable, byId);
        verifyAssembled(rows);
        verifyPreviewMerge();

        totals = "rawRows=" + rows.length()
            + " ordinaryRows=" + ordinaryRows
            + " curatedRows=" + curatedRows
            + " mappableRows=" + mappable.length()
            + " rawItems=" + rawItems
            + " nativeCatalogs=" + catalogs.size()
            + " movieItems=" + movieItems
            + " seriesItems=" + seriesItems
            + " assembledRows=" + assembledRows
            + " assembledOrdinaryRows=" + assembledOrdinaryRows
            + " assembledCuratedRows=" + assembledCuratedRows
            + " assembledItems=" + assembledItems
            + " curatedWatchedItems=" + curatedWatchedItems;
        Log.i(TAG, "TOTAL " + totals);
    }

    private JSONArray verifyRawRows(JSONArray rows) throws Exception {
        Set<String> rowIds = new HashSet<String>();
        JSONArray mappable = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            check(row != null, "raw row " + i + " is a JSON object");
            String id = trimmed(row, "id");
            String title = trimmed(row, "title");
            String type = normalizeType(trimmed(row, "type"));
            JSONArray metas = row.optJSONArray("metas");
            boolean curated = row.optBoolean("curated");
            check(!id.isEmpty(), "raw row " + i + " has an id");
            check(rowIds.add(id), "raw row id is unique: " + id);
            check(!title.isEmpty(), "raw row " + i + " has a title");
            check(type != null, "raw row " + i + " is movie or series");

            if (CURATED_ROW_ID.equals(id)) {
                check(curated, "Jed's Picks row is curated");
                check("movie".equals(type), "Jed's Picks row advertises the movie shelf");
                check(CURATED_ROW_TITLE.equals(title), "Jed's Picks title matches");
                check(++curatedRows == 1, "there is at most one Jed's Picks row");
                check(metas != null, "Jed's Picks row has a metas array");
                check(metas.length() <= CURATED_VISIBLE_MAX,
                    "Jed's Picks raw pool is at most " + CURATED_VISIBLE_MAX
                        + " (found " + metas.length() + ")");
                verifyCuratedRaw(metas);
                if (metas.length() == 0) {
                    Log.i(TAG, "CURATED empty; filtered before native mapping");
                }
            } else {
                check(!curated, "only Jed's Picks is marked curated: " + id);
                ordinaryRows++;
                check(metas != null && metas.length() > 0,
                    "ordinary raw row " + id + " has items");
                check(metas.length() <= ORDINARY_RAW_POOL,
                    "ordinary raw pool is at most " + ORDINARY_RAW_POOL + " for " + id
                        + " (found " + metas.length() + ")");
            }

            Log.i(TAG, "ROW " + i + " " + id + " \"" + title + "\" " + type
                + " items=" + metas.length() + (curated ? " curated" : ""));
            if (rowSummary.length() > 0) rowSummary.append("; ");
            rowSummary.append(title).append(" (").append(metas.length());
            if (curated) rowSummary.append(", curated");
            rowSummary.append(')');
            rawItems += metas.length();
            if (metas.length() > 0) mappable.put(row);
        }
        check(ordinaryRows == ORDINARY_ROWS, "raw rows contain " + ORDINARY_ROWS
            + " discovery shelves (found " + ordinaryRows + ")");
        Log.i(TAG, "RAW totalItems=" + rawItems + " mappableRows=" + mappable.length());
        return mappable;
    }

    private void verifyCuratedRaw(JSONArray metas) throws Exception {
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < metas.length(); i++) {
            JSONObject meta = metas.optJSONObject(i);
            check(meta != null, "curated item " + i + " is an object");
            String id = trimmed(meta, "id").toLowerCase(Locale.US);
            check(id.matches("tt[0-9]+"), "curated item " + i + " has an IMDb id");
            check(ids.add(id), "curated row has unique IMDb ids: " + id);
            check(!trimmed(meta, "name").isEmpty(), "curated item has a name: " + id);
            check(!trimmed(meta, "poster").isEmpty(), "curated item has a poster: " + id);
            check(normalizeType(trimmed(meta, "type")) != null,
                "curated item type is movie or series: " + id);
        }
    }

    private List<Object> map(JSONArray rows) throws Exception {
        Method method = type("com.jedflix.tv.TmdbCatalogMapper")
            .getMethod("catalogs", JSONArray.class);
        Object value = callStatic(method, rows);
        check(value instanceof List, "TmdbCatalogMapper.catalogs returns a list");
        List<?> list = (List<?>) value;
        Log.i(TAG, "NATIVE catalogs=" + list.size());
        if (list.size() != rows.length()) diagnoseMapping(rows, list);
        check(list.size() == rows.length(),
            "native catalog count matches raw row count (found " + list.size()
                + " for " + rows.length() + " rows)");
        List<Object> result = new ArrayList<Object>(list.size());
        for (Object item : list) result.add(item);
        return result;
    }

    /**
     * The public mapper catches per-row failures. If the count is short, walk
     * the rows through the same private mapper entry point so an
     * InvocationTargetException surfaces its actual constructor cause.
     */
    private void diagnoseMapping(JSONArray rows, List<?> catalogs) throws Exception {
        Class<?> mapper = type("com.jedflix.tv.TmdbCatalogMapper");
        Class<?> nativeTypes = type("com.jedflix.tv.TmdbCatalogMapper$Native");
        Method accessor = mapper.getDeclaredMethod("nativeTypes");
        accessor.setAccessible(true);
        Object nativeInstance = callStatic(accessor);
        Method catalog = mapper.getDeclaredMethod("catalog", nativeTypes, JSONObject.class);
        catalog.setAccessible(true);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Object value = callStatic(catalog, nativeInstance, row);
            if (value == null) {
                throw new AssertionError("Row " + row.optString("id")
                    + " did not produce a native catalog");
            }
        }
        throw new AssertionError("TmdbCatalogMapper returned " + catalogs.size()
            + " catalogs for " + rows.length() + " rows");
    }

    private Map<String, Object> verifyCatalogs(JSONArray rows, List<Object> catalogs)
            throws Exception {
        Map<String, Object> byId = new HashMap<String, Object>();
        for (Object catalog : catalogs) {
            List<?> pages = (List<?>) call(catalog, "getPages");
            check(pages != null && pages.size() == 1, "catalog has exactly one page");
            Object page = pages.get(0);
            String catalogId = (String) call(page, "getCatalogId");
            check(catalogId != null && catalogId.startsWith("jedflix-"),
                "catalog id is Jedflix-prefixed: " + catalogId);
            check(byId.put(catalogId, catalog) == null,
                "catalog id is unique: " + catalogId);
        }
        check(byId.size() == rows.length(), "every raw row has a native catalog (found "
            + byId.size() + " for " + rows.length() + " rows)");

        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            String rowId = trimmed(row, "id");
            String title = trimmed(row, "title");
            String type = normalizeType(trimmed(row, "type"));
            String catalogId = catalogId(rowId);
            Object catalog = byId.get(catalogId);
            check(catalog != null, "native catalog exists for " + rowId);
            List<?> pages = (List<?>) call(catalog, "getPages");
            Object page = pages.get(0);
            verifyPage(rowId, title, type, catalogId, page);
            List<?> items = (List<?>) call(call(page, "getReady"), "getMetaItems");
            JSONArray metas = row.optJSONArray("metas");
            check(items != null && items.size() == metas.length(),
                "native preview count matches raw item count for " + rowId + " (found "
                    + (items == null ? 0 : items.size()) + " for " + metas.length() + ")");
            for (int j = 0; j < items.size(); j++) {
                verifyItem(rowId, type, metas.optJSONObject(j), items.get(j));
            }
            Log.i(TAG, "NATIVE_ROW " + catalogId + " \"" + title + "\" " + type
                + " items=" + items.size());
        }
        Log.i(TAG, "NATIVE totalItems=" + (movieItems + seriesItems));
        check(movieItems > 0, "sample covers movie detail links");
        check(seriesItems > 0, "sample covers series detail links");
        return byId;
    }

    private void verifyPage(String rowId, String title, String type, String catalogId,
            Object page) throws Exception {
        check(title.equals(call(page, "getTitle")), "page title matches raw row " + rowId);
        check("tmdb".equals(call(page, "getAddonId")), "page addon id is tmdb for " + rowId);
        check(catalogId.equals(call(page, "getCatalogId")),
            "page catalog id matches " + catalogId);
        check(title.equals(call(page, "getCatalogName")),
            "page catalog name matches row " + rowId);
        check(type.equals(call(page, "getCatalogType")),
            "page catalog type matches " + type + " for " + rowId);

        Object request = call(page, "getRequest");
        check(request != null, "page request exists for " + rowId);
        check(CINEMETA_BASE.equals(call(request, "getBase")),
            "page request uses Cinemeta base for " + rowId);
        Object path = call(request, "getPath");
        check(path != null, "page resource path exists for " + rowId);
        check("catalog".equals(call(path, "getResource")),
            "page resource is catalog for " + rowId);
        check(type.equals(call(path, "getType")),
            "page resource type matches " + type + " for " + rowId);
        check(catalogId.equals(call(path, "getId")),
            "page resource id matches " + catalogId);
        List<?> extra = (List<?>) call(path, "getExtra");
        check(extra != null && extra.isEmpty(),
            "page resource has no extra segments for " + rowId);

        Object deepLinks = call(page, "getDeepLinks");
        check(deepLinks != null, "page deep links exist for " + rowId);
        check("".equals(call(deepLinks, "getDiscover")),
            "page discover deep link is empty for " + rowId);

        Object content = call(page, "getContent");
        check(content != null, "page content exists for " + rowId);
        check(content.getClass().getName().endsWith("LoadablePage$Content$Ready"),
            "page content is ready for " + rowId);
        Object ready = call(page, "getReady");
        check(ready != null, "page ready content is a Page for " + rowId);
        check(call(page, "getLoading") == null, "page has no loading content for " + rowId);
        check(call(page, "getError") == null, "page has no error content for " + rowId);
    }

    private void verifyItem(String rowId, String rowType, JSONObject raw, Object item)
            throws Exception {
        check(raw != null, "raw item is an object in " + rowId);
        check(item != null, "native item exists in " + rowId);
        String id = trimmed(raw, "id").toLowerCase(Locale.US);
        check(id.matches("tt[0-9]+"), "raw item id is IMDb: " + id + " in " + rowId);
        String name = first(trimmed(raw, "name"), trimmed(raw, "title"));
        check(!name.isEmpty(), "raw item name is present for " + id);
        String type = normalizeType(trimmed(raw, "type"));
        if (type == null) type = rowType;
        check(type != null, "raw item type is movie or series for " + id);
        String expectedDetail = DETAIL_PREFIX + type + "/" + id;

        check(id.equals(call(item, "getId")), "native IMDb id matches " + id);
        check(type.equals(call(item, "getType")),
            "native type matches " + type + " for " + id);
        check(name.equals(call(item, "getName")), "native name matches raw name for " + id);
        check(!((String) call(item, "getPoster")).isEmpty(),
            "native poster is present for " + id);
        check(!((String) call(item, "getBackground")).isEmpty(),
            "native background is present for " + id);
        check(call(item, "getPosterShape") != null,
            "native poster shape is set for " + id);
        check(call(item, "getBehaviorHints") != null,
            "native behavior hints are set for " + id);
        Object deepLinks = call(item, "getDeepLinks");
        check(deepLinks != null, "native deep links are set for " + id);
        check(expectedDetail.equals(call(deepLinks, "getMetaDetailsStreams")),
            "native stream detail link matches " + expectedDetail);
        check(expectedDetail.equals(call(deepLinks, "getMetaDetailsVideos")),
            "native video detail link matches " + expectedDetail);
        if ("movie".equals(type)) movieItems++;
        else seriesItems++;
    }

    private void verifyRowDefaults(JSONArray rows, Map<String, Object> catalogs)
            throws Exception {
        Class<?> home = type("com.jedflix.tv.HomeDiscovery");
        Method rowDefaults = home.getMethod("rowDefaults", Object.class);
        Method isSyntheticRow = home.getMethod("isSyntheticRow", Object.class);
        Class<?> catalogClass = type("com.stremio.core.models.Catalog");
        Class<?> rowClass = type("com.stremio.common.viewmodels.state.MetaRow$CatalogRow");
        Constructor<?> constructor = rowClass.getConstructor(
            catalogClass, boolean.class, boolean.class, boolean.class);

        for (int i = 0; i < rows.length(); i++) {
            String rowId = trimmed(rows.optJSONObject(i), "id");
            Object catalog = catalogs.get(catalogId(rowId));
            check(catalog != null, "rowDefaults has a catalog for " + rowId);
            int defaults = (Integer) callStatic(rowDefaults, catalog);
            check(defaults == 6, "rowDefaults is 6 for " + rowId + " (found " + defaults + ")");
            Object row = construct(constructor, catalog, Boolean.FALSE, Boolean.TRUE,
                Boolean.FALSE);
            check(Boolean.TRUE.equals(callStatic(isSyntheticRow, row)),
                "isSyntheticRow detects " + rowId);
            check(Boolean.FALSE.equals(call(row, "getShowOnError")),
                "native CatalogRow hides errors for " + rowId);
            check(Boolean.TRUE.equals(call(row, "getShowTitle")),
                "native CatalogRow shows the title for " + rowId);
            check(Boolean.FALSE.equals(call(row, "getShowSeeAllButton")),
                "native CatalogRow hides See All for " + rowId);
            check(catalog == call(row, "getCatalog"),
                "native CatalogRow keeps its catalog for " + rowId);
        }
    }

    private void verifyPreviewMerge() throws Exception {
        Class<?> previewClass = type("com.stremio.core.types.resource.MetaItemPreview");
        Class<?> posterShapeClass = type("com.stremio.core.types.resource.PosterShape");
        Class<?> posterClass = type("com.stremio.core.types.resource.PosterShape$POSTER");
        Class<?> behaviorHintsClass =
            type("com.stremio.core.types.resource.MetaItemBehaviorHints");
        Class<?> deepLinksClass = type("com.stremio.core.types.resource.MetaItemDeepLinks");
        Class<?> timestampClass = type("pbandk.wkt.Timestamp");
        Constructor<?> preview = previewClass.getConstructor(
            String.class, String.class, String.class, posterShapeClass,
            String.class, String.class, String.class, String.class, String.class,
            String.class, timestampClass, List.class, behaviorHintsClass, deepLinksClass,
            boolean.class, boolean.class, boolean.class, Map.class);

        Object posterShape = posterClass.getField("INSTANCE").get(null);
        Object behaviorHints = construct(behaviorHintsClass.getConstructor(
            String.class, String.class, boolean.class, Map.class),
            null, null, Boolean.FALSE, Collections.emptyMap());
        String id = "tt1234567";
        String type = "movie";
        String detail = DETAIL_PREFIX + type + "/" + id;
        Object deepLinks = construct(deepLinksClass.getConstructor(
            String.class, String.class, String.class, Map.class),
            detail, detail, null, Collections.emptyMap());
        List<Object> links = Collections.emptyList();
        Map<Object, Object> unknown = Collections.emptyMap();

        String previousPoster = "https://image.tmdb.org/t/p/w500/previous.jpg";
        String previousBackground = "https://image.tmdb.org/t/p/w1280/previous.jpg";
        String previousLogo = "https://image.tmdb.org/t/p/w500/previous-logo.png";
        String previousDescription = "Previous description";
        String previousReleaseInfo = "2024";
        String previousRuntime = "100 min";
        Object previous = construct(preview,
            id, type, "Previous", posterShape,
            previousPoster, previousBackground, previousLogo, previousDescription,
            previousReleaseInfo, previousRuntime, null, links, behaviorHints, deepLinks,
            false, false, false, unknown);

        Class<?> home = type("com.jedflix.tv.HomeDiscovery");
        Method register = home.getMethod("registerPreview", Object.class);
        Method hasLocal = home.getMethod("hasLocalPreview", Object.class);
        Method merge = home.getMethod("mergeFullPreview", Object.class, Object.class);

        check(callStatic(register, previous) == previous,
            "registerPreview returns the same preview identity");
        check(Boolean.TRUE.equals(callStatic(hasLocal, previous)),
            "registered native preview is local");

        // Fresh metadata is deliberately sparse; only the ID/type and flags are
        // useful. Poster shape, links, hints and unknown fields are non-null by
        // native constructor contract.
        Object fresh = construct(preview,
            id, type, "Different", posterShape,
            null, null, null, null, null, null, null,
            links, behaviorHints, deepLinks, true, true, true, unknown);
        check(Boolean.FALSE.equals(callStatic(hasLocal, fresh)),
            "unregistered fresh preview is not local");
        check(callStatic(merge, fresh, fresh) == fresh,
            "mergeFullPreview keeps fresh when previous is not registered");

        Object equalCopy = construct(preview,
            id, type, "Previous", posterShape,
            previousPoster, previousBackground, previousLogo, previousDescription,
            previousReleaseInfo, previousRuntime, null, links, behaviorHints, deepLinks,
            false, false, false, unknown);
        check(Boolean.FALSE.equals(callStatic(hasLocal, equalCopy)),
            "an equal copy is not the registered preview identity");
        check(callStatic(merge, fresh, equalCopy) == fresh,
            "an equal copy cannot trigger artwork merge");

        Object merged = callStatic(merge, fresh, previous);
        check(merged != null, "artwork merge returns a preview");
        check(merged != fresh && merged != previous,
            "artwork merge returns a new registered preview");
        check(Boolean.TRUE.equals(callStatic(hasLocal, merged)),
            "merged preview is registered as local");
        check(id.equals(call(merged, "getId")), "merged preview keeps the IMDb id");
        check(type.equals(call(merged, "getType")), "merged preview keeps the type");
        check("Previous".equals(call(merged, "getName")),
            "merged preview keeps the previous name");
        check(previousPoster.equals(call(merged, "getPoster")),
            "merged preview preserves the previous poster");
        check(previousBackground.equals(call(merged, "getBackground")),
            "merged preview preserves the previous background");
        check(previousLogo.equals(call(merged, "getLogo")),
            "merged preview preserves the previous logo");
        check(previousDescription.equals(call(merged, "getDescription")),
            "merged preview preserves the previous description");
        check(previousReleaseInfo.equals(call(merged, "getReleaseInfo")),
            "merged preview preserves the previous release info");
        check(previousRuntime.equals(call(merged, "getRuntime")),
            "merged preview preserves the previous runtime");
        check(Boolean.TRUE.equals(call(merged, "getInLibrary")),
            "merged preview accepts the fresh in-library flag");
        check(Boolean.TRUE.equals(call(merged, "getWatched")),
            "merged preview accepts the fresh watched flag");
        check(Boolean.TRUE.equals(call(merged, "getInCinema")),
            "merged preview accepts the fresh in-cinema flag");
        Log.i(TAG, "PREVIEW_MERGE id=" + id + " name=" + call(merged, "getName")
            + " inLibrary=" + call(merged, "getInLibrary")
            + " watched=" + call(merged, "getWatched"));
    }

    private void verifyAssembled(JSONArray rows) throws Exception {
        Object signals = callStatic(type("com.jedflix.tv.DiscoverySignals")
            .getMethod("snapshot"));
        check(signals instanceof JSONObject, "DiscoverySignals.snapshot returns signals");
        JSONObject signalObject = (JSONObject) signals;
        Set<String> excluded = new HashSet<String>();
        JSONArray exclude = signalObject.optJSONArray("exclude");
        if (exclude != null) {
            for (int i = 0; i < exclude.length(); i++) {
                excluded.add(exclude.optString(i));
            }
        }

        Method assemble = type("com.jedflix.tv.DiscoveryFeed")
            .getMethod("assemble", JSONArray.class, JSONObject.class);
        Object value = callStatic(assemble, rows, signals);
        check(value instanceof JSONArray, "DiscoveryFeed.assemble returns rows");
        JSONArray assembled = (JSONArray) value;
        assembledRows = assembled.length();
        Log.i(TAG, "ASSEMBLED rows=" + assembledRows);
        check(assembledRows > 0, "assemble keeps at least one discovery row");
        check(assembledRows <= MAX_RAW_ROWS, "assemble keeps at most " + MAX_RAW_ROWS
            + " rows (found " + assembledRows + ")");

        JSONObject rawCurated = findRow(rows, CURATED_ROW_ID);
        JSONArray rawCuratedMetas = rawCurated == null
            ? new JSONArray() : rawCurated.optJSONArray("metas");
        if (rawCuratedMetas == null) rawCuratedMetas = new JSONArray();

        // Curated shelves are published first and are allowed to contain watched
        // or saved titles. Only their own rows must be identity-unique.
        Set<String> curatedIds = new HashSet<String>();
        for (int i = 0; i < assembledRows; i++) {
            JSONObject row = assembled.optJSONObject(i);
            check(row != null, "assembled row " + i + " is an object");
            if (!row.optBoolean("curated")) continue;
            assembledCuratedRows++;
            check(CURATED_ROW_ID.equals(row.optString("id")),
                "curated assembled row keeps its id");
            JSONArray metas = row.optJSONArray("metas");
            check(metas != null && metas.length() >= CURATED_VISIBLE_MIN
                    && metas.length() <= CURATED_VISIBLE_MAX,
                "curated assembled row has " + CURATED_VISIBLE_MIN + "-"
                    + CURATED_VISIBLE_MAX + " items (found "
                    + (metas == null ? 0 : metas.length()) + ")");
            Set<String> inRow = new HashSet<String>();
            for (int j = 0; j < metas.length(); j++) {
                JSONObject meta = metas.optJSONObject(j);
                check(meta != null, "curated assembled item " + j + " is an object");
                String id = meta.optString("id", "");
                check(id.matches("tt[0-9]+"), "curated assembled IMDb id is valid: " + id);
                check(inRow.add(id), "curated row IDs are unique: " + id);
                curatedIds.add(id);
                assembledItems++;
                if (excluded.contains(id)) curatedWatchedItems++;
            }
            check(rawCuratedMetas.length() == metas.length(),
                "curated assembly keeps every raw pick (found " + metas.length()
                    + " for " + rawCuratedMetas.length() + ")");
            for (int j = 0; j < metas.length(); j++) {
                check(rawCuratedMetas.optJSONObject(j).optString("id", "")
                    .equalsIgnoreCase(metas.optJSONObject(j).optString("id", "")),
                    "curated assembly preserves pick order at " + j);
            }
        }
        check(assembledCuratedRows <= 1, "assemble keeps at most one curated row");
        check(rawCuratedMetas.length() > 0 || assembledCuratedRows == 0,
            "an empty curated row is filtered before publication");
        check(rawCuratedMetas.length() == 0 || assembledCuratedRows == 1,
            "a non-empty curated row is published");

        Set<String> ordinaryIds = new HashSet<String>();
        for (int i = 0; i < assembledRows; i++) {
            JSONObject row = assembled.optJSONObject(i);
            if (row.optBoolean("curated")) continue;
            assembledOrdinaryRows++;
            String rowId = row.optString("id", "");
            JSONArray metas = row.optJSONArray("metas");
            check(metas != null && metas.length() >= ORDINARY_VISIBLE_MIN
                    && metas.length() <= ORDINARY_VISIBLE_MAX,
                "ordinary assembled row " + rowId + " has " + ORDINARY_VISIBLE_MIN
                    + "-" + ORDINARY_VISIBLE_MAX + " items (found "
                    + (metas == null ? 0 : metas.length()) + ")");
            for (int j = 0; j < metas.length(); j++) {
                JSONObject meta = metas.optJSONObject(j);
                check(meta != null,
                    "ordinary assembled item " + j + " in " + rowId + " is an object");
                String id = meta.optString("id", "");
                check(id.matches("tt[0-9]+"), "ordinary assembled IMDb id is valid: " + id);
                check(!excluded.contains(id),
                    "ordinary rows exclude watched/saved: " + id);
                check(!curatedIds.contains(id),
                    "ordinary rows do not repeat curated picks: " + id);
                check(ordinaryIds.add(id), "ordinary IMDb IDs are duplicate-free: " + id);
                assembledItems++;
            }
            Log.i(TAG, "ASSEMBLED_ORDINARY " + i + " " + rowId + " items="
                + metas.length());
        }
        for (int i = 0; i < assembledRows; i++) {
            JSONObject row = assembled.optJSONObject(i);
            if (row.optBoolean("curated")) {
                Log.i(TAG, "ASSEMBLED_CURATED " + i + " " + row.optString("id")
                    + " items=" + row.optJSONArray("metas").length());
            }
        }
        Log.i(TAG, "ASSEMBLED ordinaryRows=" + assembledOrdinaryRows
            + " curatedRows=" + assembledCuratedRows
            + " curatedWatched=" + curatedWatchedItems
            + " totalItems=" + assembledItems);
    }

    private static JSONObject findRow(JSONArray rows, String id) {
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) return row;
        }
        return null;
    }

    private static String catalogId(String rowId) {
        return rowId.startsWith("jedflix-") ? rowId : "jedflix-" + rowId;
    }

    private static String trimmed(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value == null ? "" : value.trim();
    }

    private static String first(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if ("movie".equals(type)) return "movie";
        if ("series".equals(type) || "show".equals(type) || "tv".equals(type)) {
            return "series";
        }
        return null;
    }

    private String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    private Object call(Object target, String name) throws Exception {
        if (target == null) throw new AssertionError("Cannot call " + name + " on null");
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (InvocationTargetException failure) {
            throw propagate(name, failure);
        }
    }

    private Object callStatic(Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            throw propagate(method.getName(), failure);
        }
    }

    private Object construct(Constructor<?> constructor, Object... args) throws Exception {
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException failure) {
            throw propagate("constructor " + constructor.getDeclaringClass().getName(), failure);
        }
    }

    private static AssertionError propagate(String action, InvocationTargetException failure) {
        Throwable cause = unwrap(failure);
        return new AssertionError(action + " failed: " + describe(cause), cause);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown failure";
        String message = failure.getMessage();
        return failure.getClass().getName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
        Log.i(TAG, "PASS " + description);
    }
}
