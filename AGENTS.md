# AGENTS.md — working notes for AI agents

This file records the environment facts, build/test commands, and conventions
that an agent needs to work on **Needle Music Player** without rediscovering
them. Keep it up to date when the setup changes.

## Language policy

**Everything in this repository must be in English**: code, identifiers,
comments, commit messages, documentation, changelog entries, issue and pull
request text, and any new user-facing string resources. Do not add new
non-English prose. (Translations of user-facing strings under
`app/src/main/res/values-<locale>/` are the one expected exception — those are
legitimate localization files, not prose.)

## What this project is

Needle is a local Android music player, independently forked from
[Vinyl Music Player](https://github.com/VinylMusicPlayer/VinylMusicPlayer)
(itself based on Phonograph). The current development line renamed the
application and moved the Java package to `io.github.omblivion.needle`.

- Upstream project: `VinylMusicPlayer/VinylMusicPlayer`
- This fork's remote: `origin` = `git@github.com:omblivion/VinylMusicPlayer.git`

### Lineage (merged)

`needle/stability-rebrand` and the published `origin/master` line both forked
from upstream commit `c84e73d5` and both re-established a "Needle" identity.
They are now **merged**: the current `master` carries the Android 14–17
modernization (from the old `origin/master`) together with the package rename,
stability passes, and configurable most-played windows (from
`needle/stability-rebrand`).

- Canonical app identity: application ID `io.github.omblivion.needle`,
  namespace `io.github.omblivion.needle`.
- The earlier published line used `org.needlemusicplayer.android` /
  `0.0.1`–`0.0.2`; those are historical and are not this app's version line.
- `docs/` and `docs/superpowers/plans/` hold historical plans; treat them as
  background, not as the current state.

## Toolchain — the exact setup that works

- **JDK: use 17.** Android Gradle Plugin 9.5.1 compiles Java/Kotlin against a
  JDK 17 toolchain. Java 17 is available at
  `C:\Users\Leo\.jdks\ms-17.0.16` (Microsoft OpenJDK 17) and works.
  - **Java 27 is installed on this machine** if a newer JDK is ever needed for
    Gradle itself or other tooling; AGP's Java/Kotlin compilation still targets 17.
  - The default `C:\Program Files\Java\jdk-15.0.2` (Java 15) is **rejected**.
  - Android Studio's bundled JBR (Java 25) is also **rejected** by this AGP line.
  - Set it per command: `$env:JAVA_HOME="C:\Users\Leo\.jdks\ms-17.0.16"`.
- **Android SDK**: `%LOCALAPPDATA%\Android\Sdk` (i.e.
  `C:\Users\Leo\AppData\Local\Android\Sdk`). Gradle needs it in a
  `local.properties` at the repo root (gitignored). Create it if missing:
  ```
  sdk.dir=C\:\\Users\\Leo\\AppData\\Local\\Android\\Sdk
  ```
  (In a fresh git worktree, `local.properties` is absent — create it.)
- **Gradle**: use the wrapper (`gradlew.bat` on Windows). AGP **9.5.1**,
  Gradle **9.6.0** (uses AGP's built-in Kotlin support plus the legacy-kapt
  bridge for Data Binding; there is no separate Kotlin Gradle plugin).
- **Shell**: the agent runs PowerShell (`pwsh`) on Windows. The repo's
  `script/*` helpers are `bash` and target macOS; there is **no** bash/WSL on
  this machine, so run their steps manually or via Gradle instead.
- compileSdk 37, minSdk 34 (Android 14+), targetSdk 37.

## Build, test, and verify commands

Run from the repo root with `JAVA_HOME` set as above.

```powershell
$env:JAVA_HOME="C:\Users\Leo\.jdks\ms-17.0.16"

# Unit tests (Robolectric + JUnit4). This is the suite to keep green.
.\gradlew.bat :app:testFdroidDebugUnitTest --console=plain

# Assemble debug and release APKs (fdroid flavor)
.\gradlew.bat :app:assembleFdroidDebug :app:assembleFdroidRelease --console=plain

# Official signed release APK (playStore flavor; requires keystore.properties)
.\gradlew.bat :app:assemblePlayStoreRelease --console=plain

# All six lint variants (must stay at zero errors; lint.abortOnError = true)
.\gradlew.bat :app:lintFdroidDebug :app:lintFdroidRelease `
  :app:lintCiDebug :app:lintCiRelease `
  :app:lintPlayStoreDebug :app:lintPlayStoreRelease --console=plain
```

Product flavors: `playStore`, `fdroid`, `ci` (dimension `default`); build types
`debug`, `release`. Debug adds `applicationIdSuffix .debug`.

### Reading test results without the HTML report

Test XML lands in `app/build/test-results/testFdroidDebugUnitTest/*.xml`.
Counting totals in PowerShell:

```powershell
$files = Get-ChildItem "app\build\test-results\testFdroidDebugUnitTest" -Filter "*.xml"
$t=0;$fa=0;$er=0
foreach($x in $files){ $xml=[xml](Get-Content $x.FullName)
  $t+=[int]$xml.testsuite.tests; $fa+=[int]$xml.testsuite.failures; $er+=[int]$xml.testsuite.errors }
"suites=$($files.Count) tests=$t failures=$fa errors=$er"
```

Lint XML: `app/build/reports/lint-results-<variant>.xml`; count
`severity="Error"` issues there.

## Testing conventions

- Tests live under `app/src/test/java/io/github/omblivion/needle/`.
- Robolectric tests use `@RunWith(RobolectricTestRunner.class)` and
  `@Config(sdk = 34, application = App.class)` (a few use `sdk = 35`). Because
  minSdk is 34, the test SDK **must be 34 or higher** — Robolectric refuses to
  load an app whose manifest requires a newer SDK than the test image
  (`Requires newer sdk version #34`).
- The project follows strict TDD: write the failing test, watch it fail for the
  right reason, then implement. Prefer real components over mocks (real SQLite
  via `SongPlayCountStore`, real `SharedPreferences`) — several existing tests
  do exactly this.
- After a change, run the **whole** suite, not just your new file.

## Architecture pointers (frequently touched)

- Package root: `io.github.omblivion.needle` under
  `app/src/main/java/io/github/omblivion/needle/`.
- Play statistics: `provider/SongPlayCountStore.java` (SQLite, schema v4:
  cumulative totals + reset versions, dated events, imported v3 weekly buckets).
  `service/SongPlayCountHelper.java` + `service/MusicService.java` record plays.
- Most-played windows: `model/TopTracksWindow.java` (preset enum + cutoff math),
  `PreferenceUtil.TOP_TRACKS_WINDOW`, `loader/TopAndRecentlyPlayedTracksLoader`.
- Music service broadcasts: constants in `service/MusicService.java`
  (`META_CHANGED`, `QUEUE_CHANGED`, `PLAY_STATE_CHANGED`, `STATISTICS_CHANGED`,
  ...); activities listen via `ui/activities/base/AbsMusicServiceActivity.java`
  and the `interfaces/MusicServiceEventListener` callbacks.
- Unplayable files: `provider/UnplayableTrackStore.java` keeps SQLite snapshots
  independently of `service/UnplayableTrackRegistry.java` (session-only skip
  protection). `model/smartplaylist/UnplayableTracksPlaylist.java` exposes them
  for cleanup; `UnplayableTrackStore.CHANGED` triggers
  `onUnplayableTracksChanged()`. Do not prune reports merely because a file is
  absent from the library: permission or storage loss can be temporary.
- Modern file deletion: `dialogs/helper/DeleteSongsDialogApi30.java` prepares
  MediaStore requests on a worker and launches confirmation on the UI thread.
  Only confirmed deletion updates the queue and removes matching reports.
- Preferences: `util/PreferenceUtil.java` + `util/PrefKey.java`
  (`PrefKey.exportableKey` marks a key as export/import-able). Settings UI is
  XML under `app/src/main/res/xml/pref_*.xml`; import/export validation lives in
  `preferences/SharedPreferencesImporter.java` (it rejects a whole backup on any
  invalid value).
- This is a `SQLiteOpenHelper`-based app (no Room). Keep the current
  architecture unless a task says otherwise.

## Release process

- Version lives in `app/build.gradle` (`versionCode`, `versionName`).
- Changelog: `CHANGELOG.md` (English).
- Helper scripts (macOS/bash, run their steps manually on Windows):
  `script/prepare-new-release`, `script/build-release`, `script/build-licenses-page`,
  `script/build-credits-page`.
- `gh` CLI is installed and authenticated (account `omblivion`).
- Signing: `keystore.properties` at the repo root (present locally); release
  APKs are signed via the `ci` signing config when it exists.
- GitHub release assets follow the published convention: `fdroidDebug` for
  the debug APK and `playStoreRelease` for the signed official APK. The
  `fdroidRelease` APK is unsigned and is not the official release asset.

## House rules for agents

- **Never** merge, push, tag, or publish unless the task explicitly asks for it.
- Keep changes consistent with surrounding style and package layout.
- Prefer the dedicated tools (read/edit/write/grep/glob) over shell.
- When a plan lists tasks, execute them in order and keep a short ledger of
  rulings; record any deviation from the plan with its reason.
