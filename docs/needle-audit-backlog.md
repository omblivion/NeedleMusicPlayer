# Remaining audit and modernization work

This is a prioritized follow-up list, not a claim that every function in the
repository has been audited or that every risk is reproducible on a device.
The current patch prioritizes data integrity and independent Needle identity.

## Existing lint errors

The first full renamed build reported 8 errors and 340 warnings; the task still
exits successfully because inherited Gradle configuration uses
`lint.abortOnError false`. A successful lint task is **not** a clean lint report.

- Six `NewApi` reports: `OpenDocumentTree` contracts in
  `ui/activities/tageditor/AbsTagEditorActivity.java` and
  `dialogs/helper/DeleteSongsDialogApi19.java`. Validate registration and launch
  paths on API 19/20 versus API 21+. Keep minSdk 19 until compatibility is
  explicitly decided; do not merely suppress the warnings.
- Two `Range` reports: unchecked cursor column indexes in
  `provider/SongPlayCountStore.java`. Establish the cursor/schema contract and
  test absent-column/error behavior before changing access.
- Classify warnings and introduce a reviewed baseline before making lint
  blocking. Do not hide existing warnings by globally disabling checks.

## Lifecycle, concurrency and performance

- `Discography.addActivity()` calls initial DB loading synchronously. Measure cold
  start at multiple library sizes before moving work off the main thread.
- Discography's activity/listener collections can be accessed from scan workers
  and the main thread. Define listener ownership, dispatch and cancellation;
  swapping collection implementations alone does not establish lifecycle safety.
- Review cache locking during tag extraction, DB writes, sorting, and UI updates.
- Search normalizes and scans sorted library copies on each query. Measure query
  latency/cancellation before adding caching or an index.
- Complete adversarial queue-state tests: empty queue, deleted current track,
  duplicate tracks, shuffle toggles, restore and concurrent edits.

## Storage and external entry points

- External-provider tag replacement is streaming and correctly truncates shorter
  output, but is not atomic against power loss or mid-copy failures. A recovery
  design needs actual providers, disposable files and storage-full tests.
- FileProvider currently allows a broad external root. Trace all granted URIs
  and sharing flows before narrowing it or claiming a security vulnerability.
- Continue review of playlist import/export, deletion, SAF grants, incoming VIEW
  intents, media commands, and exported components.

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
