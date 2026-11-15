# Remaining audit and modernization work

This is a prioritized follow-up list, not a claim that every function in the
repository has been audited or that every risk is reproducible on a device.
The current patch prioritizes data integrity and independent Needle identity.

## Lint findings resolved in stability pass 2

The first full renamed build reported 8 errors and 340 warnings. Stability pass 2
resolved the eight errors and enabled `lint.abortOnError true`; warnings remain
visible. The original findings and their disposition are:

- Six `NewApi` reports: `OpenDocumentTree` contracts in
  `ui/activities/tageditor/AbsTagEditorActivity.java` and
  `dialogs/helper/DeleteSongsDialogApi19.java`. Validate registration and launch
  contracts now register only on API 21+, with cancellation regression coverage.
  Keep minSdk 19 until compatibility is explicitly decided. API 19 branches were
  simulated on a Robolectric API 21 runtime; real API 19 device coverage is open.
- Two `Range` reports: unchecked cursor column indexes in
  `provider/SongPlayCountStore.java`. Required columns now fail explicitly using
  `getColumnIndexOrThrow`; malformed-schema and real count/score tests were added.
- Classify remaining warnings. Do not hide them by globally disabling checks.

## Lifecycle, concurrency and performance

- `Discography.addActivity()` calls initial DB loading synchronously. Measure cold
  start at multiple library sizes before moving work off the main thread.
- Discography's activity/listener collections can be accessed from scan workers
  and the main thread. Define listener ownership, dispatch and cancellation;
  swapping collection implementations alone does not establish lifecycle safety.
- Review cache locking during tag extraction, DB writes, sorting, and UI updates.
- Search normalizes and scans sorted library copies on each query. Measure query
  latency/cancellation before adding caching or an index.
- Empty queue, deleted-current restore, duplicate occurrences, shuffle undo,
  insertion, batch removal and prepared-next index regressions now have coverage.
  Concurrent edits at the media-player callback boundary still need stress and
  lifecycle/device tests.
- Add actual playback-handler coverage for final queue removal; current service
  tests prove that clearing is requested, not that a native player has stopped.
- Harden restored queue correspondence: reject mismatched track IDs across
  otherwise valid index permutations.
- Verify paused `MusicService.openQueue(..., startPlaying=false)` with shuffle:
  it currently uses the pre-shuffle position when scheduling selection.

## Storage and external entry points

- External-provider tag replacement is streaming and correctly truncates shorter
  output, but is not atomic against power loss or mid-copy failures. A recovery
  design needs actual providers, disposable files and storage-full tests.
- FileProvider currently allows a broad external root. Trace all granted URIs
  and sharing flows before narrowing it or claiming a security vulnerability.
- Continue review of playlist import/export, deletion, SAF grants, incoming VIEW
  intents, media commands, and exported components.
- Legacy API 19 SAF flows still warrant an end-to-end review: the chosen file URI
  in the tag editor is not forwarded to the current MediaStore-based writer.
  Cancellation fixes do not establish successful SD-card writes on API 19.

## Compatibility and releases

- Test co-installation, widget/shortcut ownership, Bluetooth, Android Auto,
  background playback and SD cards using `needle-device-checklist.md`.
- Update target/compile SDK, foreground-service rules, receiver registration and
  edge-to-edge behavior as a separate compatibility change.
- Update old UI/theme libraries and align Glide integration/compiler versions
  independently, with release-shrinking checks.
- Current GitHub repository name remains `omblivion/VinylMusicPlayer`; no remote
  rename, push, merge or publication is performed by this work.
- Confirm permission to redistribute the supplied artwork and have the new
  privacy wording reviewed before public distribution. Historical attribution
  and the archived upstream privacy policy are retained, not rewritten as Needle
  maintainer claims.
