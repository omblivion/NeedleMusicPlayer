# Playback Error Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make playback survive corrupt/unreadable tracks by skipping them automatically, telling the user, and continuing the queue — instead of stopping silently on the first bad file.

**Architecture:** `MultiPlayer` stops deciding policy: on a current-player `MediaPlayer.onError` it releases the broken player safely, declares the track failed, and reports `onTrackFailed()` to the service. `MusicService` owns the skip decision: it records the failed URI in a session-only registry, increments a consecutive-skip counter (reset only after 10 s of real playback), and advances through the existing `StaticPlayingQueue` next-position path, honoring repeat/shuffle with three defined terminal states (clean stop at end-of-queue with repeat off, toast-and-stop under repeat-this, hard stop at the skip cap).

**Tech Stack:** Java (Android), `android.media.MediaPlayer` via the existing `MultiPlayer` facade, Robolectric + JUnit4 tests, AGP 9.5.1 / Gradle wrapper, flavors `playStore`/`fdroid`/`ci`.

**Spec:** `docs/superpowers/specs/2026-11-17-playback-error-resilience-design.md`

## Global Constraints

- Language: **English only** — code, identifiers, comments, commit messages, docs, and all new user-facing strings.
- Package root: `io.github.omblivion.needle`, under `app/src/main/java/io/github/omblivion/needle/`.
- Do **not** replace `MediaPlayer` with ExoPlayer/Media3; keep `MultiPlayer`.
- Keep the `MusicService` + `PlaybackHandler` message-loop architecture and `StaticPlayingQueue` queue math.
- Tests: Robolectric, `@RunWith(RobolectricTestRunner.class)`, `@Config(sdk = 34, application = App.class)`. Test SDK must be ≥ 34.
- TDD: write the failing test, watch it fail for the right reason, then implement.
- Constants from the spec: `MAX_CONSECUTIVE_SKIPS = 5`, `SKIP_RESET_PROGRESS_MS = 10000`.
- Run all commands with `JAVA_HOME` set:
  `$env:JAVA_HOME="C:\Users\Leo\.jdks\ms-17.0.16"`
- Unit tests: `.\gradlew.bat :app:testFdroidDebugUnitTest --console=plain`
- Lint (six variants, must stay at zero errors):
  `.\gradlew.bat :app:lintFdroidDebug :app:lintFdroidRelease :app:lintCiDebug :app:lintCiRelease :app:lintPlayStoreDebug :app:lintPlayStoreRelease --console=plain`
- Never merge, push, or tag.

## Review Focus

Inputs/conditions the spec implies but individual tasks might leave untested. Each is pinned to a task below.

1. **A file that opens fine then dies at runtime** — must count toward the cap like any failure, and must not reset the counter (this is the reported bug's shape). → Task 7.
2. **A track whose URI is empty/`null` or whose id is the `-1` sentinel** — must never crash, must never be mis-keyed onto another track in the registry. → Task 2.
3. **`REPEAT_MODE_THIS` on a broken track** — must not re-open the same URI in a loop; toast once and stop. → Task 4.
4. **A corrupt embedded cover image** — must degrade to no artwork, never throw out of the media-session metadata update. → Task 6.
5. **A healthy queue with gapless preload on** — must behave exactly as before, and a natural completion must land on the preloaded track. → Task 5.

---

### Task 1: `PlaybackCallbacks.onTrackFailed()` and safe `MultiPlayer` failure handling

**Files:**
- Modify: `app/src/main/java/io/github/omblivion/needle/service/playback/Playback.java:42-46`
- Modify: `app/src/main/java/io/github/omblivion/needle/service/MultiPlayer.java:77-106` (`setDataSourceImpl`), `:387-420` (`onError`)
- Test: `app/src/test/java/io/github/omblivion/needle/service/MultiPlayerFailureTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Playback.PlaybackCallbacks.onTrackFailed()` — `void`, no args.
  - `MultiPlayer.onError(MediaPlayer mp, int what, int extra)` returns `true` for a current-player failure (was `false`).
  - On a current-player failure `MultiPlayer` clears `mNextMediaPlayer`, recreates `mCurrentMediaPlayer` only when `context != null`, sets `mIsInitialized = false`, and invokes `callbacks.onTrackFailed()`.
  - `MultiPlayer.setDataSourceImpl` routes its caught exception through `OopsHandler.collectStackTrace` and still returns `false`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaPlayer;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.service.playback.Playback;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class MultiPlayerFailureTest {
    private static class RecordingCallbacks implements Playback.PlaybackCallbacks {
        int failedCount;
        int wentToNextCount;
        int endedCount;

        @Override public void onTrackWentToNext() { wentToNextCount++; }
        @Override public void onTrackEnded() { endedCount++; }
        @Override public void onTrackFailed() { failedCount++; }
    }

    @Test
    public void currentPlayerErrorReportsFailureSuppressesCompletionAndDiscardsPreload() {
        MultiPlayer player = new MultiPlayer(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks();
        player.setCallbacks(callbacks);

        MediaPlayer broken = ReflectionHelpers.getField(player, "mCurrentMediaPlayer");
        shadowOf(broken).setState(ShadowMediaPlayer.State.STARTED);
        ReflectionHelpers.setField(player, "mIsInitialized", true);
        ReflectionHelpers.setField(player, "mNextMediaPlayer", new MediaPlayer());

        assertTrue(player.onError(broken, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0));
        assertEquals(1, callbacks.failedCount);
        assertEquals(0, callbacks.endedCount);
        assertEquals(0, callbacks.wentToNextCount);
        assertNull(ReflectionHelpers.getField(player, "mNextMediaPlayer"));
        assertNotSame(broken, ReflectionHelpers.getField(player, "mCurrentMediaPlayer"));
        player.release();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.MultiPlayerFailureTest" --console=plain`
Expected: compilation failure — `onTrackFailed()` does not exist in `Playback.PlaybackCallbacks`.

- [ ] **Step 3: Add `onTrackFailed()` to `PlaybackCallbacks`**

In `Playback.java`, inside `interface PlaybackCallbacks`, alongside `onTrackWentToNext`/`onTrackEnded`:

```java
void onTrackFailed();
```

- [ ] **Step 4: Rework `MultiPlayer`**

In `setDataSourceImpl`, keep the `catch (Exception e) { return false; }` shape but log first:

```java
} catch (Exception e) {
    OopsHandler.collectStackTrace(e);
    return false;
}
```

Rewrite the current-player branch of `onError` (keep the preload branch as-is, but guard the release). On a current-player failure, in order: set `mIsInitialized = false`; release `mNextMediaPlayer` (guarded) and null it; release `mCurrentMediaPlayer` (guarded); if `context != null`, build a fresh `MediaPlayer`, `setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)`, and assign it; finally invoke `callbacks.onTrackFailed()` if callbacks is non-null, and `return true`. Do not call `onTrackWentToNext` or `onTrackEnded` here.

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.MultiPlayerFailureTest" --console=plain`
Expected: PASS.

- [ ] **Step 6: Run the gapless test and the full suite**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --console=plain`
Expected: all green, including `GaplessErrorTest` (its preload branch is unchanged).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/playback/Playback.java app/src/main/java/io/github/omblivion/needle/service/MultiPlayer.java app/src/test/java/io/github/omblivion/needle/service/MultiPlayerFailureTest.java
git commit -m "feat: report playback failure from MultiPlayer instead of advancing"
```

---

### Task 2: Session-only unplayable-track registry

**Files:**
- Create: `app/src/main/java/io/github/omblivion/needle/service/UnplayableTrackRegistry.java`
- Test: `app/src/test/java/io/github/omblivion/needle/service/UnplayableTrackRegistryTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `UnplayableTrackRegistry()` — no-arg constructor.
  - `boolean markUnplayable(String uri)` — returns `true` if newly added, `false` if already present or if `uri` is `null`/empty (not stored).
  - `boolean isUnplayable(String uri)` — `false` for `null`/empty.
  - `void clear()`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableTrackRegistryTest {
    @Test
    public void markingIsIdempotentAndLookupWorks() {
        UnplayableTrackRegistry registry = new UnplayableTrackRegistry();
        assertTrue(registry.markUnplayable("content://media/external/audio/media/1"));
        assertFalse(registry.markUnplayable("content://media/external/audio/media/1"));
        assertTrue(registry.isUnplayable("content://media/external/audio/media/1"));
        assertFalse(registry.isUnplayable("content://media/external/audio/media/2"));
    }

    @Test
    public void nullAndEmptyUrisAreNeverStoredOrMatched() {
        UnplayableTrackRegistry registry = new UnplayableTrackRegistry();
        assertFalse(registry.markUnplayable(null));
        assertFalse(registry.markUnplayable(""));
        assertFalse(registry.isUnplayable(null));
        assertFalse(registry.isUnplayable(""));
    }

    @Test
    public void clearForgetsEverything() {
        UnplayableTrackRegistry registry = new UnplayableTrackRegistry();
        registry.markUnplayable("content://media/external/audio/media/1");
        registry.clear();
        assertFalse(registry.isUnplayable("content://media/external/audio/media/1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.UnplayableTrackRegistryTest" --console=plain`
Expected: compilation failure — class `UnplayableTrackRegistry` does not exist.

- [ ] **Step 3: Implement `UnplayableTrackRegistry`**

A class holding a `Set<String>` (use `Collections.newSetFromMap(new ConcurrentHashMap<>())` so it is safe from the playback handler thread and the UI thread). `markUnplayable` and `isUnplayable` return `false` immediately for `null` or empty input.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.UnplayableTrackRegistryTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/UnplayableTrackRegistry.java app/src/test/java/io/github/omblivion/needle/service/UnplayableTrackRegistryTest.java
git commit -m "feat: add session-only registry of unplayable track URIs"
```

---

### Task 3: Auto-skip on open failure, with the consecutive-skip cap

**Files:**
- Modify: `app/src/main/java/io/github/omblivion/needle/service/MusicService.java` (fields near `:146`; `playSongAtImpl` `:1002-1008`; add helpers)
- Modify: `app/src/main/res/values/strings.xml` (add `too_many_unplayable_files`)
- Test: `app/src/test/java/io/github/omblivion/needle/service/PlaybackSkipTest.java` (create)

**Interfaces:**
- Consumes: `UnplayableTrackRegistry` (Task 2).
- Produces:
  - `MusicService.MAX_CONSECUTIVE_SKIPS = 5`, `MusicService.SKIP_RESET_PROGRESS_MS = 10000` (public constants).
  - `void MusicService.playSongAtImpl(int position)` — on a failed open now: mark URI unplayable, toast, and advance when allowed, instead of only toasting.
  - `void MusicService.skipToNextPlayable(boolean skippedLast)` — package-private; the single auto-advance entry point.
  - `boolean MusicService.shouldSkipTrackAt(int position)` — package-private; `true` when the track at `position` is registered unplayable.
  - `void MusicService.resetConsecutiveSkipsIfPlayed()` — package-private; resets the counter when `getSongProgressMillis() >= SKIP_RESET_PROGRESS_MS`.
  - `void MusicService.stopAfterTooManyFailures()` — package-private; toasts the cap message and notifies a state change.
  - **Test seams (package-private, part of this task's deliverable):** `int getConsecutiveSkipCount()`, `void setConsecutiveSkipCount(int)`, `void openQueueForTest(String[] trackUris, int currentPosition)` (builds a `StaticPlayingQueue` from URIs and sets it as the current queue), `boolean isTrackUriUnplayableForTest(String uri)`, `void markUriUnplayableForTest(String uri)`, `String trackUriAtForTest(int position)`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PlaybackSkipTest {
    /** A MusicService whose track opening always fails, so the skip path is exercised. */
    private static class FailingOpenService extends MusicService {
        final ArrayList<Integer> openedPositions = new ArrayList<>();

        @Override boolean openTrackAndPrepareNextAt(int position) {
            openedPositions.add(position);
            return false;
        }
    }

    @Test
    public void aFailingTrackIsMarkedAndPlaybackAdvancesToTheNextPosition() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b", "c"}, 0);

        service.playSongAtImpl(0);

        assertEquals(1, service.getConsecutiveSkipCount());
        assertTrue(service.isTrackUriUnplayableForTest("a"));
        assertEquals(1, service.openedPositions.get(service.openedPositions.size() - 1).intValue());
    }

    @Test
    public void aRunOfFailuresStopsAtTheCap() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b", "c", "d", "e", "f", "g", "h"}, 0);

        service.playSongAtImpl(0);

        assertEquals(MusicService.MAX_CONSECUTIVE_SKIPS, service.getConsecutiveSkipCount());
    }
}
```

The two seams `openQueueForTest(String[], int)` and `isTrackUriUnplayableForTest(String)` are declared in this task's Interfaces block and are part of what you implement; `FailingOpenService` overrides the package-private `openTrackAndPrepareNextAt(int)` so no real media player is opened.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.PlaybackSkipTest" --console=plain`
Expected: FAIL — `getConsecutiveSkipCount()` / `isTrackUriUnplayableForTest()` do not exist, and today's `playSongAtImpl` does not advance.

- [ ] **Step 3: Implement the skip logic**

Add the two constants plus a `UnplayableTrackRegistry unplayableTracks` field and `int consecutiveSkipCount`. Add the helper seams. Rewrite `playSongAtImpl` so that, on a failed open: `unplayableTracks.markUnplayable(getTrackUri(getCurrentSong()))`, toast, then advance via a package-private `skipToNextPlayable(boolean skippedLast)` that (a) calls `resetConsecutiveSkipsIfPlayed()`, (b) increments the counter, (c) if the counter exceeds `MAX_CONSECUTIVE_SKIPS` calls `stopAfterTooManyFailures()` (toast `R.string.too_many_unplayable_files`, `notifyChange(PLAY_STATE_CHANGED)`) and returns, otherwise (d) calls `playNextSong(skippedLast)`. Keep the existing `R.string.unplayable_file` toast for the unknown-title case.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.PlaybackSkipTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/MusicService.java app/src/main/res/values/strings.xml app/src/test/java/io/github/omblivion/needle/service/PlaybackSkipTest.java
git commit -m "feat: auto-skip unplayable tracks with a consecutive-failure cap"
```

---

### Task 4: Skip decisions at the queue boundaries (repeat off / repeat this)

**Files:**
- Modify: `app/src/main/java/io/github/omblivion/needle/service/MusicService.java` (the `skipToNextPlayable` helper from Task 3)
- Modify: `app/src/main/res/values/strings.xml` (add `unplayable_file_skipped`)
- Test: `app/src/test/java/io/github/omblivion/needle/service/PlaybackSkipBoundaryTest.java` (create)

**Interfaces:**
- Consumes: Task 3's `skipToNextPlayable(boolean)`, `getConsecutiveSkipCount()`, `openQueueForTest(String[], int)`, `markUriUnplayableForTest(String)`.
- Produces: `skipToNextPlayable(boolean)` gains the two boundary rules — end-of-queue with `REPEAT_MODE_NONE` (clean stop, no wrap) and `REPEAT_MODE_THIS` (toast once, stop, no re-open). New seam `void setRepeatModeForTest(int mode)`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PlaybackSkipBoundaryTest {
    private static class FailingOpenService extends MusicService {
        int openAttempts;
        int stopCalls;

        @Override boolean openTrackAndPrepareNextAt(int position) {
            openAttempts++;
            return false;
        }

        @Override void stopAfterTooManyFailures() {
            stopCalls++;
        }
    }

    @Test
    public void brokenLastTrackWithRepeatOffStopsWithoutWrapping() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b"}, 1); // last track, 2 tracks
        service.setRepeatModeForTest(MusicService.REPEAT_MODE_NONE);

        service.playSongAtImpl(1);

        assertEquals(1, service.getConsecutiveSkipCount()); // counted once, not a loop
    }

    @Test
    public void brokenTrackWithRepeatThisStopsAfterOneAttempt() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b"}, 0);
        service.setRepeatModeForTest(MusicService.REPEAT_MODE_THIS);

        service.playSongAtImpl(0);

        assertEquals("toast once and stop, no re-open", 1, service.getConsecutiveSkipCount());
    }
}
```

Small package-private seam `setRepeatModeForTest(int)` is part of this task's deliverable; the assertions are what matters. Both tests assert the counter reaches exactly 1 — i.e. the skip stops instead of looping or wrapping.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.PlaybackSkipBoundaryTest" --console=plain`
Expected: FAIL — repeat-this currently re-opens the same position, and end-of-queue currently wraps or loops.

- [ ] **Step 3: Implement the boundary rules**

In `skipToNextPlayable`: when the current track is unplayable and either `getRepeatMode() == REPEAT_MODE_THIS`, or `getRepeatMode() == REPEAT_MODE_NONE && isLastTrack()`, toast `R.string.unplayable_file_skipped` with the track title, do not call `playNextSong`, and leave the counter at its incremented value. Otherwise continue to `playNextSong`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.PlaybackSkipBoundaryTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/MusicService.java app/src/main/res/values/strings.xml app/src/test/java/io/github/omblivion/needle/service/PlaybackSkipBoundaryTest.java
git commit -m "feat: stop cleanly at queue boundaries when the track is unplayable"
```

---

### Task 5: Registry-aware gapless preload

**Files:**
- Modify: `app/src/main/java/io/github/omblivion/needle/service/MusicService.java:599-613` (`prepareNextImpl`)
- Test: `app/src/test/java/io/github/omblivion/needle/service/PrepareNextUnplayableTest.java` (create)

**Interfaces:**
- Consumes: Task 3's registry, `openQueueForTest(String[], int)`, `markUriUnplayableForTest(String)`.
- Produces: `prepareNextImpl()` preloads the next playable candidate's URI and stores that same position in `playingQueue.setNextPosition(...)`, so a natural completion lands on the preloaded track. Preload-time avoidance is silent: no toast, no counter change.
- Test seams integrating with `MultiPlayer` are not needed; instead the task adds package-private seams `void MusicService.setNextDataSourceForTest(String uri)` (forwards to `playback.setNextDataSource`) and `void MusicService.setNextPositionForTest(int position)` (forwards to `playingQueue.setNextPosition`) so the test can observe the preload without a real player. Both are part of this task's deliverable.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PrepareNextUnplayableTest {
    private static class RecordingService extends MusicService {
        String preloadedUri;
        int preloadedPosition = -1;

        @Override void setNextDataSourceForTest(String uri) { preloadedUri = uri; }
        @Override void setNextPositionForTest(int position) { preloadedPosition = position; }
    }

    @Test
    public void preloadSkipsAKnownUnplayableUriAndStaysConsistent() {
        RecordingService service = new RecordingService();
        service.openQueueForTest(new String[]{"a", "b", "c"}, 0);
        service.markUriUnplayableForTest(service.trackUriAtForTest(1));

        service.prepareNextImpl();

        assertEquals(service.trackUriAtForTest(2), service.preloadedUri);
        assertEquals(2, service.preloadedPosition);
    }
}
```

`prepareNextImpl` computes URIs via the private `getTrackUri(Song)`; the `openQueueForTest` seam must build its `Song`s so that the resulting URI string is the track's `data` value. If the URI format makes the literal `"a"`/`"c"` comparison awkward, assert on `service.trackUriAtForTest(2)` in place of the literal — the invariant under test is *URI and position agree*, not the string spelling.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.PrepareNextUnplayableTest" --console=plain`
Expected: FAIL — today `prepareNextImpl` preloads `b` and stores position 1.

- [ ] **Step 3: Implement the registry check**

In `prepareNextImpl`, after computing `nextPosition`, walk forward from it (respecting `getRepeatMode()`; for `REPEAT_MODE_NONE` stop at the last track) until a position whose URI is not unplayable is found. Preload that position's URI and pass the same position to `playingQueue.setNextPosition(...)`. If none is playable, fall back to the existing `playback.setNextDataSource(null)` behavior. No toast and no counter change here.
- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.PrepareNextUnplayableTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/MusicService.java app/src/test/java/io/github/omblivion/needle/service/PrepareNextUnplayableTest.java
git commit -m "feat: keep gapless preload off known-unplayable tracks"
```

---

### Task 6: Guard the cover-art decode

**Files:**
- Modify: `app/src/main/java/io/github/omblivion/needle/service/MusicService.java:755-760`
- Test: `app/src/test/java/io/github/omblivion/needle/service/CoverArtGuardTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `MusicService.decodeArtwork(InputStream)` — package-private; returns the decoded `Bitmap` or `null` on any failure, logging through `OopsHandler`. `updateMediaSessionMetaData` uses it.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertNull;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class CoverArtGuardTest {
    @Test
    public void corruptArtworkBytesReturnNullInsteadOfThrowing() {
        MusicService service = new MusicService();
        byte[] garbage = "not an image".getBytes(StandardCharsets.UTF_8);
        assertNull(service.decodeArtwork(new ByteArrayInputStream(garbage)));
    }

    @Test
    public void nullStreamReturnsNull() {
        MusicService service = new MusicService();
        assertNull(service.decodeArtwork(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.CoverArtGuardTest" --console=plain`
Expected: compilation failure — `decodeArtwork` does not exist.

- [ ] **Step 3: Implement `decodeArtwork`**

Return `null` immediately for a `null` stream. Otherwise wrap `BitmapFactory.decodeStream` in a `try`, routing any exception through `OopsHandler.collectStackTrace` and returning `null`. Replace the direct `BitmapFactory.decodeStream(data)` call in `updateMediaSessionMetaData` with `decodeArtwork(data)`, keeping the existing `data != null` guard.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.CoverArtGuardTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/MusicService.java app/src/test/java/io/github/omblivion/needle/service/CoverArtGuardTest.java
git commit -m "fix: tolerate corrupt embedded cover art instead of throwing"
```

---

### Task 7: Wire `onTrackFailed()` and keep the skip counter honest

**Files:**
- Modify: `app/src/main/java/io/github/omblivion/needle/service/MusicService.java` (add `onTrackFailed()` near `onTrackEnded` `:1292-1300`; call `resetConsecutiveSkipsIfPlayed()` on natural completion and manual skip)
- Test: `app/src/test/java/io/github/omblivion/needle/service/TrackFailedServiceTest.java` (create)

**Interfaces:**
- Consumes: Task 1's `PlaybackCallbacks.onTrackFailed()`, Task 3's `skipToNextPlayable(boolean)`, counter, `openQueueForTest(String[], int)`, `isTrackUriUnplayableForTest(String)`.
- Produces: `MusicService.onTrackFailed()` — marks the current URI unplayable, then calls `skipToNextPlayable(true)`. A runtime failure (open succeeded, then `onError`) is treated identically to an open failure and counts toward the cap. Also calls `resetConsecutiveSkipsIfPlayed()` at the real-playback points (`onTrackEnded`, `PlaybackHandler.TRACK_WENT_TO_NEXT`, and the manual-skip path in `playSongAt`), so a runtime death is not accidentally treated as a success.

- [ ] **Step 1: Write the failing test**

```java
package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class TrackFailedServiceTest {
    private static class ProbeService extends MusicService {
        int skipRequests;

        @Override void skipToNextPlayable(boolean skippedLast) { skipRequests++; }
    }

    @Test
    public void aRuntimeFailureMarksTheTrackAndRequestsASkip() {
        ProbeService service = new ProbeService();
        service.openQueueForTest(new String[]{"a", "b"}, 0);

        service.onTrackFailed();

        assertTrue(service.isTrackUriUnplayableForTest("a"));
        assertEquals(1, service.skipRequests);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.TrackFailedServiceTest" --console=plain`
Expected: FAIL — `MusicService.onTrackFailed()` does not exist (the interface method is unimplemented).

- [ ] **Step 3: Implement `onTrackFailed()` and the reset points**

Add `@Override public void onTrackFailed()` that marks `getTrackUri(getCurrentSong())` unplayable and calls `skipToNextPlayable(true)`, honoring `playbackHandlerThread.isAlive()` the same way `onTrackEnded()` does. Also call `resetConsecutiveSkipsIfPlayed()` in the natural-completion path (`onTrackEnded`, or `PlaybackHandler.TRACK_WENT_TO_NEXT`) and in the manual skip path (`playSongAt`), so only real playback clears the counter.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --tests "*.TrackFailedServiceTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/omblivion/needle/service/MusicService.java app/src/test/java/io/github/omblivion/needle/service/TrackFailedServiceTest.java
git commit -m "feat: treat runtime playback failures as skippable tracks"
```

---

### Task 8: Full-suite, lint, and changelog

**Files:**
- Modify: `CHANGELOG.md`
- Test: whole suite (no new test file)

**Interfaces:**
- Consumes: everything above.
- Produces: a green suite, zero lint errors, and a changelog entry.

- [ ] **Step 1: Run the whole unit suite**

Run: `.\gradlew.bat :app:testFdroidDebugUnitTest --console=plain`
Expected: all green, including the pre-existing `GaplessErrorTest`, `QueueSaveHandlerTest`, and `QueueInvariantTest`.

- [ ] **Step 2: Run all six lint variants**

Run: `.\gradlew.bat :app:lintFdroidDebug :app:lintFdroidRelease :app:lintCiDebug :app:lintCiRelease :app:lintPlayStoreDebug :app:lintPlayStoreRelease --console=plain`
Expected: zero `severity="Error"` issues.

- [ ] **Step 3: Add the changelog entry**

Add a short English bullet under the unreleased/current section of `CHANGELOG.md` describing that corrupt or unreadable tracks are now skipped automatically with a notice, instead of stopping playback.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: note resilient playback for unplayable tracks"
```
