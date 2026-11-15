# Needle: stability and independent identity

This document records the first pass. The subsequent
[stability pass 2](needle-stability-pass2-plan.md) resolves its eight remaining
lint errors and extends the suite to 69 tests per variant. The remaining audit
work is tracked in [the backlog](needle-audit-backlog.md).

## Approved scope

Needle Music Player (short name Needle) is an independent fork, installable
alongside Vinyl. Use `io.github.omblivion.needle` for application ID and namespace.
Preserve licenses, attribution, historical upstream references and external
dependency coordinates. Do not publish, push, change the GitHub repository, or
modify the original checkout and its untracked artwork.

Keep the current Java/Kotlin architecture, minSdk 19 and targetSdk 33 during this
stability pass. SDK/dependency modernization needs separate compatibility testing.
Both apps still access the same music files: editing/deleting those files affects
both apps.

## Execution checklist

- [x] Establish JDK 17 baseline: `testFdroidDebugUnitTest` (11 tests passing).
- [x] Add behavioral regression coverage for Android boundaries.
- [x] Preserve the library on failed scans; validate folder boundaries.
- [x] Save both queues atomically.
- [x] Validate preference imports before applying; restore ReplayGain types.
- [x] Stream tag writes and truncate destination; close resources on failure.
- [x] Isolate gapless preload errors; separate optional permissions.
- [x] Rename application, packages, resources and active project links.
- [x] Verify unit tests, debug APK, release shrinking and lint.
- [x] Independent review; document remaining audit/performance work.

## Verification and review focus

Tests must cover failed/null MediaStore queries versus successful empty queries,
invalid backups without partial application, interrupted queue saves, shorter and
large tag writes, and optional permission denial with audio access granted.
Use test-first regression cases and run the unit suite after each repair.
No performance improvement is claimed without a measurement. Device checks must
cover co-installation, background playback, Bluetooth, Android Auto, SD cards,
widgets and real provider write failures; do not report them as tested locally.

## Progress / rulings

- Native worktree starts detached, managed by OpenCode.
- Local Java defaults to 15; commands use installed Microsoft JDK 17.0.16 and
  local Android SDK via process environment, not global machine configuration.
- Baseline: 11 tests, zero failures/errors. Existing SDK XML, deprecation,
  annotation processing and third-party resource warnings remain.
- Scan/import/queue regression cases observed failing before fixes; 21-test suite
  now passes. SQLite trigger simulates failure while saving the second queue.
  Robolectric singleton reset is confined to tests.
- Second regression pass: 27 tests green, including sibling folder filters,
  optional permission denial, failed gapless preload, shorter tag replacement and
  failed tag encoding without truncation. The tag provider fixture was corrected
  to attach its authority; the original writer then demonstrably left trailing
  bytes (RED), and the streaming/truncating writer passed (GREEN).
- Deferred: discography listener/threading redesign and moving initial DB loading
  off the main thread require separate lifecycle tests and performance measurements.
  A collection-type-only change would not establish the required invariants.
- Ruling: writes are bounded-memory and truncate correctly, but are not atomic
  across external providers. IO failure during replacement remains a device-test
  and recovery-design concern; do not claim crash-safe music-file replacement.
- Rebrand: source packages/classes, themes, translated copy, component names,
  action prefixes and application ID migrated to Needle. Current repository URL
  is retained (`omblivion/VinylMusicPlayer`) rather than inventing a renamed repo.
  New launcher/banner assets are derived from copies of the user's untracked
  artwork; the original checkout's files are untouched.
- Ruling: disable inherited store publication and hide the Google Play rating
  action until a real Needle listing exists. CI builds/tests without signing
  secrets; cost is that Needle publishing must be configured deliberately later.
- Independent review: no critical issues. Two important issues confirmed and
  repaired with RED→GREEN tests: SQLite save exceptions no longer escape the
  handler, and invalid structured library categories reject the whole backup.
  Existing malformed stored categories also fall back to defaults.
- Ruling: broader preference-domain validation was considered rather than
  silently excluded. Grid counts outside the UI-supported 1..8 range can crash
  layout, so they also reject the entire backup (observed RED before repair).
  Remaining enum/numeric domains are a follow-up; cost is that this is not a
  comprehensive preference-schema migration.
- Review boundary coverage: multi-buffer (1 MiB + 17 bytes) replacement and
  missing source leave expected output/original data, respectively. These
  additional cases pass; they are not a peak-memory benchmark.
- Ruling: active intro/monochrome artwork and issue-template naming were treated
  as required rebrand completeness, not optional polish, and updated. Cost is
  that launcher masks and artwork redistribution still need human/device review.
- Review considerations not claimed as verified: real devices/providers,
  co-installation, background/Bluetooth/Auto/SD/widgets; crash-safe external writes;
  concurrency/startup/SDK/dependency modernization and measured performance.
  Source artwork hashes and original checkout status were checked separately;
  legal redistribution rights cannot be inferred from those checks.
- Full renamed tests/debug APK/R8 release/lint command succeeded before the final
  grid-range regression. Final full verification is repeated after that repair.
- Lint is not clean: first report contains 8 existing-code errors and 340 warnings.
  Follow-up details are in `needle-audit-backlog.md`; do not equate task exit 0
  with absence of lint findings.
- Artifact acceptance found another reproducible legacy issue: branch names with
  `/` generated nested APK paths, which CI's root-level glob misses. The
  configuration now sanitizes branch names before building filenames; root-level
  APK placement is checked after a clean build.
- Final evidence: `gradlew.bat clean test assembleFdroidDebug assembleFdroidRelease
  lintFdroidDebug` succeeded (247 tasks, 4m 8s). All six variant suites contain
  36 tests with zero failures/errors (216 executions total).
- Final APK metadata inspected with Android SDK `aapt`: debug ID
  `io.github.omblivion.needle.debug`, release ID `io.github.omblivion.needle`;
  launcher labels Needle DEBUG / Needle, main component in the new namespace.
  Both APKs are at their flavor/build-type output root. Release APK is unsigned.
- Final lint report: 8 errors, 340 warnings (not suppressed as resolved).
- Source artwork copy hashes match the originals; original checkout still has
  only the two preexisting untracked artwork files.
- Review fix pass complete: both important findings and category/grid input
  guards are covered by regression tests; multi-buffer/source-opening cases pass.
  No second reviewer dispatch was used. No merge, push, installation or
  publication has been performed.
- Deferred review minors: none left in the scoped rebrand/boundary coverage.
  Broader inherited risks and compatibility/performance work remain explicitly
  documented in `needle-audit-backlog.md` and `needle-device-checklist.md`.
- Ruling: this document records the conversational plan rather than inventing a
  missing earlier spec. Broad architectural rewrites and SDK upgrades are not
  part of this regression-fix/rebrand patch; the cost is deferred modernization.
