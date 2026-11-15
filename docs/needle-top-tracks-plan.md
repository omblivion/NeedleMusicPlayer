# Configurable most-played windows and per-track reset

## Approved specification

Work on `needle/stability-rebrand`, starting at `31c1167e`. No merge, push,
publication or installation. Keep minSdk 19 and the current architecture.

Default: **52 weeks**, not All time. Presets, descending:
All time, 1 year, 52 weeks, 6/4/3/2 months, 30 days, 7 days, 24 hours.
Rank the top 100 by real count descending, song ID ascending for ties.
Changing the window reads the same statistics; it never clears counts.

Keep every recoverable legacy weekly count in All time and, while its bucket is
within the most recent 52 epoch weeks, in the default view. Other windows use
precise plays recorded after migration. Explain this distinction in the UI.
Legacy contributions expire weekly, not at invented per-play timestamps.
The old recency-weighted score is no longer used.

Only in MyTopTracksPlaylist, a song menu offers a confirmed reset. Reset deletes
that song's dated/legacy history, zeroes its cumulative count, and advances a
persistent reset version so pending old sessions cannot resurrect counts.
It does not touch audio, other playlists, recent-history storage, or playback.
Future valid sessions can re-enter without a blacklist or artificial threshold.
Retain the current more-than-half-duration listening qualification.

## Design / interfaces

- `model/TopTracksWindow.java`: preset enum, preference tokens, localized labels,
  precise cutoff calculation. Default/fallback WEEKS_52. Hours/days/weeks are
  elapsed durations; months/year are calendar offsets in the clock's zone.
- `provider/SongPlayCountStore.java`: SQLite schema v4, transactionally migrate
  v3 weekly data without calling the old destructive upgrade. Store cumulative
  totals/reset versions, unique dated events and imported weekly buckets.
  Keep the v3 table as a read-only archive, never re-import it on reopen.
  `getTopPlayedResults(int, TopTracksWindow, long)` supplies the ranking;
  `recordPlay(long, long, String, long)` is idempotent and rejects stale reset
  versions; `resetSong(long)`, `getResetVersion(long)`, `clear()` own reset state.
- `SongPlayCountHelper`/`MusicService`: capture a unique occurrence and reset
  version when tracking begins, commit at most once through the existing
  qualifying-play path. Refresh top-track consumers via a separate statistics
  notification, not a synthetic playback metadata change.
- `PreferenceUtil`, settings XML/resources, settings importer: validated,
  exportable `top_tracks_window` with default `52w`, no free-form picker.
- `PlaylistDetailActivity` and a top-only song adapter/menu/dialog: confirmation,
  background reset, correct offset row, reload and visible selected period.
  Ordinary song/playlist menus do not acquire reset actions.

## Tasks / ledger

- [ ] Preset/cutoff tests RED→GREEN.
- [ ] Migration, raw ranking, dated windows, idempotence, reset and retention tests.
- [ ] Playback-session integration and reset-race tests.
- [ ] Settings persistence/import/export and loader integration.
- [ ] Context-only menu/confirmation/reset and refresh tests.
- [ ] Full suite, all six lint variants, debug/release APKs and independent review.

## Review focus

Migration rollback and reopening must not drop/double-import data. Reset must
survive close/reopen and window changes; old pending sessions and duplicate
callbacks must not restore old counts. Boundary timestamps, leap years, month-end
and DST must be deterministic. Reset cancellation and header-row clicks must have
no effects. No main-thread database queries/writes should be added to UI actions.

## Rulings

- Ruling: retain dated events for 400 days and all-time totals indefinitely.
  This bounds growth while covering every offered window including leap years;
  exact history older than that is intentionally not exposed as individual plays.
- Ruling: invalid stored preset falls back to 52w; an invalid preset in an
  imported backup rejects the whole import. No statistics are exported by the
  existing settings-only exporter.
- Ruling: the existing schema tests exercise the old weighted/live weekly
  representation. Replace them with v3 migration fixtures and v4 behavioral
  assertions when removing that implementation, rather than keep unused
  production methods solely for old tests.
