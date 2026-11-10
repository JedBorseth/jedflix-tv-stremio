# Live TV

Discover is replaced by a shared channel guide using the existing Comet /
Real-Debrid configuration. The lineup has thirteen channels: **The Office
24/7 (US)**, **Sitcoms**, **Christmas Movies**, **Comedy Movies**, **Cartoons**,
**Marvel MCU**, **Rom Com**, **Action**, **Documentaries**, **Christopher
Nolan**, **Quentin Tarantino**, **Sci-Fi**, and **Horror**.
Sitcoms mixes Friends, Seinfeld, The Office, Brooklyn Nine-Nine, and Arrested
Development. Cartoons mixes family shows with adult animation. Marvel MCU runs
the Marvel Studios film canon in release order; the two director channels use
each director's actual directing credits.

The starter lineup contains 195 Office episodes, 848 sitcom episodes, 337
cartoon episodes, and movie channels of 11-38 titles each. Cartoons includes
Avatar: The Last Airbender, Gravity Falls, SpongeBob, The Simpsons, Futurama,
and Bob's Burgers, with season caps to keep the mix balanced.

Every installation uses `assets/live-tv.json`: the same UTC epoch, seed,
episode IDs, and slot lengths. A deterministic shuffle creates one repeating
cycle. Opening the app or changing time zones does not restart the channel.
The Office channel retains its 195 regular episodes and original cycle. Both the guide
and player use `LiveTvSchedule` to resolve the current program and position.

## Guide

The guide follows the Home screen: a highlighted episode's title, description,
and background image appear above channel rows. Each row starts with a channel
poster tile, followed by landscape thumbnails for the current and upcoming
episodes. There is no page heading or clock. Highlighting an episode updates
the hero. Center on an episode card opens its details: a movie opens the movie
page, and a series opens the season/episode picker with the airing episode
focused and selected. Center on the channel tile still joins its live broadcast.
The channel poster is manifest presentation data (`channels[].thumbnail`); it
never affects the schedule.

Joining a channel starts partway through the scheduled episode. Pause and
seek allow delayed viewing; **Go Live** returns to the episode and position
currently on the shared schedule. A live client is allowed to drift up to ten
seconds from the wall clock before the player seeks; normal buffering therefore
does not trigger repeated catch-up seeks. Go Live leaves playback untouched
when it is already within that window and only seeks when it is genuinely
delayed. Video streams are resolved separately on each device using that
client's saved Comet settings and Real-Debrid account.

## Broadcast contract

The checked-in manifest is the source of truth, not metadata fetched when a
client opens the guide. Keep its epoch, seed, order and durations stable to
preserve the broadcast. Changing any of these changes what airs at a given
instant. All clients need the same manifest revision to share a schedule.
New channels can be added without changing the existing channel's cycle.

`assets/live-tv-lineup.json` configures the shared content selection and seeds.
`scripts/refresh-live-tv.py` explicitly builds the manifest using Cinemeta
episode IDs and TMDB runtimes. It preserves the existing Office broadcast and
is never run by a normal build.
It reads the existing TMDB credential locally and does not store it in the
manifest. Cinemeta splits some two-part episodes that TMDB combines, so the
refresh matches titles and divides those combined runtimes between parts.
Ambiguous segment matches are omitted and reported; the starter lineup omits
SpongeBob's "Gary Takes a Bath" because its episode metadata cannot be matched
reliably between providers.

To revise the lineup, edit `assets/live-tv-lineup.json`, bump its revision, and
run `python3 scripts/refresh-live-tv.py`. Use `--output` to inspect a staged
manifest first. `--refresh-cache` explicitly fetches updated metadata. Ship the
generated `assets/live-tv.json` to every client. The generator preserves frozen
channels and refuses an epoch change while they are preserved.

A channel is defined by one of three program sources: `shows` (Cinemeta series
episodes), `movies` (curated IMDb IDs), or a `discover` block. Discover kinds
are `company` (for example Marvel Studios), `genre` (one or more TMDB genre
IDs), and `director` (a person's directing credits). Discover channels filter
by vote count, rating, runtime, release date, `withoutGenreIds`, and
`excludeTitles` so specials, shorts, and compilation cuts stay out. Every
channel also names a `thumbnailImdbId`; the refresher resolves that title's
TMDB poster into `channels[].thumbnail` for the guide tile. Artwork is
presentation-only: only seeds, program order, and durations define the
broadcast.

Slot lengths are canonical metadata runtimes. Add-ons can return encodes with
different credits, recaps, numbering or cuts. Release selection is stable for
the same source results and excludes labelled extended/Superfan cuts. After a
file loads, its duration is compared with the slot; a release that differs by
more than three minutes or 15% is treated as a different cut, skipped, and the
next release is tried (at most twice). When no compatible alternative exists,
the available release continues using the shared slot and offset. Independently
resolved streams cannot promise frame-exact
synchronization across different releases. Keep Android's automatic date and
time enabled.

Before launching a source, the resolver checks its redirects with a one-byte
range request. Add-on error videos, failed HTTP responses, and HTML/JSON error
pages are skipped in favor of the next available release. Checks are bounded
by eight candidates and a short time budget; unavailable episodes report an
inline tuning error.

## Verification

Run `python3 scripts/verify-live-tv.py` for schedule and release-selection
checks. `python3 scripts/verify-discovery.py` checks the existing Home feed.
The regular APK build compiles the Android helpers and verifies its signature
and 16 KB alignment. Use `scripts/adb.sh` for device verification.

`python3 scripts/probe-live-tv.py --run-device` runs the schedule, stream-only
player compatibility, and controller lifecycle checks on the configured device.
Its controller uses fake player objects and performs no source requests.
