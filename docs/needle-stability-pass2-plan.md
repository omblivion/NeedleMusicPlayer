# Needle stability pass 2

Continue on `needle/stability-rebrand`; no merge, push, release, installation or
publication. Starting commit: `d1603ee9`. Keep minSdk 19, current SDK targets and
existing architecture.

## Tasks

- [x] Check SAF registration against API 19/21 boundaries. Gate tree-only
  contracts by API and ensure picker cancellation never writes, deletes, or
  persists permissions. Add behavioral regression tests before fixes.
- [x] Verify play-count cursor/schema behavior. Make mandatory columns explicit
  without concealing malformed schemas; preserve counts and weekly scoring.
- [x] Exercise empty queues, insertion at/before the current track, repeated
  shuffle cycles, restore after removed tracks, and duplicate occurrences.
  Repair only demonstrated invariant violations.
- [x] Re-run all variant tests, APK assembly/shrinking and lint. Make lint errors
  blocking once the existing eight reports have been resolved, retaining visible
  warnings rather than blanket suppression.
- [x] Review the pass; update the backlog with actual results and limitations.

## Completion conditions

Each behavioral repair has a regression observed RED→GREEN. Current/original
queues retain a bijection between occurrences and valid current/next positions.
Cancelled external UI causes no file operations. SDK coverage must state whether
it uses actual Robolectric API images or simulated branches. No device behavior
is claimed from JVM tests.

## Progress

- Initial checkout is clean on the expected branch.
- Starting lint report: six OpenDocumentTree API-level errors and two unchecked
  required-column errors. The SAF result callbacks also currently act on null
  cancellation results; investigate them rather than suppressing annotations.
- SAF regression: five cases observed failing, then passing with the full debug
  suite. Tree-only contracts are registered only on API 21+, null results have no
  file/permission side effects, and cancellation restores the save button.
- Ruling: Robolectric 4.14 rejects API 19 (`UnknownSdk`). Actual picker behavior is
  tested on its API 21 image; API 19 registration branching is simulated on that
  runtime and explicitly named as such. Real API 19 device coverage remains open.
- Required play-count columns now use `getColumnIndexOrThrow`: malformed cursors
  fail explicitly rather than indexing -1. Two missing-column regressions were
  observed RED→GREEN; actual SQLite tests preserve same-week count/score and
  shift previous-week counts correctly.
- Queue: 13 initial invariant failures reproduced and repaired; later negative
  position, final-removal service, and prepared-next occurrence cases were also
  observed RED→GREEN. A 500-operation mixed-edit test covers occurrence mappings
  and unaffected current-track identity. Shuffle undo now copies indexed
  occurrences rather than sharing mutable indexes between both lists.
- Removing the final track now schedules playback clearing even though -1 also
  serves as the "no replacement" sentinel. Prepared positions follow insert,
  remove, move and shuffle edits, or become invalid if their occurrence is removed.
- Latest completed debug suite/lint is green with zero lint errors; 340 visible
  warnings remain. `lint.abortOnError` is now true, without global suppression.
  Full-variant/build verification and independent review follow.
- Warning classification (debug report): 76 RTL-hardcoded, 37 unused resources,
  33 full-list RecyclerView refreshes, 22 locale-default operations, 20 plural
  candidates and 19 dependency-update suggestions. Counts identify follow-up
  areas, not confirmed bugs or measured performance problems.
- Full validation: `test assembleFdroidDebug assembleFdroidRelease lint`
  succeeded. Six suites each contain 69 tests with zero failures/errors
  (414 executions). Debug/release APK assembly and R8 succeeded.
- Independent review found no critical/important new regressions. Deferred
  minors: (1) final-removal service tests verify scheduling, not actual
  player/handler clearing; (2) restored index permutation is validated but
  cross-list song-ID correspondence is not yet checked.
- Ruling: keep these two inherited/boundary-hardening items explicit rather than
  claim full recovery or native-player validation. Cost: corrupted cross-list
  persistence and actual handler/player behavior still need dedicated coverage.
- Review considerations retained as follow-ups: legacy successful API19 URI
  forwarding; real device/provider/API19/lifecycle compatibility; native-player
  callback/edit interleavings; inherited paused shuffled-open selection.
  None is considered established by JVM index tests. No second review dispatch
  or release/integration action is required for this pass.
- Explicit six-variant lint verification also succeeded:
  `lintFdroidDebug lintFdroidRelease lintCiDebug lintCiRelease lintPlayStoreDebug
  lintPlayStoreRelease`. All six reports contain zero errors; each debug report
  has 340 warnings and each release report has 335 warnings.
