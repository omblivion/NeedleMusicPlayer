# Design: Playback error resilience (skip unplayable tracks)

Date: 2026-11-17
Status: approved by owner; builder check done (see "What is left to the builder")
Owner: omblivion

## Intent

While playing a queue (e.g. a Spotify-MP3 folder), a single corrupt or
unreadable MP3 can stall playback: the app shows "Couldn't play this song"
and never advances, so everything after it stays silent. The user sees this
as "the app crashed and the rest of the songs don't play anymore".

Goal: a broken file must never stop the session. Playback skips it, tells
the user which track was skipped, and continues with the next playable
track, honoring the current repeat/shuffle mode.

## Goals

- A corrupt/unreadable/unplayable track is detected at open time and at
  `MediaPlayer` error time, and playback advances to the next track.
- The queue keeps playing: repeat/shuffle semantics are honored, and a
  broken track at the end of a repeating queue does not stop the loop.
- The user gets one brief toast per skipped track, with the skipped title
  when known.
- A run of consecutive failures is bounded, so a wholly broken folder does
  not spin forever through the queue.
- Failure reasons are logged through the existing `OopsHandler` instead of
  being swallowed silently.
- No regressions: a healthy track plays exactly as before, and gapless
  preload still works.

## Non-goals

- Persistent "never play this again" blacklist. Skipped tracks are
  *session-only* (see Decision 2).
- Repairing or re-tagging corrupt files, or re-scanning the library when a
  file fails.
- Replacing `android.media.MediaPlayer` with ExoPlayer/Media3.
- Detecting corruption during the MediaStore scan (that layer already
  tolerates bad tags; the file may become readable later).
- UI for reviewing skipped tracks (a future feature).

## Anti-goals

- Silent infinite skipping that leaves the app "playing" with no audio and
  no feedback.
- A skip that ignores repeat mode and jumps past the end of the queue.
- Advancing the queue on a *preload* failure, which would double-skip.
- Crashing or NPE-ing inside a `MediaPlayer` listener.

## Constraints

- Android app, Java, package `io.github.omblivion.needle`.
- Playback engine stays raw `MultiPlayer` (`android.media.MediaPlayer`).
- Existing architecture: `MusicService` + `PlaybackHandler` message loop,
  `StaticPlayingQueue` for queue math, `PreferenceUtil` for settings.
- Test conventions: Robolectric, `sdk = 34`, `application = App.class`,
  real components over mocks. Existing patterns in
  `service/GaplessErrorTest.java` (ReflectionHelpers + ShadowMediaPlayer) and
  `service/QueueSaveHandlerTest.java` (MusicService subclass probing).
- All prose/identifiers/strings in English (repo language policy).

## Decisions made with the user

1. **Behavior on a broken track: skip and continue.** Automatically move to
   the next track. Do not stop and wait for the user.
2. **Skipped tracks are session-only.** A track that fails is remembered in
   memory and not retried while the app is running; it stays in the library
   and can be retried after a restart.
3. **Feedback: one toast per skipped track**, reusing the existing
   `R.string.unplayable_file` semantics, including the track title when
   known.
4. **The skip honors repeat/shuffle** and will loop past the end of a
   repeating queue.
5. **The consecutive-skip counter resets only after real listening.** A
   track counts as "successfully played" once it has produced
   `SKIP_RESET_PROGRESS_MS` (10000 ms = 10 s) of audible playback. Opening
   a file is *not* success: a corrupt file can pass `prepare()` and die at
   runtime, which is the reported scenario, so resetting on open would make
   the cap useless.
6. **A broken last track with `REPEAT_MODE_NONE` stops cleanly:** toast
   only, no queue wrap, release the wake lock, honor a pending sleep-timer
   quit, and go idle exactly like a normal end-of-queue stop.
7. **`REPEAT_MODE_THIS` on a broken track: toast once and stop.** Do not
   re-open the same URI in a loop; the user can press next manually.
8. **A track that dies mid-play is skipped like any other failure**, even
   after having played past the reset threshold. Mid-track decode failures
   are treated identically to an unreadable file.
9. **Gapless preload silently avoids known-unplayable files.** It picks the
   next playable candidate without a toast and without counting a skip
   (the user has not heard anything yet), and keeps
   `nextPosition`/preloaded-URI consistent so a natural completion lands on
   the right track.

## The how

### 1. Session-only "unplayable" memory (in the service)

New small holder on `MusicService`, e.g. `Set<String> unplayableTrackUris`
(or a dedicated `service/UnplayableTrackRegistry.java` if it grows), keyed
by the track URI. Cleared implicitly when the process dies. Not persisted.

Purpose: prevent an immediate retry loop on the same URI and allow
`prepareNext` to look past a known-bad track.

### 2. Detect failure at open time

`MusicService.openCurrent()` already returns `false` when
`playback.setDataSource(uri)` fails. Extend the flow so that:

- On `false`, record the URI in the registry, show the skip toast, and ask
  the queue for the next candidate instead of stopping.
- Add a bounded auto-advance: a single entry point on the service, e.g.
  `skipAndPlayNext(...)`, that advances at most
  `MAX_CONSECUTIVE_SKIPS` (proposed: 5) times before stopping with a clear
  message. Bounds the "entire folder is broken" case.

**Skip counter policy (from Decision 5).** A service field
`consecutiveSkipCount` increments on each automatic skip and resets to 0
only when the currently playing track has reached
`SKIP_RESET_PROGRESS_MS` (10 s) of playback. Detection of that milestone:
the service already tracks position; evaluate it when advancing tracks
(natural completion, user skip, or before an auto-skip) rather than
installing a timer, so no new thread or handler is needed. Rationale is in
Decision 5: open-time success is not enough because a file can open and
then fail.

**Terminal states.** When a skip cannot continue:
- Last track + `REPEAT_MODE_NONE` → clean stop (Decision 6): leave the
  current position at the last candidate, release the wake lock, run
  `checkPendingQuit`, notify `PLAY_STATE_CHANGED`, and show the toast. No
  wrap.
- `REPEAT_MODE_THIS` + broken track → toast once and stop (Decision 7). Do
  not re-open the same URI.
- Cap reached (`MAX_CONSECUTIVE_SKIPS`) → stop playback, go idle, show a
  distinct cap message (new string, e.g. `too_many_unplayable_files`).

`MultiPlayer.setDataSourceImpl` currently swallows all exceptions with no
logging. Change it to route the cause through `OopsHandler.collectStackTrace`
(keeping the `false` return), so failures are diagnosable.

### 3. Detect failure at playback time (`MediaPlayer.onError`)

Rework `MultiPlayer.onError` (currently `service/MultiPlayer.java:387`):

- **Preload failure (`mp == mNextMediaPlayer`):** keep current behavior —
  clear the preload, return `true` (do not dispatch `onCompletion`, no queue
  advance). Guard `mNextMediaPlayer.release()` with try/catch.
- **Current-player failure:** mark uninitialized, release the broken player
  safely (guarded), recreate a player *only if `context != null`*, then
  return **`true`** to suppress the framework's `onCompletion` (which today
  fires on a released player). Do **not** advance the queue or promote the
  preload inside `MultiPlayer`; instead notify the service through a new
  callback `onTrackFailed()`, so the *service* owns the skip decision, the
  registry, the toast and the skip counter. This removes the double-advance
  and the released-player hazard.
- **The preloaded next player is discarded on a current-player failure.**
  `MultiPlayer` clears any pending preload as part of the failure handling;
  the service then re-opens the next track from the queue through the normal
  path. This is deliberately simpler than promoting the preload and keeps a
  single advance code path (`onTrackFailed` → service skip), at the cost of
  one extra `setDataSource` on a rare error. (Resolves the previous
  ambiguity: the service, not `MultiPlayer`, owns advancement.)
- Add a `PlaybackCallbacks.onTrackFailed()` method (default no-op for
  implementers other than `MusicService`, if any), documented in
  `service/playback/Playback.java`.

Rationale for pushing the decision to the service: the service already owns
the queue, repeat mode, notifications and toasts. Keeping `MultiPlayer` dumb
avoids duplicated logic in two places and keeps the error paths testable at
the service level.

### 4. Skip advances via the queue, honoring repeat/shuffle

The service's skip uses the existing
`StaticPlayingQueue.getNextPosition(skippedLast)` path (same as
`playNextSong`), so repeat/shuffle are preserved. When the position would
wrap and `REPEAT_MODE_NONE` applies, the queue naturally reports "no next";
in that case we stop cleanly (Decision 6) rather than looping. Under
`REPEAT_MODE_THIS`, `getNextPosition` returns the same track, so the service
must special-case it: toast once and stop (Decision 7) instead of
re-opening.

`prepareNextImpl` consults the registry (Decision 9): if the computed next
URI is known unplayable, it advances to the following playable candidate
and keeps `nextPosition` and the preloaded URI in sync — the preloaded URI
is always the URI of the position stored as `nextPosition`, so a natural
completion lands correctly. This preload-time avoidance is silent: no
toast, no skip-count increment.

### 5. Track identity in the registry

The registry is keyed by the **exact URI string passed to
`setDataSource`** (`getTrackUri(song)`), not by `song.id`. This avoids the
sentinel `id == -1` (`EMPTY_INDEXED_SONG`) collapsing unrelated tracks onto
one key. A track whose URI is `null` or empty is never registered and never
matches; open-time failure for such a track is reported once via the
existing toast path.

### 6. Guard the metadata/cover path and user feedback

`MusicService.updateMediaSessionMetaData` decodes cover art with
`BitmapFactory.decodeStream` without guards. Wrap the decode so a corrupt
embedded image degrades to "no artwork" instead of throwing, and log via
`OopsHandler`. This is part of "the app crashed when playing a broken file":
the crash may be in artwork, not audio.

Reuse `SafeToast.show(context, ...)`. New strings (English policy):

- `unplayable_file_skipped` — "Couldn't play %1$s, skipping." shown once per
  skipped track when the title is known.
- keep the existing `unplayable_file` ("Couldn't play this song.") for the
  unknown-title case and existing call sites.
- `too_many_unplayable_files` — shown when the skip cap is reached, e.g.
  "Too many songs couldn't be played. Stopping."

## What is left to the builder

- Exact naming/location of the session registry (field on `MusicService`
  vs. small class). Intent: keyed by URI string (see §5), in-memory only.
- Exact placement of the `SKIP_RESET_PROGRESS_MS` check (proposed: at the
  moment tracks change, using the existing position tracking; no new timer).
- Whether `onTrackFailed()` is added to `PlaybackCallbacks` directly (with a
  default method) or a separate listener interface.
- Test decomposition and fixtures (reuse `GaplessErrorTest` /
  `QueueSaveHandlerTest` patterns).
- Exact wording of the new strings, subject to the English policy.

## My calls (flagged so they can be checked)

- **`MAX_CONSECUTIVE_SKIPS = 5`.** A sensible default; a wholly broken
  folder stops after five faults instead of spinning.
- **`SKIP_RESET_PROGRESS_MS = 10000`.** Chosen as "the user clearly heard
  something". Configurable constant, not a user setting.
- **`onError` returns `true` for the current-player failure** (suppressing
  `onCompletion`). This is the safer contract and avoids the released-player
  `onCompletion`. It changes behavior that `GaplessErrorTest` currently
  asserts only for the preload branch, not the current-player branch; that
  test will be updated.
- **Registry keyed by URI string** rather than song id (§5).
- **Decision pushed to the service**, not `MultiPlayer`, for single-owner
  skip logic.
- **Preload is discarded on a current-player failure** rather than promoted,
  to keep one advance path.

## Verification plan

- New Robolectric tests (red first):
  - A failing `setDataSource` on the current track advances to the next
    track and records the skip.
  - `MultiPlayer.onError` on the current player: does not advance in the
    player, returns `true`, safely recreates, discards the preload, and
    invokes `onTrackFailed`.
  - Transient mid-track failure (open succeeded, then `onError`) advances
    like any other failure.
  - The counter resets only after `SKIP_RESET_PROGRESS_MS` of playback; a
    chain of open-then-fail tracks still reaches the cap.
  - Consecutive failures stop at the cap with the dedicated message.
  - `REPEAT_MODE_NONE` with a broken last track: clean stop, no wrap.
  - `REPEAT_MODE_THIS` with a broken track: toast once, stop, no re-open.
  - Repeat mode ALL with a broken last track wraps instead of stopping.
  - Preload skips a known-unplayable URI silently and keeps
    `nextPosition` consistent with the preloaded URI.
  - A corrupt embedded cover does not throw from
    `updateMediaSessionMetaData`.
  - Healthy-track regression: open + play + natural completion unchanged.
- Full suite stays green:
  `:app:testFdroidDebugUnitTest`.
- Lint stays at zero errors across the six variants.
