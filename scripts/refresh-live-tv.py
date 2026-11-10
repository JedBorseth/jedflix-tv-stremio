#!/usr/bin/env python3
"""Explicitly refresh the Live TV channel manifest; never runs during a build.

The manifest is the broadcast contract. Changing its seed, epoch, durations or
program list changes the schedule; ship the same revision to every client.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import unicodedata
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LINEUP = ROOT / "assets/live-tv-lineup.json"
DEFAULT_MANIFEST = ROOT / "assets/live-tv.json"
CACHE_DIR = ROOT / ".local/live-tv-metadata"
MAX_WORKERS = 4
TMDB_API = "https://api.themoviedb.org/3"
TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w780"
TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"


def load_tmdb_key():
    key = os.environ.get("JEDFLIX_TMDB_API_KEY", "").strip()
    credential = ROOT / ".local/tmdb-api-key"
    if not key and credential.exists():
        key = credential.read_text(encoding="utf-8").strip()
    if not key:
        raise SystemExit("Provide JEDFLIX_TMDB_API_KEY or .local/tmdb-api-key")
    return key


def fetch_cached_json(url, cache_key, refresh_cache=False):
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file = CACHE_DIR / f"{cache_key}.json"
    if cache_file.exists() and not refresh_cache:
        try:
            return json.loads(cache_file.read_text(encoding="utf-8"))
        except Exception:
            pass
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "JedFlix-LiveTV-Refresher/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = json.load(response)
    except Exception:
        # Never print credential URLs or raw HTTP exceptions.
        raise SystemExit(f"Channel metadata request failed for {cache_key}") from None

    fd, tmp_name = tempfile.mkstemp(prefix=f"{cache_key}.", suffix=".tmp", dir=str(CACHE_DIR))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)
        os.replace(tmp_name, cache_file)
    except Exception:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
        raise
    return data


def fetch_optional_json(url, cache_key, refresh_cache=False):
    """Like fetch_cached_json but treats a failed request as a miss.

    Discovery pages and per-title detail lookups are allowed to fail without
    aborting the whole refresh; the affected title is simply skipped.
    """
    try:
        return fetch_cached_json(url, cache_key, refresh_cache=refresh_cache)
    except (SystemExit, Exception):
        return None


def tmdb_url(path, key, **params):
    query = urllib.parse.urlencode({"api_key": key, "language": "en-US", **params})
    return f"{TMDB_API}{path}?{query}"


def discover_movies(discover_cfg, key, refresh_cache=False):
    """Resolve a TMDB discover/director block into movie detail records."""
    kind = discover_cfg.get("kind", "")
    limit = int(discover_cfg.get("limit", 30))
    candidates = []

    if kind == "director":
        person_id = int(discover_cfg["personId"])
        data = fetch_optional_json(
            tmdb_url(f"/person/{person_id}/movie_credits", key),
            f"tmdb_person_{person_id}_credits",
            refresh_cache=refresh_cache,
        )
        if data:
            seen = set()
            for credit in data.get("crew") or []:
                if credit.get("job") != "Director":
                    continue
                if int(credit.get("vote_count") or 0) < int(discover_cfg.get("minVoteCount", 0)):
                    continue
                tmdb_id = credit.get("id")
                if not tmdb_id or tmdb_id in seen:
                    continue
                seen.add(tmdb_id)
                candidates.append(credit)
            candidates.sort(key=lambda c: c.get("release_date") or "9999")
    elif kind in ("company", "genre"):
        params = {
            "include_adult": "false",
            "sort_by": discover_cfg.get("sortBy", "popularity.desc"),
        }
        if kind == "company":
            params["with_companies"] = str(int(discover_cfg["companyId"]))
            if discover_cfg.get("releaseFrom"):
                params["primary_release_date.gte"] = discover_cfg["releaseFrom"]
            if discover_cfg.get("releaseTo"):
                params["primary_release_date.lte"] = discover_cfg["releaseTo"]
        else:
            genres = [str(int(g)) for g in discover_cfg.get("genreIds") or []]
            if genres:
                params["with_genres"] = ",".join(genres)
            if discover_cfg.get("minRating"):
                params["vote_average.gte"] = str(discover_cfg["minRating"])
        excluded_genres = [str(int(g)) for g in discover_cfg.get("withoutGenreIds") or []]
        if excluded_genres:
            params["without_genres"] = ",".join(excluded_genres)
        if discover_cfg.get("minVoteCount"):
            params["vote_count.gte"] = str(int(discover_cfg["minVoteCount"]))

        token = hashlib.sha1(
            json.dumps(params, sort_keys=True).encode("utf-8")
        ).hexdigest()[:12]
        need = max(limit * 2, limit + 10)
        page = 1
        while len(candidates) < need and page <= 6:
            data = fetch_optional_json(
                tmdb_url("/discover/movie", key, page=str(page), **params),
                f"tmdb_discover_{token}_p{page}",
                refresh_cache=refresh_cache,
            )
            if not data:
                break
            results = data.get("results") or []
            if not results:
                break
            candidates.extend(results)
            if page >= int(data.get("total_pages") or 1):
                break
            page += 1
    else:
        raise SystemExit(f"Unsupported discover kind '{kind}'")

    return candidates


def build_discover_channel(discover_cfg, key, today_iso, refresh_cache=False):
    kind = discover_cfg.get("kind", "")
    if kind == "director":
        # A person's full directing credits are small enough to take whole.
        limit = int(discover_cfg.get("limit", 40))
    else:
        limit = int(discover_cfg.get("limit", 30))
    min_runtime = int(discover_cfg.get("minRuntimeMinutes", 60))
    excluded_titles = {
        str(title).strip().lower() for title in discover_cfg.get("excludeTitles") or []
    }

    candidates = discover_movies(discover_cfg, key, refresh_cache=refresh_cache)
    programs = []
    omissions = []
    seen = set()

    def build_one(candidate):
        tmdb_id = candidate.get("id")
        if not tmdb_id:
            return None, None
        detail = fetch_optional_json(
            tmdb_url(f"/movie/{int(tmdb_id)}", key),
            f"tmdb_movie_{int(tmdb_id)}",
            refresh_cache=refresh_cache,
        )
        if not detail:
            return None, {"title": candidate.get("title", ""), "reason": "missing_detail"}
        return movie_program_from_detail(detail, today_iso, min_runtime)

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        results = list(executor.map(build_one, candidates))

    for program, omission in results:
        if omission:
            omissions.append(omission)
            continue
        if program["title"].strip().lower() in excluded_titles:
            continue
        if program["videoId"] in seen:
            continue
        seen.add(program["videoId"])
        programs.append(program)
        if len(programs) >= limit:
            break

    return programs, omissions


def movie_program_from_detail(detail, today_iso, min_runtime_minutes=60):
    imdb_id = (detail.get("imdb_id") or "").strip()
    if not re.match(r"^tt\d+$", imdb_id):
        return None, {
            "title": detail.get("title", ""),
            "reason": "missing_imdb_id",
        }
    release_date = detail.get("release_date") or ""
    if not is_released_date(release_date, today_iso):
        return None, {"title": detail.get("title", ""), "videoId": imdb_id,
                      "reason": "unreleased_movie"}
    runtime = detail.get("runtime")
    if not runtime or int(runtime) < int(min_runtime_minutes):
        return None, {"title": detail.get("title", ""), "videoId": imdb_id,
                      "reason": "missing_or_short_runtime"}
    artwork_path = detail.get("backdrop_path") or detail.get("poster_path")
    if not artwork_path:
        return None, {"title": detail.get("title", ""), "videoId": imdb_id,
                      "reason": "missing_artwork"}

    year = parse_year(release_date)
    program = {
        "videoId": imdb_id,
        "title": (detail.get("title") or "").strip(),
        "season": 0,
        "episode": 0,
        "durationMs": int(runtime) * 60_000,
        "thumbnail": TMDB_IMAGE_BASE + artwork_path,
        "description": (detail.get("overview") or "").strip(),
    }
    if year > 0:
        program["year"] = year
    return program, None


def channel_poster_url(imdb_id, key, refresh_cache=False):
    """Channel tile artwork: the poster of the channel's flagship title."""
    imdb_id = (imdb_id or "").strip()
    if not re.match(r"^tt\d+$", imdb_id):
        return ""
    query = urllib.parse.urlencode({"api_key": key, "external_source": "imdb_id"})
    data = fetch_optional_json(
        f"{TMDB_API}/find/{imdb_id}?{query}",
        f"tmdb_find_{imdb_id}",
        refresh_cache=refresh_cache,
    )
    if not data:
        return ""
    for section in ("movie_results", "tv_results"):
        for item in data.get(section) or []:
            if item.get("poster_path"):
                return TMDB_POSTER_BASE + item["poster_path"]
    return ""


def strip_accents(text):
    return "".join(
        c for c in unicodedata.normalize("NFD", text or "")
        if unicodedata.category(c) != "Mn"
    )


def normalize_title(title, strip_part=False):
    text = strip_accents(title).lower()
    text = text.replace("&", " and ")
    text = re.sub(r"\brumour\b", "rumor", text)
    text = re.sub(r"(\w)s'(\s|$)", r"\1s's\2", text)
    text = re.sub(r",\s*(the|an|a)$", "", text)
    if strip_part:
        text = re.sub(r"\s*\((?:part\s+)?(?:[12]|i{1,2})\)$", "", text)
        text = re.sub(r"\s*-\s*part\s+(?:[12]|i{1,2})$", "", text)
        text = re.sub(r"\s+part\s+(?:[12]|i{1,2})$", "", text)
    else:
        text = re.sub(r"\((?:part\s+)?(1|i)\)$", "(1)", text)
        text = re.sub(r"\((?:part\s+)?(2|ii)\)$", "(2)", text)
    text = re.sub(r"\b(the|an|a)\b", "", text)
    text = text.replace("'", "")
    return "".join(c for c in text if c.isalnum())


def title_variants(title, strip_part=False):
    variants = {normalize_title(title, strip_part=strip_part)}
    aka_match = re.match(
        r"^(.*?)\s*\(a\.?k\.?a\.?\s+(.*?)\)\s*(\([12]\))?$",
        title or "",
        flags=re.IGNORECASE,
    )
    if aka_match:
        suffix = f" {aka_match.group(3)}" if aka_match.group(3) else ""
        variants.add(normalize_title(aka_match.group(1) + suffix, strip_part=strip_part))
        variants.add(normalize_title(aka_match.group(2) + suffix, strip_part=strip_part))
    variants.discard("")
    return variants


def parse_year(date_text):
    if not date_text or not isinstance(date_text, str):
        return 0
    match = re.match(r"^(\d{4})", date_text.strip())
    if not match:
        return 0
    year = int(match.group(1))
    return year if 1900 <= year <= 2100 else 0


def is_released_date(date_text, today_iso):
    if not date_text or not isinstance(date_text, str):
        return True
    prefix = date_text.strip()[:10]
    if re.match(r"^\d{4}-\d{2}-\d{2}$", prefix):
        return prefix <= today_iso
    return True


def resolve_tmdb_find(imdb_id, key, refresh_cache=False):
    query = urllib.parse.urlencode({
        "api_key": key,
        "external_source": "imdb_id",
        "language": "en-US",
    })
    url = f"https://api.themoviedb.org/3/find/{imdb_id}?{query}"
    return fetch_cached_json(url, f"tmdb_find_{imdb_id}", refresh_cache=refresh_cache)


def build_series_show_programs(
    show_cfg,
    key,
    existing_channels_by_id,
    today_iso,
    refresh_cache=False,
):
    series_title = show_cfg["seriesTitle"].strip()
    imdb_id = show_cfg["imdbId"].strip()
    reuse_channel_id = show_cfg.get("reuseChannelId")

    if reuse_channel_id:
        source_channel = existing_channels_by_id.get(reuse_channel_id)
        if not source_channel or not source_channel.get("programs"):
            raise SystemExit(
                f"Cannot reuse channel '{reuse_channel_id}' for {series_title}: not found in existing manifest"
            )
        reused_programs = []
        for prog in source_channel["programs"]:
            copied = dict(prog)
            copied["seriesTitle"] = series_title
            reused_programs.append(copied)
        return reused_programs, []

    find_data = resolve_tmdb_find(imdb_id, key, refresh_cache=refresh_cache)
    tv_results = find_data.get("tv_results") or []
    if not tv_results:
        raise SystemExit(f"TMDB find returned no TV series for {series_title} ({imdb_id})")
    tmdb_show = tv_results[0]
    tmdb_id = tmdb_show["id"]
    series_year = parse_year(tmdb_show.get("first_air_date", ""))

    cinemeta_url = f"https://v3-cinemeta.strem.io/meta/series/{imdb_id}.json"
    cinemeta_data = fetch_cached_json(
        cinemeta_url, f"cinemeta_series_{imdb_id}", refresh_cache=refresh_cache
    )
    meta = cinemeta_data.get("meta") or {}
    all_videos = meta.get("videos") or []

    configured_seasons = show_cfg.get("seasons")
    if configured_seasons:
        allowed_seasons = [int(s) for s in configured_seasons if int(s) > 0]
    else:
        allowed_seasons = sorted({
            int(v.get("season", 0))
            for v in all_videos
            if int(v.get("season", 0)) > 0
        })

    def fetch_season(season_num):
        query = urllib.parse.urlencode({"api_key": key, "language": "en-US"})
        url = f"https://api.themoviedb.org/3/tv/{tmdb_id}/season/{season_num}?{query}"
        return season_num, fetch_cached_json(
            url, f"tmdb_tv_{tmdb_id}_season_{season_num}", refresh_cache=refresh_cache
        )

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        season_details = dict(executor.map(fetch_season, allowed_seasons))

    strict_title_match = bool(show_cfg.get("strictTitleMatch", False))
    max_episodes = show_cfg.get("maxEpisodes")
    programs = []
    omissions = []

    for season in allowed_seasons:
        detail = season_details.get(season) or {}
        tmdb_episodes = detail.get("episodes") or []
        season_videos = [
            v for v in all_videos
            if int(v.get("season", 0)) == season
            and int(v.get("number") or v.get("episode") or 0) > 0
        ]

        for video in season_videos:
            if max_episodes is not None and len(programs) >= int(max_episodes):
                break
            ep_num = int(video.get("number") or video.get("episode") or 0)
            video_id = (video.get("id") or f"{imdb_id}:{season}:{ep_num}").strip()
            raw_title = (video.get("name") or video.get("title") or "").strip()
            released_str = video.get("released") or video.get("firstAired") or ""
            if not is_released_date(released_str, today_iso):
                omissions.append({
                    "seriesTitle": series_title,
                    "videoId": video_id,
                    "title": raw_title,
                    "reason": "unreleased_in_cinemeta",
                })
                continue

            v_exact = title_variants(raw_title, strip_part=False)
            v_norm = title_variants(raw_title, strip_part=True)

            exact_matches = [
                e for e in tmdb_episodes
                if title_variants(e.get("name", ""), strip_part=False) & v_exact
            ] if v_exact else []

            matches = exact_matches
            used_combined_split = False

            if not matches and v_norm:
                norm_matches = [
                    e for e in tmdb_episodes
                    if title_variants(e.get("name", ""), strip_part=True) & v_norm
                ]
                if len(norm_matches) == 1:
                    matches = norm_matches
                    used_combined_split = True

            if len(matches) != 1 and not strict_title_match:
                by_number = [
                    e for e in tmdb_episodes
                    if int(e.get("episode_number", 0)) == ep_num
                ]
                if len(by_number) == 1:
                    cand = by_number[0]
                    cand_norm = title_variants(cand.get("name", ""), strip_part=True)
                    claimed_by_other = any(
                        other is not video
                        and (
                            title_variants(
                                (other.get("name") or other.get("title") or ""),
                                strip_part=True,
                            ) & cand_norm
                        )
                        for other in season_videos
                    )
                    first_split_num = min(
                        (
                            int(other.get("number") or other.get("episode") or 0)
                            for other in season_videos
                            if re.search(r"\((?:part\s+)?(?:[12]|i{1,2})\)$", (other.get("name") or other.get("title") or "").strip(), flags=re.IGNORECASE)
                        ),
                        default=None,
                    )
                    unshifted_before_split = (
                        len(season_videos) == len(tmdb_episodes)
                        or (first_split_num is not None and ep_num < first_split_num)
                    )
                    if not claimed_by_other and unshifted_before_split:
                        matches = by_number
                        used_combined_split = False

            if len(matches) != 1:
                omissions.append({
                    "seriesTitle": series_title,
                    "videoId": video_id,
                    "title": raw_title,
                    "reason": "no_unambiguous_tmdb_episode_match",
                })
                continue

            episode = matches[0]
            air_date = episode.get("air_date") or ""
            if not is_released_date(air_date, today_iso):
                omissions.append({
                    "seriesTitle": series_title,
                    "videoId": video_id,
                    "title": raw_title,
                    "reason": "unreleased_in_tmdb",
                })
                continue

            runtime = episode.get("runtime")
            still_path = episode.get("still_path")
            if not runtime or int(runtime) <= 0 or not still_path:
                omissions.append({
                    "seriesTitle": series_title,
                    "videoId": video_id,
                    "title": raw_title,
                    "reason": "missing_tmdb_runtime_or_still",
                })
                continue

            parts = 1
            if used_combined_split and v_norm:
                parts = max(
                    1,
                    sum(
                        bool(
                            title_variants(
                                (other.get("name") or other.get("title") or ""),
                                strip_part=True,
                            ) & v_norm
                        )
                        for other in season_videos
                    ),
                )

            duration_ms = int(runtime) * 60_000 // parts
            if duration_ms <= 0:
                omissions.append({
                    "seriesTitle": series_title,
                    "videoId": video_id,
                    "title": raw_title,
                    "reason": "non_positive_duration",
                })
                continue

            ep_title = raw_title or (episode.get("name") or "").strip()
            description = (
                video.get("description")
                or video.get("overview")
                or episode.get("overview")
                or ""
            ).strip()
            ep_year = (
                parse_year(air_date)
                or parse_year(released_str)
                or series_year
            )

            prog = {
                "videoId": video_id,
                "title": ep_title,
                "seriesTitle": series_title,
                "season": season,
                "episode": ep_num,
                "durationMs": duration_ms,
                "thumbnail": TMDB_IMAGE_BASE + still_path,
                "description": description,
            }
            if ep_year > 0:
                prog["year"] = ep_year
            programs.append(prog)

    return programs, omissions


def build_movie_program(movie_cfg, key, today_iso, refresh_cache=False):
    expected_title = movie_cfg["title"].strip()
    imdb_id = movie_cfg["imdbId"].strip()
    if not re.match(r"^tt\d+$", imdb_id):
        raise SystemExit(f"Invalid movie IMDb ID for {expected_title}: {imdb_id}")

    find_data = resolve_tmdb_find(imdb_id, key, refresh_cache=refresh_cache)
    movie_results = find_data.get("movie_results") or []
    if len(movie_results) != 1:
        return None, {
            "title": expected_title,
            "videoId": imdb_id,
            "reason": "tmdb_find_movie_not_unique",
        }

    tmdb_id = movie_results[0]["id"]
    query = urllib.parse.urlencode({"api_key": key, "language": "en-US"})
    detail_url = f"https://api.themoviedb.org/3/movie/{tmdb_id}?{query}"
    detail = fetch_cached_json(
        detail_url, f"tmdb_movie_{tmdb_id}", refresh_cache=refresh_cache
    )

    if detail.get("imdb_id") and detail["imdb_id"] != imdb_id:
        return None, {
            "title": expected_title,
            "videoId": imdb_id,
            "reason": "tmdb_imdb_id_mismatch",
        }

    cinemeta_url = f"https://v3-cinemeta.strem.io/meta/movie/{imdb_id}.json"
    cinemeta_meta = fetch_cached_json(
        cinemeta_url, f"cinemeta_movie_{imdb_id}", refresh_cache=refresh_cache
    ).get("meta") or {}

    release_date = detail.get("release_date") or ""
    if not is_released_date(release_date, today_iso):
        return None, {
            "title": expected_title,
            "videoId": imdb_id,
            "reason": "unreleased_movie",
        }

    runtime = detail.get("runtime")
    artwork_path = detail.get("backdrop_path") or detail.get("poster_path")
    if not runtime or int(runtime) <= 0 or not artwork_path:
        return None, {
            "title": expected_title,
            "videoId": imdb_id,
            "reason": "missing_movie_runtime_or_artwork",
        }

    canonical_title = (
        cinemeta_meta.get("name")
        or detail.get("title")
        or expected_title
    ).strip()
    description = (
        cinemeta_meta.get("description")
        or detail.get("overview")
        or ""
    ).strip()
    year = parse_year(release_date) or parse_year(str(cinemeta_meta.get("year") or ""))

    program = {
        "videoId": imdb_id,
        "title": canonical_title,
        "season": 0,
        "episode": 0,
        "durationMs": int(runtime) * 60_000,
        "thumbnail": TMDB_IMAGE_BASE + artwork_path,
        "description": description,
    }
    if year > 0:
        program["year"] = year
    return program, None


def atomic_write_json(target_path, payload):
    target_path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    if len(rendered.encode("utf-8")) > 2 * 1024 * 1024:
        raise SystemExit("Live TV manifest exceeds the app's 2 MB asset limit")
    fd, tmp_name = tempfile.mkstemp(
        prefix=f".{target_path.name}.",
        suffix=".tmp",
        dir=str(target_path.parent),
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(rendered)
        os.replace(tmp_name, target_path)
    except Exception:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
        raise
    return len(rendered.encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(
        description="Refresh the JedFlix Live TV manifest from assets/live-tv-lineup.json."
    )
    parser.add_argument(
        "--lineup",
        type=Path,
        default=DEFAULT_LINEUP,
        help="Path to the curated lineup configuration JSON.",
    )
    parser.add_argument(
        "--existing",
        type=Path,
        default=DEFAULT_MANIFEST,
        help="Path to the existing manifest to preserve frozen channels (default: assets/live-tv.json).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_MANIFEST,
        help="Destination manifest path (default: assets/live-tv.json).",
    )
    parser.add_argument(
        "--refresh-cache",
        action="store_true",
        help="Bypass .local/live-tv-metadata cache and re-fetch metadata.",
    )
    args = parser.parse_args()

    key = load_tmdb_key()
    if not args.lineup.exists():
        raise SystemExit(f"Lineup config not found: {args.lineup}")

    lineup = json.loads(args.lineup.read_text(encoding="utf-8"))
    existing_channels_by_id = {}
    existing_manifest = {}
    if args.existing.exists():
        existing_manifest = json.loads(args.existing.read_text(encoding="utf-8"))
        for ch in existing_manifest.get("channels") or []:
            if isinstance(ch, dict) and ch.get("id"):
                existing_channels_by_id[ch["id"]] = ch

    today_iso = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    channels_out = []
    all_omissions = []

    for ch_cfg in lineup.get("channels") or []:
        ch_id = ch_cfg["id"]
        ch_type = ch_cfg["type"]

        if ch_cfg.get("preserveExisting"):
            preserved = existing_channels_by_id.get(ch_id)
            if not preserved or not preserved.get("programs"):
                raise SystemExit(
                    f"Channel '{ch_id}' requires preserveExisting, but was not found in {args.existing}"
                )
            # Deep copy to guarantee byte-for-byte preservation of the frozen
            # program list. Artwork is presentation-only and may be refreshed.
            preserved_copy = json.loads(json.dumps(preserved))
            poster = channel_poster_url(
                ch_cfg.get("thumbnailImdbId"), key, refresh_cache=args.refresh_cache
            )
            if poster:
                preserved_copy["thumbnail"] = poster
            channels_out.append(preserved_copy)
            continue

        if ch_cfg.get("discover"):
            programs, omissions = build_discover_channel(
                ch_cfg["discover"], key, today_iso, refresh_cache=args.refresh_cache
            )
            all_omissions.extend({"channel": ch_id, **item} for item in omissions)
        elif ch_type == "series":
            programs = []
            seen_video_ids = set()
            for show_cfg in ch_cfg.get("shows") or []:
                show_progs, show_omissions = build_series_show_programs(
                    show_cfg,
                    key,
                    existing_channels_by_id,
                    today_iso,
                    refresh_cache=args.refresh_cache,
                )
                all_omissions.extend(
                    {"channel": ch_id, **item} for item in show_omissions
                )
                for prog in show_progs:
                    vid = prog["videoId"]
                    if vid in seen_video_ids:
                        raise SystemExit(f"Duplicate videoId {vid} in channel {ch_id}")
                    seen_video_ids.add(vid)
                    programs.append(prog)
        elif ch_type == "movie":
            movie_cfgs = ch_cfg.get("movies") or []

            def build_one_movie(m_cfg):
                return build_movie_program(
                    m_cfg, key, today_iso, refresh_cache=args.refresh_cache
                )

            with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
                results = list(executor.map(build_one_movie, movie_cfgs))

            programs = []
            seen_video_ids = set()
            for prog, omission in results:
                if omission:
                    all_omissions.append({"channel": ch_id, **omission})
                    continue
                vid = prog["videoId"]
                if vid in seen_video_ids:
                    raise SystemExit(f"Duplicate movie videoId {vid} in channel {ch_id}")
                seen_video_ids.add(vid)
                programs.append(prog)
        else:
            raise SystemExit(f"Unsupported channel type '{ch_type}' for channel '{ch_id}'")

        if not programs:
            raise SystemExit(f"Refusing to write empty program list for channel '{ch_id}'")
        if len(programs) > 10000:
            raise SystemExit(f"Channel '{ch_id}' exceeds 10,000 programs ({len(programs)})")

        channel_obj = {
            "id": ch_id,
            "name": ch_cfg["name"],
            "description": ch_cfg.get("description", ""),
            "type": ch_type,
        }
        if ch_cfg.get("imdbId"):
            channel_obj["imdbId"] = ch_cfg["imdbId"]
        poster = channel_poster_url(
            ch_cfg.get("thumbnailImdbId"), key, refresh_cache=args.refresh_cache
        )
        if poster:
            channel_obj["thumbnail"] = poster
        channel_obj["seed"] = int(ch_cfg["seed"])
        channel_obj["programs"] = programs
        channels_out.append(channel_obj)

    if not channels_out:
        raise SystemExit("Refusing to write manifest with no channels")

    manifest = {
        "version": int(lineup.get("version", 1)),
        "revision": lineup.get("revision", "lineup-v3"),
        "epochMs": int(lineup.get("epochMs", 1767225600000)),
        "channels": channels_out,
    }
    if any(ch.get("preserveExisting") for ch in lineup.get("channels", [])):
        if manifest["epochMs"] != existing_manifest.get("epochMs"):
            raise SystemExit("Preserving existing channels requires the original epochMs")

    size_bytes = atomic_write_json(args.output, manifest)
    try:
        rel_out = args.output.resolve().relative_to(ROOT)
    except ValueError:
        rel_out = args.output

    summary = ", ".join(f"{ch['id']}={len(ch['programs'])}" for ch in channels_out)
    print(
        f"Wrote {len(channels_out)} channels ({summary}) "
        f"[{size_bytes:,} bytes] to {rel_out}"
    )
    if all_omissions:
        print(f"Omitted {len(all_omissions)} item(s) due to strict metadata checks:")
        for item in all_omissions:
            label = item.get("seriesTitle") or item.get("title") or ""
            print(
                f"  - channel={item['channel']} {label} ({item.get('videoId', '')}): "
                f"{item.get('title', '')!r} -> {item['reason']}"
            )


if __name__ == "__main__":
    main()
