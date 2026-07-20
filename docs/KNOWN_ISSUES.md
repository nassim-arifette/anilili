# Known Issues and Fix Tracking

Last static review: July 20, 2026.

This document is the central tracking list for the audit. Checked boxes indicate that a fix was
implemented and committed on a dedicated local branch; they do not mean that the branch has
already been merged into `main`. Unchecked items still need to be addressed.

The issues below have concrete execution paths in the code, but could not be validated on a real
device because no Android SDK was installed in the audit environment.

**Current status:** 20 fixes implemented, 18 code bugs still open, and 2 documentation fixes still
required.

## Fixes implemented on local branches

Each branch contains a single commit based directly on `main`.

- [x] Authenticate WebView bridge callbacks — `fix/webview-bridge-capabilities`
  (`c0a4ea0`).
- [x] Prevent an old MAL request from overwriting the current session —
  `fix/mal-auth-session-race` (`dbb2f4d`).
- [x] Replace release alarms only with a complete release snapshot —
  `fix/release-sync-atomic-snapshot` (`c577e21`).
- [x] Keep WebView timer lifecycles local to each player —
  `fix/webview-local-lifecycle` (`e99549d`).
- [x] Prevent the hidden Flixcloud resolver from overlapping the active player —
  `fix/flixcloud-player-overlap` (`90c97da`).
- [x] Isolate every logical embed navigation and reject callbacks from the replaced page —
  `fix/embed-navigation-generation` (`3de7291`).
- [x] Reject cancelled or superseded source-resolution results —
  `fix/playback-resolution-generation` (`e1c5f52`).
- [x] Preserve the union of all provider episodes and never substitute episode index zero —
  `fix/episode-spine-union` (`d89d6c1`).
- [x] Add history only after a native or embed player is actually presented —
  `fix/history-on-playback-start` (`3caa052`).
- [x] Attribute native progress to the exact anime, episode, generation, and media item —
  `fix/native-progress-playback-identity` (`5c14b2f`).
- [x] Merge intro and outro markers independently with AniSkip fallback —
  `fix/skip-marker-field-merge` (`015ad71`).
- [x] Keep Skip Outro behavior independent from autoplay —
  `fix/outro-skip-policy` (`76fb9f4`).
- [x] Detect natural completion in same-origin embeds and auto-advance once —
  `fix/embed-natural-end-autoplay` (`c7d83fc`).
- [x] Publish Watch Next again when the episode, provider, or category changes —
  `fix/watch-next-content-aware-throttle` (`c311513`).
- [x] Scope decoder fallback retries to the current stream —
  `fix/player-decoder-retry-per-stream` (`3a775dc`).
- [x] Reject stale or duplicate native episode transitions, including double Next —
  `fix/episode-transition-idempotence` (`fc9a491`).
- [x] Flush paused, sought, stopped, failed, and disposed embed progress outside the periodic
  throttle — `fix/embed-progress-finalization` (`a0246e0`).
- [x] Preserve the paused state when seeking an embed —
  `fix/embed-seek-preserves-pause` (`be7b44a`).
- [x] Prevent a late Cast disconnect from restarting local audio after the player screen is gone —
  `fix/cast-background-disconnect` (`5085e56`).
- [x] Persist the exact terminal native position before autoplay and accept completion only once —
  `fix/native-completion-finalization` (`cd2a9ef`).

## Audit TODO list

- [ ] KI-001 — Recreate WebViews after their renderer is lost.
- [x] KI-002 — Isolate embed callbacks and state for each navigation.
- [x] KI-003 — Prevent cancelled resolutions from publishing stale playback state.
- [ ] KI-004 — Invalidate profile requests on logout or account changes.
- [ ] KI-005 — Protect the AniList token from concurrent writes.
- [ ] KI-006 — Prevent an alarm from being rescheduled while it is being delivered.
- [ ] KI-007 — Point the updater and repository links to the fork.
- [ ] KI-008 — Make the Pipe request lifecycle reliable and cancellable.
- [ ] KI-009 — Give reminders a stable, collision-free identity.
- [ ] KI-010 — Mark only notifications that were actually displayed as delivered.
- [ ] KI-011 — Synchronize the in-memory cache after `putBatch()`.
- [ ] KI-012 — Serialize settings and remote watchlist writes.
- [ ] KI-013 — Wait for preferences to load in the release worker.
- [ ] KI-014 — Implement actual Picture-in-Picture entry.
- [x] KI-015 — Build episode navigation from the union of every provider catalog.
- [x] KI-016 — Record history only when playback really starts.
- [x] KI-017 — Bind native progress callbacks to the exact playback identity.
- [x] KI-018 — Fall back to AniSkip independently for each missing skip range.
- [x] KI-019 — Decouple Skip Outro from autoplay and make the last-episode action valid.
- [x] KI-020 — Handle natural completion and autoplay for observable embeds.
- [x] KI-021 — Key the Watch Next throttle by episode and source context.
- [x] KI-022 — Reset the decoder retry decision for each stream.
- [x] KI-023 — Make native episode transitions stale-safe and idempotent.
- [x] KI-024 — Persist embed progress on pause, seek, stop, failure, navigation, and teardown.
- [x] KI-025 — Do not force a paused embed to play after a seek.
- [x] KI-026 — Keep a late Cast disconnect from reviving background local playback.
- [x] KI-027 — Commit native completion before autoplay and deduplicate terminal events.
- [ ] KI-028 — Obtain progress, resume, skip, and end events from cross-origin embeds.
- [ ] KI-029 — Give the generic web fallback a trustworthy episode and progress identity.
- [ ] KI-030 — Preserve protected-source headers and external subtitles when casting.
- [ ] KI-031 — Keep Cast progress, Next, and autoplay alive after the Watch UI is disposed.
- [ ] KI-032 — Remove a completed final episode from Continue Watching.
- [ ] KI-033 — Use a monotonic clock for process-local progress throttles.
- [ ] DOC-001 — Correct the documented SDK version.
- [ ] DOC-002 — Correct the documented APK name and path.

## Integration and validation TODO list

- [ ] Integrate the 20 fix branches into the selected target branch.
- [ ] Resolve the `WatchViewModel.kt` overlap between source resolution, episode union, playback
  identity, history, skip-marker, Watch Next, and completion branches.
- [ ] Resolve the `EmbedWebView.kt` overlap between bridge security, lifecycle, navigation,
  progress finalization, seek, outro policy, and natural-completion branches.
- [ ] Resolve the `PlayerSurface.kt` / `PlaybackService.kt` overlap between playback identity,
  transition, decoder retry, completion, outro policy, and Cast lifecycle branches.
- [ ] Build the app and run unit tests with Android SDK API 36 configured.
- [ ] Validate rapid A → B episode changes, pause/seek/exit resume, intro/outro, final episode,
  decoder fallback, Cast connect/disconnect, renderer loss, alarms, and account changes on a real
  device.

## Playback-core audit summary

- **Player overlap:** the active native and embed surfaces are selected mutually exclusively, so
  no persistent two-visible-player path was found. Two real lifecycle races could still sound like
  overlapping players: the hidden Flixcloud resolver could outlive cancellation, and a late Cast
  disconnect could resume ExoPlayer after leaving Watch. Both now have dedicated fixes.
- **Last watched episode:** source selection can no longer silently substitute the first episode;
  merely highlighting an episode on TV no longer writes history; native and embed progress now
  have identity/finalization fixes on their own branches.
- **Skip intro/outro:** partial provider markers now use AniSkip for only the missing range. Skip
  Outro seeks to the end when autoplay is disabled and advances only when autoplay and a next
  episode are both available; automatic skipping never fires while playback is paused.
- **Autoplay and transitions:** same-origin embed completion is detected, native terminal events
  are committed before Next, and duplicate/stale transitions are rejected.
- **Branch model:** these fixes intentionally remain independent, one commit directly above
  `main`. Several touch the same player files and must be integrated with the conflict checklist
  above rather than blindly merged.

## Remaining playback-core bugs

### KI-028 — Cross-origin embeds expose no playback telemetry

- **Priority:** high.
- **Impact:** resume position, progress saving, intro/outro skipping, natural-end detection, and
  autoplay remain unavailable when the actual video lives in a cross-origin iframe.
- **Evidence:** `EmbedWebView` can inspect the main document and same-origin frames only. Browser
  origin isolation prevents its injected JavaScript from reading a cross-origin `<video>`.
- **Files:** `ui/watch/EmbedWebView.kt`, provider embed integrations.
- **Required design:** a cooperative `postMessage` protocol, a provider API/native stream, or an
  app-controlled player page; this cannot be made reliable by another DOM polling loop.

### KI-029 — The generic web fallback has no reliable episode identity

- **Priority:** high.
- **Impact:** a user can play an episode on the fallback site while the app cannot safely know
  which episode owns the progress, so resume/history/autoplay can be missing or misattributed.
- **Evidence:** the error fallback opens `https://www.miruro.to/info/<animeId>` rather than an
  episode-specific media item; subsequent selection happens inside the remote page.
- **File:** `ui/watch/WatchScreen.kt`.
- **Required design:** use an episode-specific fallback URL plus an authenticated episode/progress
  bridge, or make the fallback explicitly external and do not claim app-managed resume.

### KI-030 — Cast does not carry every local playback requirement

- **Priority:** high.
- **Impact:** protected sources may fail on the receiver, and external subtitles may disappear.
- **Evidence:** local playback configures `Referer`/`Origin` on `DefaultHttpDataSource` and attaches
  subtitle configurations to the `MediaItem`; a Cast receiver fetches media independently and the
  default conversion does not reproduce that local HTTP data-source behavior.
- **Files:** `playback/PlaybackService.kt`, `ui/watch/PlayerSurface.kt`.
- **Required design:** a custom Cast receiver/converter, an authenticated proxy URL, or providers
  that do not require sender-only headers.

### KI-031 — Cast progress and navigation are owned by the Watch UI

- **Priority:** high.
- **Impact:** after leaving Watch while remote playback continues, progress saving and automatic
  Next stop because the listener and ViewModel that perform those jobs are disposed.
- **Evidence:** progress polling, `onEnded`, and episode navigation are attached in
  `PlayerSurface`; `PlaybackService` owns playback but not the anime/episode history workflow.
- **Files:** `playback/PlaybackService.kt`, `ui/watch/PlayerSurface.kt`,
  `ui/watch/WatchViewModel.kt`.
- **Required design:** move durable playback identity, progress persistence, and Cast queue/Next
  policy into a service-owned coordinator.

### KI-032 — A completed final episode stays in Continue Watching

- **Priority:** medium.
- **Impact:** finishing the last available episode leaves the title at 100% in Continue Watching,
  and reopening it can immediately land at the end.
- **Evidence:** `LibraryStore.updateProgress()` only replaces the one history entry, while
  `ContinueRail` renders every history entry; no completion path removes or archives a final item.
- **Files:** `data/library/LibraryStore.kt`, `ui/home/HomeScreen.kt`, player completion paths.
- **Suggested branch:** `fix/completed-history-cleanup`.

### KI-033 — Wall-clock rollback can freeze progress throttles

- **Priority:** low.
- **Impact:** if the device clock moves backward, progress and Watch Next updates can be suppressed
  until wall time catches up.
- **Evidence:** process-local throttles compare `System.currentTimeMillis()` against their last
  timestamp. A negative delta still satisfies the “too soon” condition.
- **Files:** `ui/watch/WatchViewModel.kt`, `data/tv/WatchNextManager.kt`.
- **Suggested branch:** `fix/monotonic-playback-throttles`.

## High priority

### KI-001 — Dead WebViews are not recreated

- **Impact:** permanently black player, unavailable Pipe resolver until the activity restarts, or
  process termination during OAuth login.
- **Evidence:** `EmbedWebView.onRenderProcessGone()` and `PipeBridge.onRenderProcessGone()` return
  `true`, but their `AndroidView` instances retain the WebView whose renderer died.
  `LoginWebView` does not handle this callback at all. Update and release paths then keep calling
  methods on those invalid instances.
- **Files:** `ui/watch/EmbedWebView.kt`, `data/remote/PipeBridge.kt`,
  `ui/PipeWebView.kt`, `ui/profile/LoginWebView.kt`.
- **Suggested branch:** `fix/webview-renderer-recreation`.

### KI-002 — Callbacks from an old embed modify the new episode

- **Status:** fixed on `fix/embed-navigation-generation` (`3de7291`).
- **Impact:** progress saved against the wrong episode, false playback errors, or controls and
  timestamps that stop updating after changing episode or quality.
- **Evidence:** the same `AndroidView` is reused across URLs. `WebProgressBridge` is constructed
  only once and captures the first states created by `remember(url)`, while late ticks and errors
  use the current callbacks and URL. An event from A can therefore be dropped or attributed to B.
- **Files:** `ui/watch/EmbedWebView.kt`, `ui/watch/WatchScreen.kt`.
- **Implemented branch:** `fix/embed-navigation-generation`.

### KI-003 — A cancelled resolution can publish stale playback state

- **Status:** fixed on `fix/playback-resolution-generation` (`e1c5f52`).
- **Impact:** quickly changing episode or server can jump back to the old episode, incorrectly
  report that no source exists, or mix two provider catalogs.
- **Evidence:** `MiruroRepository.resolveSources()` wraps suspending calls in `runCatching`
  without rethrowing `CancellationException`. `WatchViewModel.launchAnivexaMerge()` does the same,
  then still writes to `spine`, `mergedIncludesAnivexa`, and global state without checking an ID
  or request generation.
- **Files:** `data/MiruroRepository.kt`, `ui/watch/WatchViewModel.kt`.
- **Implemented branch:** `fix/playback-resolution-generation`.

### KI-004 — A profile request can complete after logout

- **Impact:** the private profile and remote watchlist can reappear after logout or an account
  change.
- **Evidence:** `ProfileViewModel.loadIfLoggedIn()` neither retains nor cancels its `Job`, and it
  does not verify the session before `hydrateWatchlistFromAniList()` or writing `_profile`.
  `logout()` only sets `_profile` to `null`.
- **File:** `ui/profile/ProfileViewModel.kt`.
- **Suggested branch:** `fix/profile-session-generation`.

### KI-005 — The AniList expiry check can erase a new token

- **Impact:** immediate, intermittent logout just after a successful authentication.
- **Evidence:** `AuthManager.current()` reads an old expired token and then calls `clearToken()`
  without a lock or generation check. A concurrent `setToken()` between those operations is then
  erased.
- **File:** `data/auth/AuthManager.kt`.
- **Suggested branch:** `fix/anilist-token-generation`.

### KI-006 — An alarm can be rescheduled during its own delivery

- **Impact:** two notifications and two sounds for the same release when an alarm cold-starts the
  process.
- **Evidence:** `MiruroApp.onCreate()` initializes the managers before the receiver runs. Their
  `init()` reloads and reschedules the still-persisted alarm for `now + 1s`; only afterward does
  the receiver remove the record, without cancelling the alarm that was just recreated. The
  scenario affects both manual and automatic reminders.
- **Files:** `MiruroApp.kt`, `data/reminder/ReminderManager.kt`,
  `data/reminder/AutomaticReleaseManager.kt`.
- **Suggested branch:** `fix/reminder-cold-start-delivery`.

### KI-007 — The fork updater still downloads upstream releases

- **Impact:** the app offers an APK that does not belong to the fork and that Android may reject
  because it has a different signature.
- **Evidence:** the Git remote is `nassim-arifette/anilili`, but `RELEASES_LATEST` and the Settings
  link still point to `kompoti121/anilili`.
- **Files:** `data/update/UpdateManager.kt`, `ui/settings/SettingsScreen.kt`.
- **Suggested branch:** `fix/fork-update-source`.

## Medium priority

### KI-008 — The Pipe request lifecycle is unreliable

- **Impact:** waits of roughly 25 seconds even with a short timeout, orphaned requests, and a
  false “ready” state after WebView recreation.
- **Evidence:** `PipeBridge.fetch()` ignores a `false` or `null` result from the `ready` wait and
  then starts a second wait. Cancellation does not consistently remove the request from
  `pending`. Finally, the delayed `onPageFinished()` callback is tied to neither an instance nor a
  generation, so an old WebView can complete the new `ready` signal.
- **File:** `data/remote/PipeBridge.kt`.
- **Suggested branch:** `fix/pipe-request-lifecycle`.

### KI-009 — Different reminders share the same Android identity

- **Impact:** one reminder can replace or cancel another anime's reminder.
- **Evidence:** `requestCode = 31 * mediaId + episode` is not injective: `(100, 32)` and `(101, 1)`
  both produce `3132`. In addition, the functional identifier includes `airingAt`; if the schedule
  changes, the old reminder becomes invisible from the new row and remains armed.
- **File:** `data/reminder/ReminderManager.kt`.
- **Suggested branch:** `fix/reminder-stable-identity`.

### KI-010 — AniList notifications after the eighth are lost

- **Impact:** when more than eight new unread notifications exist, the remaining ones are never
  displayed by a later sync.
- **Evidence:** `notifyUnread()` displays only `freshUnread.take(8)`, but still records every ID in
  `freshUnread` as already delivered.
- **File:** `data/reminder/AniListNotificationPushManager.kt`.
- **Suggested branch:** `fix/notification-delivery-accounting`.

### KI-011 — `putBatch()` leaves stale values in the in-memory cache

- **Impact:** expired season chains are recomputed on every access in the same process despite
  being correctly rewritten on disk.
- **Evidence:** `AppCache.putBatch()` writes only through `dao.putAll()` without replacing or
  invalidating matching entries in `memory`; `read()` always prefers memory.
- **Files:** `data/cache/AppCache.kt`, `data/MiruroRepository.kt` (`animeSeries`).
- **Suggested branch:** `fix/cache-batch-memory-coherence`.

### KI-012 — Fire-and-forget writes can complete in the wrong order

- **Impact:** after rapid changes, the setting restored on the next launch or the remote watchlist
  state can be the opposite of the last choice shown in the UI.
- **Evidence:** `SettingsStore` setters launch independent `store.edit` calls on `Dispatchers.IO`,
  so their arrival order is not guaranteed. `LibraryStore.toggleWatchlist()` also starts one
  remote job per toggle: the mutex serializes execution but does not guarantee that the job for
  the older state acquires the lock first.
- **Files:** `data/settings/SettingsStore.kt`, `data/library/LibraryStore.kt`.
- **Suggested branch:** `fix/serialized-state-writes`.

### KI-013 — The release worker reads preferences before they load

- **Impact:** at startup, a user who disabled notifications can briefly run a synchronization with
  the default value of `true` and recreate alarms.
- **Evidence:** `SettingsStore.init()` loads DataStore in a coroutine, then
  `ReleaseSyncScheduler.schedule()` immediately starts the worker. `ReleaseSyncWorker.doWork()`
  reads `releaseNotifications.value` without calling `SettingsStore.awaitLoaded()`.
- **Files:** `MiruroApp.kt`, `data/settings/SettingsStore.kt`,
  `data/reminder/AutomaticReleaseManager.kt`.
- **Suggested branch:** `fix/release-worker-settings-barrier`.

### KI-014 — Picture-in-Picture is declared but cannot start

- **Impact:** leaving the app pauses playback instead of opening the mini-player.
- **Evidence:** the manifest enables `supportsPictureInPicture` and the activity observes PiP mode,
  but the code contains no call to `enterPictureInPictureMode`, no parameter configuration, and
  no Android 12 auto-enter setup.
- **Files:** `AndroidManifest.xml`, `MainActivity.kt`.
- **Suggested branch:** `fix/picture-in-picture-entry`.

## Build documentation

- **DOC-001 — Required SDK:** the README says API 35, while `compileSdk = 36`.
- **DOC-002 — APK output:** the README says `app-debug.apk`, but Gradle renames every output to
  `anilili.apk`.
