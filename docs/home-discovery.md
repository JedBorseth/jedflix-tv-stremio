# Home discovery

Home discovery is a build-time layer that adds dynamic on-device Home rows to
the existing Stremio TV Home: a curated Jed's Picks shelf from a fixed public
HTTPS feed, plus discovery rows fetched directly from TMDB. It does not replace
the Home screen or add platform widgets. The app still renders every row with
the normal Stremio catalog components and keeps the small `J` logo and its patch
unchanged. While a discovery snapshot exists, it supplies the catalog row list
(Continue Watching, then Jed's Picks, then the TMDB rows) and passes Continue
Watching through unchanged; before the first snapshot, the original Stremio rows
render normally.

The layer is part of the managed-addons spike build
(`scripts/spike-build.py --lock-addons`), not a standalone APK. It is assembled
from Java helpers in `scripts/android/` and injected into the staged bytecode by
`scripts/discovery-patch.py`.

## Architecture

```text
StremioApplication.onCreate
  -> HomeDiscovery.init(context)              create the on-device clients
CoreStorage constructor
  -> DiscoverySignals.attach(sharedPreferences)   read-only local taste
BoardFragment.onViewCreated
  -> HomeDiscovery.attach(BoardViewModel)
BoardFragment.onViewCreated collector
  -> HomeDiscovery.onState(BoardState)         keep Continue Watching, set catalogs
BoardFragment.onResume / onPause
  -> HomeDiscovery.onResume / onPause          refresh and focus tracking

HomeDiscovery (single background executor)
  -> DiscoverySignals.snapshot()               cached seeds + exclusions
  -> JedPicksClient.load()                     fixed public HTTPS picks feed (5 min TTL)
  -> DiscoveryRules.rows(now, localTimeZone)   deterministic TMDB row plan
  -> TmdbCatalogClient.load(row)               HTTPS TMDB, filter, rank, cache
  -> DiscoveryFeed.assemble(rows, signals)     picks exception, dedupe, bounds
  -> TmdbCatalogMapper.catalogs(rows)          native Stremio model objects
  -> BoardViewModel.jedflixSetCatalogs(...)    publish into BoardState
```

| Helper | Responsibility |
| --- | --- |
| `DiscoveryRules` | Pure calendar/daypart/spotlight plan and deterministic candidate ranking for TMDB rows. |
| `DiscoverySignals` | Asynchronous, read-only snapshot of local library and playback evidence; profile hashing and watched/saved exclusion. |
| `TmdbCatalogClient` | Direct TMDB v3 HTTPS access, eligibility filtering, detail enrichment, ranking, and stale-tolerant caches. Also maps one curated TMDB id on demand. |
| `JedPicksClient` | Fixed public HTTPS feed of curated TMDB ids, five-minute in-memory cache, metadata resolution, and web-order assembly. |
| `TmdbCatalogMapper` | Reflection into upstream Stremio model constructors; IMDb-only items and native catalog rows. |
| `DiscoveryFeed` | Cross-row deduplication, picks exception, curated 80-item/ordinary 32-item caps, and append-only focus checks. |
| `HomeDiscovery` | Lifecycle, background refresh of both sources, profile-scoped disk cache, focus-preserving publication. |

The patch also makes `CatalogRowsViewModel` skip load and paging for rows whose
catalog id starts with `jedflix-`. Discovery rows are preloaded, so they do not
page in the reader.

Mapped cards are registered as local previews. During native full-metadata
enrichment, `HomeDiscovery.mergeFullPreview` calls
`TmdbCatalogMapper.preserveArtwork`: for a local preview it keeps the complete
TMDB artwork, name, synopsis, release/runtime, and links, while taking the
native `InLibrary`, `Watched`, and `InCinema` flags and appending fresh
IMDb-category links from the native result. Native library flags and real IMDb
data therefore update without losing full TMDB artwork. The collector also asks
`HomeDiscovery.rowDefaults(catalog)`, which returns 6 for a `jedflix-` catalog
and 14 for other rows; the source describes this as hiding the addon-only See
All card for local rows.

The integration depends on exact upstream bytecode seams. `discovery-patch.py`
asserts every hook and fails the build if the upstream method shape changes; it
does not silently produce a partial Home.

## Row order and sources

The published catalog list has three parts:

1. Continue Watching, passed through from the native state unchanged.
2. Jed's Picks, a fixed curated row loaded separately from its public feed.
3. The deterministic TMDB discovery plan from `DiscoveryRules`.

Jed's Picks is not part of `DiscoveryRules`. `HomeDiscovery` loads it separately
and prepends it to the published catalogs, so it appears directly below the
native Continue Watching row and above every TMDB discovery row, in both the
initial cached Home and later refreshes.

- Source: the fixed public HTTPS feed
  `https://canny-bat-352.convex.site/tv/jeds-picks`. No TMDB or Jedflix API key
  is sent. The host, path, HTTPS scheme, default port, and absence of
  credentials/query/fragment are validated.
- Contract: feed version 1 with a `picks` array of numeric `tmdbId` values and
  `type` `movie` or `series`. Web order is preserved; duplicate TMDB ids and
  duplicate IMDb results are dropped. At most 80 picks are accepted.
- Metadata: each pick is mapped through `TmdbCatalogClient.loadItem` with up to
  four parallel lookups. Curated lookups skip the default vote floors but still
  require a valid release date, artwork, IMDb id, and the normal runtime and
  eligibility filters. Invalid picks are omitted. A failed lookup reuses the
  previous metadata for that pick when available.
- Cache: a successful or stale picks result is held in memory for five minutes.
  A feed failure returns the previous cached result; a confirmed empty feed
  clears the shelf rather than resurrecting old picks. `HomeDiscovery` also
  persists the last assembled row in its profile Home file, so the row survives
  a cold start.
- Watched exception: the curated row is exempt from the local watched/saved
  exclusion and from cross-row `used` filtering, so a watched title can
  intentionally appear in Jed's Picks. Its IMDb ids are still added to the
  page-wide used set, so the same title is not repeated in a TMDB row.
- Shape: row id `home.jeds-picks`, title `Jed’s Picks`, visible type `movie`,
  `curated: true`, and each item keeps its own movie/series type. An empty
  curated shelf is omitted entirely; a non-empty shelf needs only one item.
- Independence: the picks feed does not alter `DiscoveryRules` or the TMDB
  queries. The TMDB rows remain the deterministic direct-TMDB plan and work
  without a picks feed; the picks row works without a TMDB refresh.

The TMDB plan is capped at 14 rows. Each TMDB row requests up to 48 titles; the
page-level feed later caps an ordinary displayed shelf at 32.

## TMDB row plan

`DiscoveryRules.rows` runs in the device's current timezone
(`TimeZone.getDefault()`), not a configured household timezone. The plan is a
pure function of the local date/time and the signal snapshot, so the same inputs
produce the same rows and queries.

Rows are added in this order; the keep value decides which rows are trimmed:

| Keep | Rows |
| --- | --- |
| 100 | Trending Now (always first). |
| 95 | Up to two active seasonal rows. |
| 90 | One time-of-day row. |
| 85 | Up to two personal seed rows. |
| 30-60 | Genre, world, documentary, and anime spotlights. |
| 55 | Fresh on Digital. |
| 0 | Hidden Gems and Prestige TV. |

When more than 14 rows are planned, the lowest keep value is removed first (in a
tie, the later entry goes). Hidden Gems and Prestige TV go first, then the
lowest-priority spotlights, then Fresh on Digital. This lets fixed rows vanish on
crowded seasonal/personal days while Trending, seasonal, time-of-day, and
personal rows survive.

All TMDB queries use `en-US` and `include_adult=false`. The row plan bounds
discover results with the device-local date; the client re-checks release and
first-air dates against its UTC date and drops titles dated after it.

### Trending

`home.trending.movies` always uses `/trending/movie/week`. Its TMDB page is
stable for the local date and rotates among pages 1-3 through a SHA-256 hash of
the row id plus the local date.

### Seasonal rows

Seasonal windows are device-local calendar windows. The definitions are
hard-coded, including Canadian Thanksgiving; there is no locale or region
setting.

| Row | Active window | Query shape |
| --- | --- | --- |
| Thanksgiving Together | Saturday before Canadian Thanksgiving through Thanksgiving Monday (second Monday of October). | Family genre, popularity, at least 80 votes. |
| Halloween Frights | October 1-31. | Horror genre, popularity, at least 150 votes. |
| Christmas Movies | November 20-December 25. | Christmas keyword, at least 120 votes. |
| New Year, Fresh Start | December 26-January 7. | Comedy/drama, popularity, at least 200 votes. |
| Valentine's Movie Night | February 7-14. | Romance genre, at least 150 votes. |
| Summer Adventures | June-August. | Adventure/family, at least 200 votes. |
| Cozy Winter Nights | December-February. | Drama/comedy, at least 200 votes. |

Only the first two active rows in that definition order are planned. Windows can
overlap: October 10-12 has Thanksgiving and Halloween; late December has New Year
and Winter; November 20-December 25 has Christmas and Winter.

### Time-of-day rows

Exactly one `home.moment.*` row is planned. Rules are evaluated in this order:

| Row | Window | Filter |
| --- | --- | --- |
| Late-Night Thrillers | 22:00-02:59 every day. | Thriller, mystery, or crime; at least 200 votes. |
| Friday Night Movie Night | Friday from 17:00, before 22:00. | Action, adventure, comedy, sci-fi, or fantasy; at least 300 votes. |
| Saturday Double Feature | Saturday from 12:00, before 22:00. | Action, adventure, comedy, thriller, or sci-fi; at least 300 votes. |
| Sunday Comfort Watches | Sunday from 16:00, before 22:00. | Comedy, family, or romance; excludes horror, thriller, crime, and war. |
| After a Long Day | Monday-Thursday from 17:00, before 22:00. | Same comfort filter as Sunday. |
| Weekend Binge | Saturday or Sunday 09:00-16:59. | Series; drama, comedy, or mystery; at least 100 votes. |
| Tonight's Pick | All other times. | Comedy, drama, thriller, or sci-fi; at least 500 votes and rating 7.0. |

The weekend row only covers the hours before the later weekend rules start
(Saturday until 11:59, Sunday until 15:59).

### Personal rows

When local taste exists, the first two seeds become:

- The most recent seed is titled `Because You Watched <seed>` when it has
  meaningful playback evidence, otherwise `More Like <seed>`.
- The second seed is always `More Like <seed>`.

A movie seed uses `/discover/movie`, a series seed uses `/discover/tv`, and the
client resolves the seed through TMDB `/find/{imdbId}` and loads that title's
recommendations. Row ids include the seed IMDb id, so changing the seed produces
a different cached row rather than relabeling old content.

### Spotlights and fixed rows

The spotlight definitions are added in this order: Family Movie Night, Comedy
That Lands, Crime Files, Sci-Fi Worlds, Mystery & Suspense, Fantasy Escapes, True
Stories, World Cinema, Anime Picks. A non-empty `preferredGenres` signal would
sort matching spotlights first; `DiscoverySignals` currently always emits an
empty list, so the order above is the effective order.

- Family Movie Night: movie, family genre, US certification at most PG.
- Comedy That Lands: movie, comedy, at least 250 votes.
- Crime Files: series, crime, at least 80 votes.
- Sci-Fi Worlds: series, sci-fi/fantasy genre ids, at least 100 votes.
- Mystery & Suspense: movie, mystery/thriller, at least 200 votes.
- Fantasy Escapes: movie, fantasy/adventure, at least 250 votes.
- True Stories: series, documentary, at least 30 votes.
- World Cinema: movie, one original language selected daily from Korean, French,
  Japanese, Spanish, Hindi, Italian, Danish, Swedish, Portuguese, German, and
  Chinese; at least 80 votes.
- Anime Picks: series, animation genre, original language Japanese.

Fixed rows:

- Fresh on Digital: movie, Canada region, release types 4 or 5 (digital or
  physical), released in the last 180 days, at least 20 votes, rating 5.5.
- Hidden Gems: movie, 150-1200 votes, rating at least 7.4, sorted by rating.
- Prestige TV: drama series, at least 250 votes, rating at least 7.5, sorted by
  rating.

### Daily stability and ranking

Row ids and query pages are stable for a local date. Page selection uses the
first 8 bytes of SHA-256 over `rowId|localDate` (and a separate daily hash for
World Cinema's language). The same date and signals produce byte-identical row
queries; a week can change pages, but it does not reshuffle within one request.

For a row, `DiscoveryRules.rankCandidates` scores every candidate as:

```text
quality     = vote_average * min(1, log1p(vote_count) / 7)
popularity  = log1p(max(popularity, 0)) / 10
freshness   = 0 for future dates, otherwise 1 / (1 + ageDays / 730)
score       = quality + 0.25 * popularity + 0.35 * freshness
```

Roughly three quarters of the requested row is the highest-scoring head; the
remaining quarter is a salted-hash exploration tail. Ties fall back to the
salted hash and then title id. This is a deterministic sampler, not a learned
recommender.

## Local taste

`DiscoverySignals` reads the existing Stremio SharedPreferences through
`CoreStorage`; it never writes or deletes viewing state. It reads the raw
`library_recent` and `library` JSON buckets and merges entries by their latest
`_mtime`, ignoring the older bucket when the two buckets belong to different
accounts.

Parsing happens off the main thread. `attach` registers a listener for
`library` and `library_recent`; a single executor rebuilds the snapshot and
notifies `HomeDiscovery` only when it changes. `snapshot()` returns the last
cached value, so Home never parses the library JSON inline. A rebuild in flight
keeps serving the previous snapshot.

Eligible evidence is limited to `tt` ids with type `movie` or `series`:

- Meaningful: `timesWatched > 0`, actual playback of at least 5 minutes
  (`timeWatched` or `overallTimeWatched`), or a resume position of at least 20
  minutes (`timeOffset`).
- Saved: not `removed` and not `temp`, which covers My List.
- Continuing: not `removed` and a non-zero resume position.

Meaningful or saved titles can become seeds. Meaningful, saved, and continuing
titles are excluded from TMDB discovery so Home does not recommend what is
already in progress or in My List. Unwatched removed/temporary entries stay
eligible. The curated Jed's Picks row is deliberately exempt from this exclusion.

Seeds are ordered by last-watched time and then `_mtime`; the snapshot prefers a
movie/series mix and retains at most three, while the row planner only uses the
first two. Each seed carries a `watched` flag for meaningful playback, which
distinguishes `Because You Watched` from a saved-only `More Like` row. The
profile is represented by a 12-byte SHA-256 prefix, never the account id, and
personal disk caches are keyed by that hash. A malformed or missing bucket
produces an empty snapshot, which degrades to the cold-start schedule rather
than blocking Home.

This is a local snapshot, not a history service. It cannot see another device's
progress, server-side events, or account state that is not already in the local
buckets, and it does not perform watched suppression for titles absent from the
snapshot.

## TMDB client and caches

`TmdbCatalogClient` calls TMDB v3 directly from the device over HTTPS. It never
routes discovery through a Jedflix server. Supported endpoints are the two
weekly trending paths, `/discover/movie`, `/discover/tv`, `/find/{imdbId}`,
`/movie/{id}/recommendations`, `/tv/{id}/recommendations`, and the matching
detail endpoints. Internal row fields beginning with `_` and any caller-supplied
`api_key`, session, page, language, or `append_to_response` parameters are
stripped before a request is built.

Eligibility is enforced twice, on the list and after detail enrichment:

- Adult/softcore titles, future releases, missing titles, and missing posters or
  backdrops are dropped.
- A title must resolve to an IMDb id; TMDB-only titles are not shown because the
  packaged Comet/Cinemeta stream path needs `tt` ids.
- Movie release checks require a valid public date. For releases within 120
  days, an already-reached US/CA digital, physical, or TV release admits the
  title; a theatrical release at least 30 days old also admits it. If TMDB has no
  recognized US/CA release rows, the title is admitted only once it is at least
  30 days old. Older releases pass.
- Series must have a reached first-air date. Optional season/episode/runtime
  caps exist in the client but the current row plan does not set them.
- Known runtimes shorter than 45 minutes for a movie, or 15 minutes per episode
  for a series, are dropped by default. A row can opt in with `_shorts` or
  `_allowShorts`; the current row plan does not.
- Genres, votes, ratings, language, and runtime filters from a row are applied
  after enrichment. The client never labels a TMDB score as an IMDb rating.

Mapped titles contain `id`, `type`, `name`, poster at `w500`, background at
`w1280`, an optional English PNG logo at `w500`, a description, release year,
and runtime. Internal `_tmdbId`, `_tmdbRating`, and `_genreIds` fields are kept
for filtering but are not rendered as IMDb claims. Genre and cast links are
search deep links.

`TmdbCatalogClient.loadItem(tmdbId, type)` maps a single curated id through the
same detail and eligibility path with default options. It skips global vote
floors because a curated list has already been editorially selected; it still
requires a released, illustrated, IMDb-resolved title.

Caching layers:

| Layer | Policy |
| --- | --- |
| TMDB catalog responses (`/trending`, `/discover`, recommendations) | 6-hour TTL. |
| TMDB details and `/find` | 7-day TTL. |
| TMDB memory cache | 192-entry access-order map. |
| Disk TMDB cache | `context.getCacheDir()/jedflix-tmdb-v1`, atomic temp-file writes, 2 MiB response and bounded wrapper limits; pruned every 32 writes to 800 files and 64 MiB. |
| Jed's Picks feed | Five-minute in-memory TTL; stale result on feed failure; confirmed empty clears the shelf. |
| Home rows | `filesDir/jedflix-discovery/home-<profileHash>.json`, written atomically; read only if under 3 MB. |

The TMDB disk key is a SHA-256 of the logical URL, which excludes the API key,
and the stored body does not contain the key. A cached response is used when
fresh; on a network error or non-200 response, a cached response may still be
served even after its TTL, because both catalog and detail reads allow stale
fallback. A 429 response sets a throttle window from `Retry-After`, clamped to
1-60 seconds. Detail enrichment uses four daemon threads and stops once the
requested row is filled, so it does not enrich a whole candidate page (at most
80 items by construction). List reads use at most four pages and a starting
page no higher than 5.

## Refresh and offline fallback

Refresh order is cache-first:

1. Read the profile's Home file.
2. Keep the cached Jed's Picks row, then only cached TMDB rows whose ids are
   still in the current plan. Expired seasonal windows and stale personal seeds
   therefore disappear instead of being resurrected.
3. Publish those usable rows immediately.
4. Refresh Jed's Picks when its five-minute poll is due or the row is missing.
   A feed failure keeps cached picks; with no cached picks the row is absent.
5. Refresh each planned TMDB row. A failed row falls back to its previously
   cached row from disk.
6. Publish the first fresh TMDB row and every third row after that; a
   picks-only refresh publishes immediately.
7. Write the merged set (picks plus any available TMDB rows) once it is
   non-empty. The hourly completion key is set only when every TMDB row
   refreshed successfully, so a missing row is retried on the next tick.

If the whole refresh fails, the previous Home stays on screen. If neither a
Home cache nor a usable network response exists, Home has no discovery rows and
the normal Stremio rows render. A cached catalog response can also outlive its
TTL during an outage, so an offline Home can show older content rather than
fail. There is no offline guarantee of any particular title or shelf.

The Home context key includes profile hash, local year/day/hour, timezone id,
and a hash of the signal snapshot. A completed key stops duplicate TMDB work for
the same hour; Jed's Picks uses its separate five-minute poll. A 60-second
foreground tick and resume/attach refresh trigger checks, with a 30-second floor
between TMDB attempts when a snapshot already exists. A missing Jed's Picks row
is also loaded during a discovery refresh, so a cold Home does not wait for the
next poll.

## Focus preservation

Discovery never replaces the native focus model. Publication happens on the main
thread, and an identical row payload is ignored.

Before replacing the current rows, `HomeDiscovery` checks whether focus is inside
a poster grid (a `HorizontalGridView` ancestor). If a poster has focus and the
new payload is not append-only, the new snapshot is held as pending. Pending
content is applied when poster focus is absent, or on resume when focus is
absent or the pending payload is append-only. This avoids moving the currently
focused poster while the user is browsing.

`DiscoveryFeed.isAppendOnly` accepts a new payload only when every existing row
keeps its position, id, and title, and every existing item keeps its position
and id. New rows may be appended beneath the focused content; replacing or
reordering a row while a poster is focused is deferred instead.

## Key setup

The TMDB v3 key is a build-time input for the spike build. `scripts/spike-build.py`
accepts either:

```bash
JEDFLIX_TMDB_API_KEY=<32-hex-v3-key> ./scripts/spike-build.py --lock-addons
```

or a gitignored file at `.local/tmdb-api-key` containing the same value. The
build validates a 32-hex key and generates a temporary
`com.jedflix.tv.JedflixTmdbConfig` class. The key is compiled into the APK; it is
not a runtime setting and there is no in-app key screen. The Jed's Picks feed is
public and keyless.

Treat the key as extractable from any installed APK and use a restricted,
revocable key. Do not commit it, paste it into logs, or put it in the Home cache;
the client's logical URL and cache key deliberately exclude it. `verify-discovery`
generates a harmless placeholder and never reads `.local/tmdb-api-key`.

The normal build prerequisite still applies: run `./scripts/build.sh` first so
the staged decode exists, then run the managed-addons spike build.

## Verification

```bash
./scripts/verify-discovery.py
```

The verifier:

- Uses `ANDROID_HOME` (or `ANDROID_SDK_ROOT`, or the default macOS SDK path),
  preferring `platforms/android-36/android.jar` and otherwise the newest
  installed platform.
- Uses `.local/tools/json-20240303.jar`; if it is missing, downloads the pinned
  Maven artifact and verifies SHA-256
  `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.
- Creates a temporary directory, generates a test-only `JedflixTmdbConfig`,
  compiles the seven discovery helpers (including `JedPicksClient`) against the
  Android API jar and real org.json, then compiles and runs every
  `scripts/tests/*Test.java` (five tests currently, including the Jed's Picks
  feed contract).
- Runs no device, TMDB API call, or APK build. Failures report the exact
  command and compiler/test output.

The host tests are behavioral: calendar boundaries, row contracts, ranking,
signals and exclusions, cross-row dedupe, focus append checks, mapping,
stale-cache fallback, and the Jed's Picks feed/cache/transport contract. They do
not read the real key or mirror private helper methods. The separate
`scripts/probe-discovery.py` builds an optional read-only on-device
instrumentation probe; this verifier does not build, install, or run it.

## Boundaries and no recommendation guarantees

- This is a deterministic, editorial sampler built from TMDB queries plus a
  curated web feed. It is not a learned recommender and will not reliably match
  taste, mood, or a specific household. Jed's Picks order and contents are
  controlled by its public feed, not by local signals.
- No row, title, genre, seasonal shelf, or "Because You Watched" claim is
  guaranteed. TMDB coverage, regional availability, vote counts, artwork, and
  release metadata vary; TMDB rows under four usable titles are dropped. An
  empty Jed's Picks shelf is dropped, and the feed can temporarily be stale or
  unavailable.
- Seasonal and daypart rules use one hard-coded calendar (including Canadian
  Thanksgiving) and the device timezone. There is no locale setting, holiday
  configuration, or parental/region override.
- Only IMDb-resolved titles are emitted. The layer does not verify that a title
  currently has a playable stream through the packaged addons, and it does not
  add new addons.
- Personalization is limited to the local snapshot and the first two seeds.
  Watched suppression only covers titles present in that snapshot; missing or
  malformed state falls back to the cold-start rows. Jed's Picks intentionally
  may include watched titles.
- TMDB requests are direct device calls; Jed's Picks is a direct call to the
  fixed public Convex feed. Cached rows and stale responses can be shown during
  outages, so content may be older than the current date or absent. No volume,
  latency, availability, freshness, or recommendation-quality guarantee is
  implied.
- The layer does not change the existing Home UI, its typography, spacing,
  colors, D-pad behavior, detail screens, player, Continue Watching, or the small
  `J` logo. No new default Android widgets or dialogs are introduced.
