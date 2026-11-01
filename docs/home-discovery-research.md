# Home discovery research: legacy seasonal shelves → Stremio catalogs

Historical research: the implemented design now calls TMDB directly on the TV,
independently of the legacy server. See [Home discovery](home-discovery.md).
Only the separately requested Jed's Picks shelf reads the web app's curated IDs.

Research date: 2026-10-31. This is recommendation research only; no app, addon,
device, or deployment changes were made. The parent thread inspected the live
TMDB Discover+ install; this note focuses on the legacy `JedBorseth/jedflix-tv`
scheduler and whether it is transferable.

## Bottom line

- **Preferred architecture:** reuse the legacy **27-theme engine through a
  Stremio catalog adapter**, then add the personal/watch-history bridge as a
  separate phase. Keep the current Home UI and addon-lock model.
- **TMDB Discover+ supplies configured catalogs, including rolling date filters
  and randomization.** It has no seasonal/daypart scheduler or observed
  watch-history input, and its manifest row names/order are fixed until
  installation or manifest refresh. Keep it, if desired, for its existing
  static browse rows; do not make it the source of seasonal rotation.
- **The legacy v0.8+ scheduler is the valuable part.** It is deterministic,
  tested, and small: 27 theme families, local-time windows, factual eligibility,
  daily seeded rotation, quality/release filters, deduplication, watched/library
  suppression, and cached output.
- **Expose stable catalog IDs whose contents change by date.** Do not depend on
  remote manifest row names changing. Treat watch-history ingestion as a separate
  phase because Stremio catalogs do not carry history.

## Deployed backend check

The parent performed a read-only `GET https://borseth.ddns.net/tv-api/health`
on 2026-10-31. It returned:

```json
{"status":"ok","version":"fab4d75c1f97171311e2c9f55cf987301aabcd78"}
```

No `POST /recommendations` or `POST /discover` request and no inference were
made. Deployed commit `fab4d75c1f97171311e2c9f55cf987301aabcd78` (2026-10-12)
has byte-identical `themes.py`, `engine.py`, `catalog.py`, `home_cache.py`,
`model.py`, and `service.py` to current `main` at
`938aeac8377f8f624129d089a9f26fc7bdda7ea5`. A `gh compare
fab4d75...938aeac` shows six commits ahead but **no server-file changes**; the
intervening commits are migration-related Android app/docs work. The deployed
build therefore already contains the 27-theme engine and the two-per-day Home
budget described below.
[deployed commit](https://github.com/JedBorseth/jedflix-tv/commit/fab4d75c1f97171311e2c9f55cf987301aabcd78) ·
[themes.py at deployed SHA](https://github.com/JedBorseth/jedflix-tv/blob/fab4d75c1f97171311e2c9f55cf987301aabcd78/server/recommender/themes.py)

## Exact legacy scheduler

Calendar eligibility is a pure function of an aware device-local datetime. The
module deliberately handles facts (holidays, runtime, credits, limited-series
status); the model only ranks supplied titles. The scheduler landed in
`7893ee6` and is unchanged on `main` except for its module docstring.
[`themes.py#L159-L232`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/themes.py#L159-L232) ·
[`themes.py#L243-L312`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/themes.py#L243-L312) ·
[introduction commit](https://github.com/JedBorseth/jedflix-tv/commit/7893ee6108cd48f016c62708207fe9215da3007a)

All windows are evaluated in the device timezone; a naive datetime is rejected.
Priority is 100 for Halloween/Christmas/New Year/Valentine’s/Thanksgiving, 92 for
Spooky, 90 for Friday/Sunday/Late-Night, and lower for other themes. Equal
priorities are ordered by a SHA-256 of `local-date + theme-id`, giving stable
daily variety rather than request-to-request shuffling.

| Theme | Activation | Factual eligibility |
| --- | --- | --- |
| Friday Night Movie Night | Friday from 17:00 | Movie; action, adventure, comedy, sci-fi, or fantasy |
| Saturday Double Feature | Saturday from 12:00 | Movie; any genre |
| Sunday Comfort Watches | Sunday from 16:00 | Movie; light genre; no horror/thriller; no stressful action/war/crime without a light genre |
| After a Long Day | Weekdays from 17:00 | Same comfort filter as Sunday |
| Done Before Bed | Daily from 17:00 | Movie; verified runtime 45–99 minutes |
| Late-Night Thrillers | 22:00–03:00 | Movie; thriller, mystery, or crime genre |
| Weekend Binge | Friday 17:00 through Sunday | Scripted/Miniseries; 1–12 episodes; known runtimes; total ≤720 minutes |
| Halloween Movie Night | October | Movie; horror genre or explicit Halloween/ghost/witch/vampire/monster/supernatural words |
| Spooky, Not Scary | October | Family movie; no horror; spooky words; excludes Christmas-only stories unless also Halloween |
| Christmas Movies | Nov 20–Dec 25 | Movie; explicit Christmas/Santa/nativity words |
| Cozy Winter Watches | Dec–Feb | Movie; no horror/thriller; winter/snow/Christmas words |
| New Year, Fresh Start | Dec 26–Jan 7 | Movie; eligibility is intentionally genre-neutral; the theme query does the rest |
| Valentine’s Movie Night | Feb 7–14 | Movie; romance genre |
| Summer Adventure | Jun–Aug | Movie; vacation/road-trip/travel/camping/journey words |
| Thanksgiving Together | Saturday through Canadian Thanksgiving Monday | Movie; Thanksgiving/family-reunion/homecoming/cooking/dinner words |
| Start a New Series | At most two recently active Shows | Scripted or Miniseries |
| Your Next Obsession | A completed Show | Scripted or Miniseries; completion is verified from final episode + watched evidence |
| One Season and Done | Weekend, or repeated drama interest | Ended one-season Miniseries |
| More From [Director] | ≥2 positive titles by same director | Movie directed by that person |
| Starring [Actor] | ≥2 positive titles with same cast member | Verified cast membership |
| Hidden Gems for You | ≥3 positive titles | Vote average ≥7, votes ≥100, popularity <20 |
| A Change of Pace | ≥3 recent watches, ≥75% in one genre | Genre differs from dominant genre |
| Back to the ’90s | ≥2 positive 1990s titles | Release year 1990–1999 |
| Twists and Turns | ≥2 positive mystery/thriller titles | Mystery/thriller/crime genre |
| Worlds to Get Lost In | ≥2 positive sci-fi/fantasy titles | Sci-fi/fantasy genre |
| Something to Make You Laugh | Comedy count ≥2 | Comedy genre |
| Based on a True Story | ≥2 positive verified true-story titles | True-story/biography keywords |

Canadian Thanksgiving is computed as the second Monday of October; the row is
active the preceding Saturday, Sunday, and Monday. The tests assert Oct 10–12
in 2026 are active while Oct 9 and Oct 13 are not.
[`test_themes.py#L50-L78`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/test_themes.py#L50-L78)

### Rotation and daily plan

- The server builds one plan per profile/local date and precomputes theme pools
  at local hours `0, 3, 12, 16, 17, 22`, covering all daypart transitions. A later
  visit at a different hour selects the cached rankings for that hour without
  another model call.
- At display time it re-runs `active_themes` for the current local time, keeps at
  most **5** dynamic shelves, requires **≥6** eligible items per shelf, and caps
  each shelf at 16 displayed items. Personal `For You` and up to two
  `Because you watched…` rows are returned separately.
- The model may select up to 24 per dynamic shelf and 40 for For You, but it may
  only return supplied keys; an empty shelf is allowed and padding is explicitly
  forbidden.
  [`engine.py#L349-L397`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py#L349-L397) ·
  [`engine.py#L415-L477`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py#L415-L477)

## Quality, dedup, and watched suppression

- **Candidate corpus:** the background TMDB index targets 3,000 titles (cap
  10,000). Movie recipes require `vote_count.gte=100` and
  `with_runtime.gte=45`; TV requires `vote_count.gte=50`; adult titles are
  dropped. Genre, era, short-movie, limited-series, and seasonal-keyword recipes
  broaden coverage. Seasonal keyword queries are scheduled first during a cold
  warmup.
  [`catalog.py#L234-L268`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/catalog.py#L234-L268)
- **Release eligibility:** Shows need an already-reached first-air date. Movies
  need a typed public release (types 2–6); a festival premiere alone fails.
  Future public dates fail closed. A movie is admitted by a reached digital/
  physical/TV release, a Canada-first (US fallback) rent/buy/stream offer, or a
  public release at least 30 days old. Unknown dates fail closed. A recent
  verified playback can admit an already-public movie earlier, but never a
  future release.
  [`engine.py#L33-L55`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py#L33-L55)
- **Shortlist:** genre overlap with recent seeds, then a quality prior
  `vote_average * min(1, log1p(vote_count)/7)`, then a date-salted hash. The
  shortlist reserves roughly one quarter of the slate for exploration outside
  the head. The model makes the final theme/taste order.
  [`engine.py#L296-L313`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py#L296-L313)
- **Dedup:** model output is validated against each shelf’s allowed key set and
  deduplicated within the shelf. Display then uses one `used` set across dynamic
  shelves, Because rows, and For You. On Android, `DiscoveryPolicy.apply` also
  deduplicates across the entire merged discovery page while leaving Continue
  Watching/My List/Watch History utility rows untouched.
  [`engine.py#L321-L347`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py#L321-L347) ·
  [`DiscoveryPolicy.kt#L23-L54`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/app/src/main/java/com/jedflix/tv/data/recommendations/DiscoveryPolicy.kt#L23-L54)
- **Watched/library suppression:** a history row counts only at ≥5 minutes of
  actual playback, or ≥20 minutes of resume position when actual time is absent.
  All such watched titles, all feedback (like and dislike), and all My List
  titles are excluded from `items` before the model sees them. The client
  re-applies current dislikes and authoritative ineligibility on every
  recomposition. Utility rows are preserved intentionally.
  [`engine.py#L188-L222`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py#L188-L222) ·
  [`DiscoveryPolicy.kt#L31-L42`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/app/src/main/java/com/jedflix/tv/data/recommendations/DiscoveryPolicy.kt#L31-L42)

## Caching and refresh behavior

- **TMDB catalog:** SQLite-backed; 24-hour freshness; 400 queued IDs; batch 8;
  one request every 0.3 s; honors `Retry-After` on 429; bounded FIFO eviction;
  no profile data in the public cache.
  [`catalog.py#L17-L28`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/catalog.py#L17-L28) ·
  [`catalog.py#L164-L189`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/catalog.py#L164-L189)
- **Home plan:** one plan per anonymous profile token; 7-day storage; at most
  128 tokens; maximum **2 Home generation attempts per Vancouver date** across
  all profiles; failed attempts are counted and a failed profile gets a 1-hour
  cooldown. `validUntil` is the next local hour boundary (capped at one hour).
  [`home_cache.py#L11-L66`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/home_cache.py#L11-L66) ·
  [`server/README.md#L122-L148`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/README.md#L122-L148)
- **TV client:** disk cache per profile; 6-hour TTL plus an hourly context key
  (`local date + hour + UTC offset`); cached taste shelves survive offline, but
  expired calendar/dynamic shelves are dropped rather than resurrected; retries
  are capped at one minute while the catalog is warming or the response is stale.
  [`RecommendationRepository.kt#L42-L80`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/app/src/main/java/com/jedflix/tv/data/recommendations/RecommendationRepository.kt#L42-L80)

## Sources and dependencies

- **TMDB API v3** for public metadata: trending, discover, details, keywords,
  credits, watch providers, and release dates. `TMDB_API_KEY` stays server-side.
- **OpenRouter** model `nvidia/nemotron-3-super-120b-a12b:free`, constrained with
  a strict JSON schema. It must end in `:free`; paid fallback is rejected. The
  shared account has a daily free-model allowance and a 45-combined-call daily
  ceiling; 429s fail gracefully.
  [`model.py#L9-L28`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/model.py#L9-L28) ·
  [`server/README.md#L150-L163`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/README.md#L150-L163)
- **Runtime:** Python standard library + SQLite recommender, 2 CPUs, 512 MiB, no
  GPU or local model; Go API proxy; Caddy; public base
  `https://borseth.ddns.net/tv-api/`.
  [`server/docker-compose.yml`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/docker-compose.yml) ·
  [`server/README.md#L1-L16`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/README.md#L1-L16)
- **Android coupling:** the client only accepts dynamic theme IDs in its static
  `DynamicShelves` allowlist. Adding a new server-only theme silently drops the
  row until the client list changes.
  [`DynamicShelves.kt#L5-L18`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/app/src/main/java/com/jedflix/tv/data/recommendations/DynamicShelves.kt#L5-L18)

## Merits and weaknesses of the legacy implementation

**Merits**

- Calendar and holidays are deterministic and separate from model mood; snow
  cannot admit a Christmas shelf by itself, and Christmas-only stories are kept
  out of the October family row.
- Exact quality gates (runtime, votes, release dates, limited-series facts) and
  one whole-page dedup set avoid the worst “same popular titles everywhere”
  behavior.
- One daily plan covers all dayparts; no model work on focus or scroll; cached
  output displays immediately.
- Strong test coverage for every activation family, date boundaries, Canadian
  Thanksgiving, runtime/limited-series claims, empty profiles, watched/disliked/
  release filtering, one-generation-per-day, and failed-refresh removal of
  expired Halloween rows.
  [`test_themes.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/test_themes.py) ·
  [`test_dynamic_engine.py#L104-L186`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/test_dynamic_engine.py#L104-L186)

**Weaknesses / risks**

- The scheduler is hard-coded to one locale’s holidays (Canadian Thanksgiving,
  Christmas, Valentine’s) and has no region/locale configuration.
- Theme admission is keyword/title/overview based. It is factual but can still
  admit incidental mentions or miss synonyms; `New Year` and
  `Saturday Double Feature` rely mostly on the model query for genre fit.
- `one-season-done` requires TMDB type exactly `Miniseries`; some limited series
  are typed `Scripted`. `weekend-binge` needs `episode_run_time`, which is often
  missing. Both can underfill.
- The LLM adds quota, latency, non-determinism, and an external dependency. The
  daily budget is only two generations across all profiles, so a failed day can
  leave some profiles without a fresh plan.
- The server and client both maintain theme-ID allowlists; adding a theme is a
  two-component change.
- The model's “no padding” instruction is enforced only by prompt plus minimum-
  six display checks; a shelf can disappear rather than degrade gracefully.
- A 3-hour downstream catalog cache (as observed on TMDB Discover+) would delay
  a local-time transition; the legacy design avoids this with hourly client
  refresh, but a Stremio addon must choose its own cache policy.

## Transfer to a Stremio catalog addon

The Stremio addon protocol supports `catalog` and `meta` resources, plus
`player` and `library` **event** resources. Catalog requests carry `type`, `id`,
and `extra` (`search`, `genre`, `skip`, `date`) plus configured `config`; they do
not carry a timezone, watch history, library, or profile identity.
[protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md) ·
[catalog handler](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineCatalogHandler.md) ·
[manifest](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md)

Consequences:

1. **Calendar logic transfers, but timezone must be supplied explicitly.** The
   legacy code requires an aware local datetime. A managed addon should use a
   configured timezone (for example `America/Vancouver`) in its install/config
   URL, or a household default; it cannot infer the TV’s timezone from a catalog
   request.
2. **Row titles and manifest order are static.** `catalogs[].name` is manifest
   data. A stable slot such as “Seasonal Picks” can change contents by date
   without an app release. Making the row itself say “Halloween Movie Night”
   requires a manifest refresh/rebuild; parent inspection indicates remote
   manifest changes do not automatically reach the installed client. Stable
   named slots with dynamic content are the low-risk design.
3. **Watch suppression needs a verified history bridge.** A catalog request
   cannot read the existing library/history. The SDK documents `library` and
   `player` events, but support and delivery in the pinned Android TV 1.10.4
   client must be verified before relying on them. Otherwise, bridge the app's
   loaded library/playback state explicitly. A per-install token can key that
   state. This is phase-two work; the first seasonal rows should not promise
   watched suppression from day one.
4. **IDs must be playable.** Current Comet advertises `tt` and `kitsu` streams.
   A catalog that emits `tmdb:` IDs can display posters but cannot resolve a
   Comet stream. Filter seasonal output to IMDb-compatible IDs (TMDB
   `external_ids.imdb_id`), and drop titles without one; use kitsu only for the
   anime path. Stremio’s manifest docs note catalog `idPrefixes` are not used for
   catalog filtering, so ID compatibility is the addon’s responsibility.
5. **Cache lifetime must respect calendar boundaries.** The observed TMDB
   Discover+ response used `max-age`/`cacheMaxAge=10800` (3 hours). For seasonal
   rows, use a short cache (about one hour) or align it to the next local
   eligibility boundary; otherwise a Halloween row can survive into November or
   appear late.
6. **The managed app cannot install it at runtime.** The build uses exactly
   Cinemeta and Comet from packaged snapshots; install, upgrade, sync, and addon
   mutation are blocked. Adding any new addon is an owner build/manifest change,
   not a user setting.
   [`managed-addons.md#L3-L14`](https://github.com/JedBorseth/jedflix-tv-stremio/blob/main/docs/managed-addons.md#L3-L14) ·
   [`managed-addons.md#L32-L41`](https://github.com/JedBorseth/jedflix-tv-stremio/blob/main/docs/managed-addons.md#L32-L41)

## TMDB Discover+ assessment

Parent inspection of the supplied TMDB Discover+ manifest/source found:

- Six browse rows (Trending, Top TV Shows, Top Movies, Disney+, Harry Potter,
  Anime) plus two required-search catalogs. The private install URL/config ID is
  intentionally not reproduced here.
- Public settings filters are not exposed by the manifest.
- Sample catalog responses used `max-age`/`cacheMaxAge=10800`; `no-store` was not
  observed.
- The source supports randomization and rolling date presets, but **no seasonal
  calendar scheduler** was found.
- A Top Movies sample returned Spanish names while TMDB rows were English.
- Trending mixed `tt` and `tmdb:` IDs; Comet only advertises `tt`/`kitsu`, so
  IMDb-compatible IDs are required. Kitsu anime samples were compatible.
- The managed app packages manifest snapshots and blocks install/upgrade/sync;
  remote row name/order changes will not automatically reach the client. Stable
  named catalog slots with dynamic content are easiest; richer seasonal row
  titles need an explicit trusted manifest-refresh policy.

**Verdict:** use TMDB Discover+ for **configured browsing catalogs**, with its
rolling date filters and randomization where useful. It is a good catalog
builder and a useful
reference for filters, randomization, and date presets. It is not a drop-in
replacement for the legacy 27-theme scheduler, and its current source lacks the
watch-event resources needed for automatic watched suppression. Keep its
existing browse rows if useful; put seasonal rotation in the legacy-engine
adapter and add the personal/history bridge separately.
[public project](https://github.com/semi-column/tmdb-discover-plus)

## Reuse strategy

### Recommended: small Stremio catalog adapter in front of the existing backend

Keep the current Home UI and addon-lock model. Add a thin HTTP facade that
implements `/manifest.json` and `/catalog/{type}/{id}.json` and calls the
existing deterministic code. Do not reimplement the date logic first.

- Reuse `themes.py` unchanged for `active_themes` and `eligible_for_theme`.
- Reuse `catalog.py` / the existing TMDB snapshot for candidate metadata, the
  24-hour cache, 429 handling, and the bounded corpus. Reuse `release_policy`
  from `engine.py`.
- Extract a small deterministic `seasonal_shelves(now, timezone)` path from the
  current `Engine`: filter snapshot → theme eligibility → shortlist → dedup/cap.
  Do **not** route seasonal rows through the daily LLM plan; the model adds cost
  and quota without improving the fixed calendar rules. Keep the LLM path for
  For You/personalization if desired.
- Expose stable catalog IDs, e.g. one movie `seasonal` catalog and one series
  `seasonal` catalog, with names such as “Seasonal Picks.” Optionally add a
  small fixed set of named holiday slots only if an owner manifest-refresh policy
  is acceptable. Avoid changing manifest names dynamically.
- Configure the timezone in the install/config URL or owner manifest. Return
  `tt` IDs only; drop unresolved TMDB titles. Set `cacheMaxAge` to roughly one
  hour and re-check the local eligibility boundary on each request.
- Keep the existing Home layout: Trending stays first and owns the Billboard;
  seasonal/personal rows remain after it, as the legacy client already does.
  [`HomeShelfLayout.kt#L56-L65`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/app/src/main/java/com/jedflix/tv/data/tmdb/HomeShelfLayout.kt#L56-L65)

This is the lowest-risk path because the deployed backend is already healthy and
its scheduler/cache files match current `main`. The only new surface is the
Stremio protocol facade, ID mapping, and a timezone/config convention. TMDB
Discover+ can be integrated as a catalog source for the configured rows;
it should not own seasonal rotation. Keep the existing Home presentation,
typography, spacing, colors, and D-pad focus behavior; no new default Android
widgets or dialogs are implied by this research.

### Alternative: extend a TMDB Discover+ fork

Add a “seasonal” source/preset that ports the 15 calendar rows and their factual
filters, plus IMDb-ID filtering. This reuses an existing addon, TMDB client,
catalog builder, and config UI, and avoids a second service. It also inherits
third-party maintenance, a larger surface, per-user TMDB-key configuration, a
3-hour cache default, and no `library`/`player` resources. Suitable only if the
goal is to keep TMDB Discover+ as the primary catalog tool.

### Not recommended now: native Home replacement

Injecting native seasonal rows into the Stremio core/Compose Home would duplicate
the addon-lock work, couple seasonal content to app releases, and risk the
existing focus/scroll behavior. The current Home UI and addon snapshots are
working; a catalog adapter preserves both.

## Suggested phased implementation

1. **Deterministic seasonal addon:** stable catalog slots, configured timezone,
   IMDb-only output, one-hour cache, reusing the legacy scheduler and TMDB cache.
   No LLM, no watch state.
2. **ID and cache hardening:** verify every emitted ID resolves through Comet;
   add fallback/omission counts; align cache expiry to local date windows.
3. **Watch-history bridge:** verify pinned-client `library`/`player` event
   delivery or bridge app state explicitly. Use a per-install token, map
   IMDb→TMDB, and suppress watched/library titles.
   Existing Stremio history cannot be read through the catalog protocol; state
   that limitation or provide a separate import/feedback path.
4. **Optional personalization:** retain the legacy LLM For You/Because rows only
   after the deterministic seasonal rows are stable; keep the two-per-day budget
   and free-model guard.

## Primary sources

- Legacy scheduler: [`themes.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/themes.py) ·
  [`engine.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/engine.py) ·
  [`catalog.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/catalog.py) ·
  [`home_cache.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/home_cache.py)
- Legacy design/validation: [`docs/dynamic-home.md`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/docs/dynamic-home.md) ·
  [`docs/dynamic-home-validation.md`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/docs/dynamic-home-validation.md) ·
  [`server/README.md`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/README.md)
- Legacy tests: [`test_themes.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/test_themes.py) ·
  [`test_dynamic_engine.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/test_dynamic_engine.py) ·
  [`test_catalog.py`](https://github.com/JedBorseth/jedflix-tv/blob/1d818d50fe0419101315f4f4ee009622268a0f57/server/recommender/test_catalog.py)
- Stremio protocol: [protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md) ·
  [manifest](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md) ·
  [catalog handler](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineCatalogHandler.md) ·
  [player events](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/definePlayerHandler.md) ·
  [library events](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineLibraryHandler.md) ·
  [user data/config](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/advanced.md)
- TMDB Discover+: [public project](https://github.com/semi-column/tmdb-discover-plus)
- Current app addon policy: [`docs/managed-addons.md`](https://github.com/JedBorseth/jedflix-tv-stremio/blob/main/docs/managed-addons.md)
- Prior `JedBorseth/jedflix`: no movie seasonal scheduler was found. Its Home
  rows are static provider/genre rows
  ([`packages/tmdb/src/client.ts#L275-L319`](https://github.com/JedBorseth/jedflix/blob/cddd5298cdd0d1c1854ea3ea8be718817751edcc/packages/tmdb/src/client.ts#L275-L319));
  its only daily rotation is an unrelated OpenLibrary book-series selector
  ([`apps/backend/internal/openlibrary/series.go#L8-L14`](https://github.com/JedBorseth/jedflix/blob/cddd5298cdd0d1c1854ea3ea8be718817751edcc/apps/backend/internal/openlibrary/series.go#L8-L14)).
  Legacy `jedflix-tv` references that repository only for shared hosting/music-AI
  context, not for Home discovery.
