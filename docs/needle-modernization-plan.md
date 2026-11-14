# Needle: stability and independent identity

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
- [ ] Preserve the library on failed scans; validate folder boundaries.
- [x] Save both queues atomically.
- [x] Validate preference imports before applying; restore ReplayGain types.
- [ ] Stream tag writes and truncate destination; close resources on failure.
- [ ] Isolate gapless preload errors; separate optional permissions.
- [ ] Rename application, packages, resources and active project links.
- [ ] Verify unit tests, debug APK, release shrinking and lint.
- [ ] Independent review; document remaining audit/performance work.

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
- Ruling: this document records the conversational plan rather than inventing a
  missing earlier spec. Broad architectural rewrites and SDK upgrades are not
  part of this regression-fix/rebrand patch; the cost is deferred modernization.
