# Needle: device validation before release

This is a manual checklist, not a record of tests already performed.
Use a disposable music library and backup files before tag/deletion tests.
Do not run destructive tests on the user's only copy of their music.

## Install and identity

- Install Needle beside Vinyl. Both icons/labels are distinguishable; launching
  either opens that app. Needle's package is `io.github.omblivion.needle`
  (`.debug` on debug builds).
- Change settings in each app and verify the other is unaffected.
- Add widgets and shortcuts for both apps; each controls its own service.
- Check English, Italian, another translated locale, and RTL layout.
- Check adaptive icon masks and Android's themed monochrome icon.

## Playback and permissions

- On Android 13+, grant audio but deny images and non-media notifications. Music
  remains available; no blocking "storage access denied" flow for optional denial.
- Deny/revoke audio while a scan is pending. Existing index is not erased; the
  error is understandable and granting permission permits a retry.
- Test play/pause/seek/skip, repeat modes, shuffle, duplicate tracks and queue edits.
- With gapless enabled, use an unreadable/corrupt next file. Current audio should
  continue; next-track handling must recover without an endless error loop.
- Edit/reorder the queue immediately before a gapless transition, including
  duplicate tracks and removal of the prepared next occurrence. Metadata must
  still match the audio that actually starts; JVM index tests cannot establish
  native-player callback ordering.
- Remove the final queue item while playing and while paused; the old file must
  not continue playing and no invalid current position should remain.
- Test audio focus with calls/navigation, headset unplug, Bluetooth disconnect,
  lock screen, background playback, process termination and restoration.
- Test Android Auto browsing and controls.

## Library and settings

- Test empty library, large library, removable storage disconnected/reconnected,
  non-ASCII names, duplicate names, rescans and excluded folders.
- Excluding `/Music` must not exclude `/MusicBackup`. Test the corresponding
  inclusion filter and paths with trailing separators.
- Export and import colors, grid sizes, ReplayGain, theme and library categories.
  Invalid, incomplete, out-of-range and incorrectly typed backups must not crash
  the app or partially change settings.

## Files and providers

- Edit tags/artwork on disposable MP3 and FLAC files, including large files,
  internal storage, SD card and providers with restricted write access.
- Remove a large cover and verify the shorter replacement has no trailing bytes,
  still decodes fully and retains all unrelated metadata.
- Verify failure before encoding/writing leaves the source unchanged.
- Cancel every document/file picker and its access guide. No permission grant,
  tag write, delete task or library mutation should occur, and tag saving must
  remain available for retry.
- Simulate no space/permission loss/termination during writing. The current
  replacement is **not atomic across external providers**; recovery must be
  assessed before claiming crash-safe editing.
- Verify deletion and sharing target only the selected files and explain that
  the underlying files are shared with Vinyl and other players.

## Compatibility and performance

- Exercise the oldest supported API 19, API 28, scoped-storage APIs 29/30,
  permission changes on API 33, and recent Android versions.
- Use both debug and minified release builds.
- Record cold-start time, scan time and peak memory against library size and
  file size before proposing further optimizations.
